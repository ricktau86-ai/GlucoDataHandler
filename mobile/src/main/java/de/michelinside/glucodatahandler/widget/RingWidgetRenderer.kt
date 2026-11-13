package de.michelinside.glucodatahandler.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import de.michelinside.glucodatahandler.common.GlucoDataService
import de.michelinside.glucodatahandler.common.ReceiveData
import de.michelinside.glucodatahandler.common.database.GlucoseValue
import de.michelinside.glucodatahandler.common.database.dbAccess
import de.michelinside.glucodatahandler.common.notification.AlarmType
import de.michelinside.glucodatahandler.common.utils.GlucoDataUtils
import de.michelinside.glucodatahandler.common.utils.Log
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * "Ring" widget style: draws the whole widget as one bitmap (rounded card, gauge ring,
 * time-in-range ring/bar and a dot chart), so it looks the same on every launcher.
 */
object RingWidgetRenderer {
    private const val LOG_ID = "GDH.widget.RingRenderer"
    private const val MAX_PIXELS = 1_500_000
    private const val CHART_HOURS = 3
    private const val TIR_HOURS = 24
    private const val HISTORY_DAYS = 7

    enum class Kind { STANDARD, MINI, FORECAST, DAY, PARENTS, NIGHT, LOWS }

    class Spec(
        val hasTrend: Boolean,
        val hasDelta: Boolean,
        val hasTime: Boolean,
        val hasIobCob: Boolean,
        val hasOtherUnit: Boolean,
        val hasGraph: Boolean
    )

    private enum class Zone { OK, HIGH, VERY_HIGH, LOW, VERY_LOW, OBSOLETE }

    private class Palette(
        val card: Int,
        val ink: Int,
        val sub: Int,
        val track: Int,
        val accent: Int,
        val filled: Boolean
    )

    /** pink = "Rosa Chiclete" (pink cards, bordeaux ink); otherwise the original green Ring palette (light/dark). */
    private class Colors(dark: Boolean, val pink: Boolean) {
        val green = if (pink) 0xFF831843.toInt() else if (dark) 0xFF4ADE80.toInt() else 0xFF15803D.toInt()
        val amber = if (pink) 0xFFB45309.toInt() else if (dark) 0xFFFBBF24.toInt() else 0xFFD97706.toInt()
        val red = if (pink) 0xFFDC2626.toInt() else if (dark) 0xFFF87171.toInt() else 0xFFB91C1C.toInt()
        val card = if (pink) 0xFFF9A8D4.toInt() else if (dark) 0xFF1E1D1C.toInt() else 0xFFFFFFFF.toInt()
        val ink = if (pink) 0xFF500724.toInt() else if (dark) 0xFFF5F5F4.toInt() else 0xFF1C1917.toInt()
        val sub = if (pink) 0xFF6B0F3A.toInt() else if (dark) 0xFFB5AFA9.toInt() else 0xFF57534E.toInt()
        val track = if (pink) 0xFFFBCFE8.toInt() else if (dark) 0xFF3A3836.toInt() else 0xFFECE7DF.toInt()
        val grid = if (pink) 0xFFFBCFE8.toInt() else if (dark) 0xFF3A3836.toInt() else 0xFFE7E5E4.toInt()
        val obsCard = if (pink) 0xFFF5F5F4.toInt() else if (dark) 0xFF2A2928.toInt() else 0xFFE7E5E4.toInt()
        val obsInk = if (dark && !pink) 0xFF9C9690.toInt() else 0xFF78716C.toInt()
    }

    // ---------- history cache (shared by all widgets) ----------
    private var cacheKey = -1L
    private var cacheTime = 0L
    private var history: List<GlucoseValue> = emptyList()

    private fun loadHistory() {
        val now = System.currentTimeMillis()
        if (cacheKey == ReceiveData.time && now - cacheTime < 60_000L)
            return
        history = try {
            dbAccess.getGlucoseValues(now - HISTORY_DAYS * 86_400_000L)
        } catch (exc: Exception) {
            Log.e(LOG_ID, "loadHistory exception: " + exc.message.toString())
            emptyList()
        }
        cacheKey = ReceiveData.time
        cacheTime = now
    }

    private class Tir(val low: Float, val inRange: Float, val high: Float)

    /** set per render: true = time in range of today (00:00-23:59), false = last 24 hours */
    private var tirToday = false

    private fun startOfToday(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun getTir(): Tir? {
        val values = if (tirToday) {
            val start = startOfToday()
            history.filter { it.timestamp >= start }
        } else {
            val start = System.currentTimeMillis() - TIR_HOURS * 3_600_000L
            history.filter { it.timestamp >= start }
        }
        if (values.size < (if (tirToday) 3 else 12))
            return null
        val minT = ReceiveData.targetMinRaw
        val maxT = ReceiveData.targetMaxRaw
        var lo = 0
        var ok = 0
        var hi = 0
        values.forEach {
            when {
                it.value < minT -> lo++
                it.value > maxT -> hi++
                else -> ok++
            }
        }
        val total = (lo + ok + hi).toFloat()
        return Tir(lo / total, ok / total, hi / total)
    }

    private fun tirPeriod(context: Context): String =
        if (tirToday) (if (isPt(context)) "hoje" else "today") else "24h"

    // ---------- state ----------
    private fun getZone(): Zone {
        if (ReceiveData.time == 0L)
            return Zone.OBSOLETE
        return when (ReceiveData.getAlarmType()) {
            AlarmType.OBSOLETE -> Zone.OBSOLETE
            AlarmType.VERY_LOW -> Zone.VERY_LOW
            AlarmType.LOW -> Zone.LOW
            AlarmType.VERY_HIGH -> Zone.VERY_HIGH
            AlarmType.HIGH -> Zone.HIGH
            else -> Zone.OK
        }
    }

    private fun getPalette(zone: Zone, c: Colors): Palette {
        if (c.pink) {
            // every out-of-range state fills the whole card so it never blends with the pink
            return when (zone) {
                Zone.VERY_LOW -> Palette(0xFF7F1D1D.toInt(), 0xFFFFFFFF.toInt(), 0xFFFECACA.toInt(), 0x55FFFFFF, 0xFFFFFFFF.toInt(), true)
                Zone.LOW -> Palette(0xFFDC2626.toInt(), 0xFFFFFFFF.toInt(), 0xFFFEE2E2.toInt(), 0x55FFFFFF, 0xFFFFFFFF.toInt(), true)
                Zone.VERY_HIGH -> Palette(0xFFF59E0B.toInt(), 0xFF1C1917.toInt(), 0xFF451A03.toInt(), 0x33000000, 0xFF1C1917.toInt(), true)
                Zone.HIGH -> Palette(0xFFFDE68A.toInt(), 0xFF1C1917.toInt(), 0xFF713F12.toInt(), 0x33000000, 0xFF1C1917.toInt(), true)
                Zone.OBSOLETE -> Palette(c.obsCard, c.obsInk, c.obsInk, 0xFFD6D3D1.toInt(), c.obsInk, false)
                Zone.OK -> Palette(c.card, c.ink, c.sub, c.track, c.green, false)
            }
        }
        return when (zone) {
            Zone.VERY_LOW -> Palette(0xFFB91C1C.toInt(), 0xFFFFFFFF.toInt(), 0xFFFECACA.toInt(), 0x55FFFFFF, 0xFFFFFFFF.toInt(), true)
            Zone.VERY_HIGH -> Palette(0xFFF59E0B.toInt(), 0xFF1C1917.toInt(), 0xFF451A03.toInt(), 0x33000000, 0xFF1C1917.toInt(), true)
            Zone.LOW -> Palette(c.card, c.ink, c.sub, c.track, c.red, false)
            Zone.HIGH -> Palette(c.card, c.ink, c.sub, c.track, c.amber, false)
            Zone.OBSOLETE -> Palette(c.obsCard, c.obsInk, c.obsInk, c.track, c.obsInk, false)
            Zone.OK -> Palette(c.card, c.ink, c.sub, c.track, c.green, false)
        }
    }

    private fun isPt(context: Context): Boolean =
        context.resources.configuration.locales[0].language == "pt"

    private fun zoneLabel(context: Context, zone: Zone): String {
        val pt = isPt(context)
        return when (zone) {
            Zone.OK -> if (pt) "No alvo" else "In range"
            Zone.HIGH -> if (pt) "Alta" else "High"
            Zone.VERY_HIGH -> if (pt) "Muito alta" else "Very high"
            Zone.LOW -> if (pt) "Baixa" else "Low"
            Zone.VERY_LOW -> if (pt) "Muito baixa" else "Very low"
            Zone.OBSOLETE -> if (pt) "Sem dados" else "No data"
        }
    }

    private fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun valueColor(mg: Int, c: Colors): Int {
        return when {
            ReceiveData.lowRaw > 0F && mg <= ReceiveData.lowRaw -> c.red
            mg < ReceiveData.targetMinRaw -> c.red
            ReceiveData.highRaw > 0F && mg >= ReceiveData.highRaw -> c.amber
            mg > ReceiveData.targetMaxRaw -> c.amber
            else -> c.green
        }
    }

    private fun glucoseFraction(): Float {
        val mg = ReceiveData.rawValue.toFloat()
        return ((mg - 40F) / 260F).coerceIn(0.04F, 1F)
    }

    // ---------- public entry ----------
    fun getContentDescription(context: Context): String =
        try { ReceiveData.getAsText(context, true) } catch (exc: Exception) { "" }

    fun render(context: Context, spec: Spec, widthDp: Int, heightDp: Int, transparency: Int, pink: Boolean = false, today: Boolean = false, kind: Kind = Kind.STANDARD): Bitmap {
        tirToday = today
        val wDp = if (widthDp > 0) widthDp else 110
        val hDp = if (heightDp > 0) heightDp else 110
        var density = context.resources.displayMetrics.density
        if (wDp * hDp * density * density > MAX_PIXELS)
            density = Math.sqrt(MAX_PIXELS.toDouble() / (wDp * hDp)).toFloat()
        val w = max(1, (wDp * density).toInt())
        val h = max(1, (hDp * density).toInt())
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val c = Colors(isDark(context), pink)
            val zone = getZone()
            val p = if (kind == Kind.DAY || kind == Kind.NIGHT || kind == Kind.LOWS) getPalette(Zone.OK, c) else getPalette(zone, c)
            val d = density
            loadHistory()

            // card
            val cardAlpha = when {
                p.filled -> 245
                transparency <= 0 -> 0
                else -> 140 + (115 * min(10, transparency) / 10)
            }
            val radius = min(min(w, h) * 0.24F, 28F * d)
            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            cardPaint.color = p.card
            cardPaint.alpha = cardAlpha
            canvas.drawRoundRect(RectF(0F, 0F, w.toFloat(), h.toFloat()), radius, radius, cardPaint)

            val ratio = wDp.toFloat() / hDp.toFloat()
            when {
                kind == Kind.MINI -> drawMiniLayout(context, canvas, w, h, d, p, zone)
                kind == Kind.FORECAST -> drawForecastLayout(context, canvas, w, h, d, p, c, zone)
                kind == Kind.DAY -> drawDayLayout(context, canvas, w, h, d, p, c)
                kind == Kind.PARENTS -> drawParentsLayout(context, canvas, w, h, d, p, c, zone)
                kind == Kind.NIGHT -> drawNightLayout(context, canvas, w, h, d, p, c)
                kind == Kind.LOWS -> drawLowsLayout(context, canvas, w, h, d, p, c)
                spec.hasGraph && hDp >= 110 && wDp >= 150 -> drawChartLayout(context, canvas, spec, w, h, d, p, c, zone)
                ratio >= 1.6F && hDp >= 50 -> drawWideLayout(context, canvas, spec, w, h, d, p, c, zone)
                else -> drawCompactLayout(context, canvas, spec, w, h, d, p, zone)
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "render exception: " + exc.message.toString())
        }
        return bitmap
    }

    // ---------- helpers ----------
    private val heavy: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    private fun textPaint(tf: Typeface, color: Int, size: Float, align: Paint.Align = Paint.Align.LEFT): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = tf
        paint.color = color
        paint.textSize = size
        paint.textAlign = align
        return paint
    }

    /** shrinks the paint's text size until text fits in maxWidth */
    private fun fit(paint: Paint, text: String, maxWidth: Float, maxSize: Float): Paint {
        paint.textSize = maxSize
        val tw = paint.measureText(text)
        if (tw > maxWidth && tw > 0F)
            paint.textSize = max(4F, maxSize * maxWidth / tw)
        return paint
    }

    private fun glucoseText(spec: Spec): String =
        if (spec.hasOtherUnit) ReceiveData.getGlucoseAsOtherUnit() else ReceiveData.getGlucoseAsString()

    private fun unitText(spec: Spec): String =
        if (spec.hasOtherUnit) ReceiveData.getOtherUnit() else ReceiveData.getUnit()

    private fun deltaText(spec: Spec): String =
        if (spec.hasOtherUnit) ReceiveData.getDeltaAsOtherUnit() else ReceiveData.getDeltaAsString()

    /** trend arrow centred at (cx, cy) */
    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val rate = ReceiveData.rate
        if (rate.isNaN() || ReceiveData.isObsoleteShort())
            return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1.5F, size * 0.16F)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val degrees = GlucoDataUtils.getRateDegrees(rate)
        val double = abs(rate) >= 3F
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(-degrees.toFloat())
        val half = size / 2F
        val head = size * 0.36F
        val path = Path()
        path.moveTo(-half, 0F)
        path.lineTo(half, 0F)
        path.moveTo(half - head, -head)
        path.lineTo(half, 0F)
        path.lineTo(half - head, head)
        if (double) {
            path.moveTo(half - head * 2.1F, -head)
            path.lineTo(half - head * 1.1F, 0F)
            path.lineTo(half - head * 2.1F, head)
        }
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private fun drawGauge(canvas: Canvas, rect: RectF, stroke: Float, p: Palette, zone: Zone) {
        val track = Paint(Paint.ANTI_ALIAS_FLAG)
        track.style = Paint.Style.STROKE
        track.strokeWidth = stroke
        track.strokeCap = Paint.Cap.ROUND
        track.color = p.track
        if (zone == Zone.OBSOLETE)
            track.pathEffect = DashPathEffect(floatArrayOf(stroke * 0.6F, stroke * 0.9F), 0F)
        canvas.drawArc(rect, 135F, 270F, false, track)
        if (zone == Zone.OBSOLETE)
            return
        val value = Paint(track)
        value.pathEffect = null
        value.color = p.accent
        canvas.drawArc(rect, 135F, 270F * glucoseFraction(), false, value)
    }

    private fun drawTirRing(context: Context, canvas: Canvas, cx: Float, cy: Float, r: Float, stroke: Float, tir: Tir, p: Palette, c: Colors) {
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        val gap = if (tir.low > 0F && tir.high > 0F) 2F else 0F
        var start = -90F
        val segments = listOf(Pair(tir.inRange, if (p.filled) p.accent else c.green), Pair(tir.high, c.amber), Pair(tir.low, c.red))
        segments.forEach {
            if (it.first > 0F) {
                val sweep = 360F * it.first
                paint.color = it.second
                canvas.drawArc(rect, start, max(0.5F, sweep - gap), false, paint)
                start += sweep
            }
        }
        val pct = Math.round(tir.inRange * 100F).toString() + "%"
        val numPaint = fit(textPaint(heavy, p.ink, r * 0.62F, Paint.Align.CENTER), pct, r * 1.4F, r * 0.62F)
        canvas.drawText(pct, cx, cy + numPaint.textSize * 0.25F, numPaint)
        val cap = (if (isPt(context)) "alvo " else "range ") + tirPeriod(context)
        val capPaint = fit(textPaint(regular, p.sub, r * 0.26F, Paint.Align.CENTER), cap, r * 1.35F, r * 0.26F)
        canvas.drawText(cap, cx, cy + numPaint.textSize * 0.25F + capPaint.textSize * 1.3F, capPaint)
    }

    private fun metaLine(context: Context, spec: Spec): String {
        val parts = ArrayList<String>()
        if (spec.hasDelta) parts.add("Δ " + deltaText(spec))
        if (spec.hasTime) parts.add(ReceiveData.getElapsedTimeMinuteAsString(context))
        if (spec.hasIobCob) {
            if (!ReceiveData.iob.isNaN()) parts.add("IOB " + ReceiveData.getIobAsString())
            if (!ReceiveData.cob.isNaN()) parts.add("COB " + ReceiveData.getCobAsString())
        }
        return parts.joinToString("  ·  ")
    }

    // ---------- layouts ----------
    private fun drawCompactLayout(context: Context, canvas: Canvas, spec: Spec, w: Int, h: Int, d: Float, p: Palette, zone: Zone) {
        val size = min(w, h).toFloat()
        val pad = size * 0.08F
        val stroke = max(3F * d, size * 0.07F)
        val cx = w / 2F
        val cy = h / 2F
        val r = size / 2F - pad - stroke / 2F
        drawGauge(canvas, RectF(cx - r, cy - r, cx + r, cy + r), stroke, p, zone)

        val inner = (r - stroke) * 2F
        val text = glucoseText(spec)
        val showSub = size >= 90F * d && (spec.hasTrend || spec.hasDelta || spec.hasTime)
        val numPaint = fit(textPaint(heavy, p.ink, inner * 0.42F, Paint.Align.CENTER), text, inner * 0.82F, inner * 0.42F)
        if (zone == Zone.OBSOLETE)
            numPaint.isStrikeThruText = !ReceiveData.isObsoleteLong()
        val numY = if (showSub) cy + numPaint.textSize * 0.2F else cy + numPaint.textSize * 0.35F
        canvas.drawText(text, cx, numY, numPaint)

        if (showSub) {
            val subSize = inner * 0.12F
            val sub = ArrayList<String>()
            if (spec.hasDelta) sub.add(deltaText(spec))
            if (spec.hasTime || zone == Zone.OBSOLETE) sub.add(ReceiveData.getElapsedTimeMinuteAsString(context))
            val subText = sub.joinToString(" · ")
            val subPaint = fit(textPaint(medium, p.sub, subSize, Paint.Align.CENTER), subText, inner * 0.7F, subSize)
            val subY = numY + subPaint.textSize * 1.5F
            val arrowSize = subPaint.textSize * 1.1F
            if (spec.hasTrend && subText.isNotEmpty()) {
                val tw = subPaint.measureText(subText)
                val total = arrowSize + subPaint.textSize * 0.4F + tw
                val startX = cx - total / 2F
                drawArrow(canvas, startX + arrowSize / 2F, subY - subPaint.textSize * 0.33F, arrowSize, p.accent)
                subPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(subText, startX + arrowSize + subPaint.textSize * 0.4F, subY, subPaint)
            } else if (spec.hasTrend) {
                drawArrow(canvas, cx, subY - arrowSize * 0.3F, arrowSize * 1.4F, p.accent)
            } else {
                canvas.drawText(subText, cx, subY, subPaint)
            }
        } else if (spec.hasTrend && size >= 60F * d) {
            drawArrow(canvas, cx, cy + r * 0.62F, inner * 0.16F, p.accent)
        }
    }

    private fun drawWideLayout(context: Context, canvas: Canvas, spec: Spec, w: Int, h: Int, d: Float, p: Palette, c: Colors, zone: Zone) {
        val pad = min(h * 0.14F, 20F * d)
        val sideSize = h - pad * 2F
        val tir = if (spec.hasIobCob || spec.hasTime) getTir() else null
        val showSide = w - sideSize - pad * 3F > sideSize * 1.2F
        val textRight = if (showSide) w - sideSize - pad * 2F else w - pad

        // right side: time-in-range ring, or gauge with arrow
        if (showSide) {
            val cx = w - pad - sideSize / 2F
            val cy = h / 2F
            val stroke = max(3F * d, sideSize * 0.09F)
            val r = sideSize / 2F - stroke / 2F
            if (tir != null && zone != Zone.OBSOLETE) {
                drawTirRing(context, canvas, cx, cy, r, stroke, tir, p, c)
            } else {
                drawGauge(canvas, RectF(cx - r, cy - r, cx + r, cy + r), stroke, p, zone)
                if (spec.hasTrend) drawArrow(canvas, cx, cy, r * 0.8F, p.accent)
            }
        }

        val avail = textRight - pad
        val showStatus = h >= 80F * d
        val meta = metaLine(context, spec)
        val showMeta = meta.isNotEmpty() && h >= 64F * d
        var y = pad
        if (showStatus) {
            val label = zoneLabel(context, zone) + (if (spec.hasTrend && zone != Zone.OBSOLETE) " · " + ReceiveData.getRateAsText(context).lowercase(Locale.getDefault()) else "")
            val sPaint = fit(textPaint(medium, p.sub, h * 0.11F, Paint.Align.LEFT), label, avail - h * 0.12F, h * 0.11F)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG)
            dot.color = p.accent
            val dotR = sPaint.textSize * 0.32F
            y += sPaint.textSize
            canvas.drawCircle(pad + dotR, y - sPaint.textSize * 0.34F, dotR, dot)
            canvas.drawText(label, pad + dotR * 2F + sPaint.textSize * 0.45F, y, sPaint)
        }
        val numMax = if (showStatus && showMeta) h * 0.42F else if (showStatus || showMeta) h * 0.5F else h * 0.62F
        val text = glucoseText(spec)
        val unit = unitText(spec)
        val numPaint = fit(textPaint(heavy, p.ink, numMax, Paint.Align.LEFT), text, avail * 0.62F, numMax)
        if (zone == Zone.OBSOLETE)
            numPaint.isStrikeThruText = !ReceiveData.isObsoleteLong()
        val numY = if (showStatus || showMeta) y + numPaint.textSize * 0.98F + (if (showStatus) h * 0.03F else 0F) else h / 2F + numPaint.textSize * 0.36F
        canvas.drawText(text, pad, numY, numPaint)
        var x = pad + numPaint.measureText(text) + numPaint.textSize * 0.12F
        if (spec.hasTrend && !showStatus && !showSide) {
            val aSize = numPaint.textSize * 0.5F
            drawArrow(canvas, x + aSize / 2F, numY - numPaint.textSize * 0.36F, aSize, p.accent)
            x += aSize + numPaint.textSize * 0.12F
        }
        val uPaint = fit(textPaint(regular, p.sub, numPaint.textSize * 0.3F, Paint.Align.LEFT), unit, max(1F, textRight - x), numPaint.textSize * 0.3F)
        if (textRight - x > uPaint.textSize * 2F)
            canvas.drawText(unit, x, numY, uPaint)

        if (showMeta) {
            val mPaint = fit(textPaint(medium, p.ink, h * 0.115F, Paint.Align.LEFT), meta, avail, h * 0.115F)
            canvas.drawText(meta, pad, h - pad, mPaint)
        }
    }

    private fun drawChartLayout(context: Context, canvas: Canvas, spec: Spec, w: Int, h: Int, d: Float, p: Palette, c: Colors, zone: Zone) {
        val pad = min(w * 0.05F, 20F * d)
        val headH = min(h * 0.3F, 56F * d)
        val text = glucoseText(spec)
        val numPaint = fit(textPaint(heavy, p.ink, headH * 0.78F, Paint.Align.LEFT), text, w * 0.3F, headH * 0.78F)
        if (zone == Zone.OBSOLETE)
            numPaint.isStrikeThruText = !ReceiveData.isObsoleteLong()
        val numY = pad + numPaint.textSize * 0.92F
        canvas.drawText(text, pad, numY, numPaint)
        var x = pad + numPaint.measureText(text) + numPaint.textSize * 0.18F
        val aSize = numPaint.textSize * 0.42F
        if (spec.hasTrend) {
            drawArrow(canvas, x + aSize / 2F, numY - numPaint.textSize * 0.32F, aSize, p.accent)
            x += aSize + numPaint.textSize * 0.15F
        }

        // time in range bar on the right
        val tir = getTir()
        val barW = w * 0.36F
        val showBar = tir != null && w >= 250F * d
        val infoRight = if (showBar) w - pad - barW - pad else w - pad
        val line1 = unitText(spec) + " · " + ReceiveData.getElapsedTimeMinuteAsString(context)
        val iob = ArrayList<String>()
        if (!ReceiveData.iob.isNaN()) iob.add("IOB " + ReceiveData.getIobAsString())
        if (!ReceiveData.cob.isNaN()) iob.add("COB " + ReceiveData.getCobAsString())
        if (spec.hasDelta) iob.add("Δ " + deltaText(spec))
        val line2 = iob.joinToString("  ")
        val infoW = max(1F, infoRight - x)
        val l1 = fit(textPaint(regular, p.sub, headH * 0.24F), line1, infoW, headH * 0.24F)
        val l2 = fit(textPaint(medium, p.ink, headH * 0.24F), line2, infoW, headH * 0.24F)
        canvas.drawText(line1, x, numY - numPaint.textSize * 0.42F, l1)
        if (line2.isNotEmpty()) canvas.drawText(line2, x, numY, l2)

        if (showBar && tir != null) {
            val bx = w - pad - barW
            val bh = max(6F * d, headH * 0.16F)
            val by = pad + headH * 0.25F
            val clip = Path()
            clip.addRoundRect(RectF(bx, by, bx + barW, by + bh), bh / 2F, bh / 2F, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            val seg = Paint(Paint.ANTI_ALIAS_FLAG)
            var sx = bx
            listOf(Pair(tir.low, c.red), Pair(tir.inRange, if (p.filled) p.accent else c.green), Pair(tir.high, c.amber)).forEach {
                seg.color = it.second
                val sw = barW * it.first
                canvas.drawRect(sx, by, sx + sw, by + bh, seg)
                sx += sw
            }
            canvas.restore()
            val pt = isPt(context)
            val cap = (if (pt) "No alvo " else "In range ") + tirPeriod(context) + " " + Math.round(tir.inRange * 100F) + "%"
            val capPaint = fit(textPaint(medium, p.sub, headH * 0.21F, Paint.Align.RIGHT), cap, barW, headH * 0.21F)
            canvas.drawText(cap, w - pad, by + bh + capPaint.textSize * 1.4F, capPaint)
        }

        // chart
        val top = pad + headH + pad * 0.5F
        val bottom = h - pad
        val left = pad
        val right = w - pad
        if (bottom - top < 30F * d)
            return
        val now = System.currentTimeMillis()
        val minTime = now - CHART_HOURS * 3_600_000L
        val values = history.filter { it.timestamp >= minTime }
        val minT = ReceiveData.targetMinRaw
        val maxT = ReceiveData.targetMaxRaw
        var lo = minT - 30F
        var hi = maxT + 30F
        values.forEach {
            lo = min(lo, it.value - 10F)
            hi = max(hi, it.value + 10F)
        }
        lo = max(30F, lo)
        val labelPaint = textPaint(regular, p.sub, max(9F * d, (bottom - top) * 0.07F))
        val chartBottom = bottom - labelPaint.textSize * 1.4F
        fun yOf(v: Float): Float = chartBottom - (v - lo) / (hi - lo) * (chartBottom - top)
        fun xOf(t: Long): Float = left + (t - minTime).toFloat() / (now - minTime).toFloat() * (right - left)

        // target band lines
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        gridPaint.color = if (p.filled) p.track else c.grid
        gridPaint.strokeWidth = max(1F, d)
        listOf(minT, maxT).forEach {
            val gy = yOf(it)
            canvas.drawLine(left, gy, right, gy, gridPaint)
            val lbl = if (ReceiveData.isMmol) "%.1f".format(GlucoDataUtils.mgToMmol(it)) else it.toInt().toString()
            canvas.drawText(lbl, left, gy - labelPaint.textSize * 0.35F, labelPaint)
        }
        canvas.drawText("-" + CHART_HOURS + "h", left, bottom, labelPaint)
        val nowLbl = if (isPt(context)) "agora" else "now"
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(nowLbl, right, bottom, labelPaint)

        if (values.isEmpty())
            return
        val dotR = ((right - left) / (values.size * 2.6F)).coerceIn(1.5F * d, 4F * d)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        values.forEach {
            dot.color = if (p.filled) p.accent else valueColor(it.value, c)
            canvas.drawCircle(xOf(it.timestamp), yOf(it.value.toFloat()), dotR, dot)
        }
        val last = values.maxByOrNull { it.timestamp }!!
        val lx = xOf(last.timestamp)
        val ly = yOf(last.value.toFloat())
        dot.color = if (p.filled) p.accent else valueColor(last.value, c)
        dot.alpha = 60
        canvas.drawCircle(lx, ly, dotR * 3F, dot)
        dot.alpha = 255
        canvas.drawCircle(lx, ly, dotR * 1.7F, dot)
    }

    // =====================================================================
    // Extra widgets: mini, forecast, day bars, parents, night summary, lows
    // =====================================================================

    private fun lowLimit(): Float =
        if (ReceiveData.lowRaw > 0F) ReceiveData.lowRaw else ReceiveData.targetMinRaw

    private fun fmtGlucose(mg: Float): String =
        if (ReceiveData.isMmol) String.format(Locale.getDefault(), "%.1f", GlucoDataUtils.mgToMmol(mg)) else Math.round(mg).toString()

    private fun hhmm(time: Long): String =
        java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(time))

    private fun dayStart(daysAgo: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -daysAgo)
        return cal.timeInMillis
    }

    private fun whenText(context: Context, time: Long): String {
        val pt = isPt(context)
        return when {
            time >= dayStart(0) -> (if (pt) "hoje " else "today ") + hhmm(time)
            time >= dayStart(1) -> (if (pt) "ontem " else "yesterday ") + hhmm(time)
            else -> java.text.SimpleDateFormat("EEE HH:mm", context.resources.configuration.locales[0]).format(java.util.Date(time))
        }
    }

    private class LowEpisode(val start: Long, val end: Long, val min: Int, val minTime: Long)

    /** consecutive values below the low limit form one episode */
    private fun lowEpisodes(values: List<GlucoseValue>): List<LowEpisode> {
        val limit = lowLimit()
        val result = ArrayList<LowEpisode>()
        var inLow = false
        var start = 0L
        var last = 0L
        var mn = 0
        var mnTime = 0L
        values.sortedBy { it.timestamp }.forEach {
            if (it.value < limit) {
                if (!inLow || it.timestamp - last > 30 * 60_000L) {
                    if (inLow) result.add(LowEpisode(start, last + 5 * 60_000L, mn, mnTime))
                    inLow = true
                    start = it.timestamp
                    mn = it.value
                    mnTime = it.timestamp
                } else if (it.value < mn) {
                    mn = it.value
                    mnTime = it.timestamp
                }
                last = it.timestamp
            } else if (inLow) {
                result.add(LowEpisode(start, max(last + 5 * 60_000L, it.timestamp), mn, mnTime))
                inLow = false
            }
        }
        if (inLow) result.add(LowEpisode(start, last + 5 * 60_000L, mn, mnTime))
        return result
    }

    /** slope in mg/dL per minute from the last 15 minutes (least squares); falls back to the source rate */
    private fun recentSlope(): Float {
        val now = System.currentTimeMillis()
        val pts = history.filter { it.timestamp >= now - 16 * 60_000L }
        if (pts.size >= 3) {
            val t0 = pts.minOf { it.timestamp }
            val xs = pts.map { (it.timestamp - t0) / 60_000F }
            val ys = pts.map { it.value.toFloat() }
            val mx = xs.average().toFloat()
            val my = ys.average().toFloat()
            var num = 0F
            var den = 0F
            for (i in xs.indices) {
                num += (xs[i] - mx) * (ys[i] - my)
                den += (xs[i] - mx) * (xs[i] - mx)
            }
            if (den > 0F) return num / den
        }
        return ReceiveData.rate
    }

    private fun trendWord(context: Context, slope: Float): String {
        val pt = isPt(context)
        return when {
            slope.isNaN() -> ""
            slope >= 1F -> if (pt) "a subir" else "rising"
            slope <= -1F -> if (pt) "a descer" else "falling"
            else -> if (pt) "estável" else "stable"
        }
    }

    private fun drawTile(canvas: Canvas, rect: RectF, label: String, value: String, valueColor: Int, p: Palette, c: Colors, d: Float) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.color = if (p.filled) 0x33FFFFFF else c.track
        val r = min(rect.height() * 0.28F, 14F * d)
        canvas.drawRoundRect(rect, r, r, bg)
        val padX = rect.height() * 0.16F
        val lPaint = fit(textPaint(regular, p.sub, rect.height() * 0.24F), label, rect.width() - padX * 2F, rect.height() * 0.24F)
        canvas.drawText(label, rect.left + padX, rect.top + rect.height() * 0.38F, lPaint)
        val vPaint = fit(textPaint(heavy, valueColor, rect.height() * 0.36F), value, rect.width() - padX * 2F, rect.height() * 0.36F)
        canvas.drawText(value, rect.left + padX, rect.bottom - rect.height() * 0.18F, vPaint)
    }

    // ---------- 1 · mini ----------
    private fun drawMiniLayout(context: Context, canvas: Canvas, w: Int, h: Int, d: Float, p: Palette, zone: Zone) {
        val size = min(w, h).toFloat()
        val cx = w / 2F
        val cy = h / 2F
        val text = ReceiveData.getGlucoseAsString()
        if (p.filled || zone == Zone.OBSOLETE) {
            val label = zoneLabel(context, zone).uppercase(Locale.getDefault())
            val numPaint = fit(textPaint(heavy, p.ink, size * 0.36F, Paint.Align.CENTER), text, size * 0.8F, size * 0.36F)
            if (zone == Zone.OBSOLETE) numPaint.isStrikeThruText = !ReceiveData.isObsoleteLong()
            canvas.drawText(text, cx, cy + numPaint.textSize * 0.15F, numPaint)
            val lPaint = fit(textPaint(medium, p.ink, size * 0.12F, Paint.Align.CENTER), label, size * 0.8F, size * 0.12F)
            canvas.drawText(label, cx, cy + numPaint.textSize * 0.15F + lPaint.textSize * 1.6F, lPaint)
            return
        }
        val stroke = max(2.5F * d, size * 0.085F)
        val r = size / 2F - size * 0.08F - stroke / 2F
        drawGauge(canvas, RectF(cx - r, cy - r, cx + r, cy + r), stroke, p, zone)
        val inner = (r - stroke) * 2F
        val numPaint = fit(textPaint(heavy, p.ink, inner * 0.46F, Paint.Align.CENTER), text, inner * 0.86F, inner * 0.46F)
        canvas.drawText(text, cx, cy + numPaint.textSize * 0.2F, numPaint)
        if (size >= 64F * d)
            drawArrow(canvas, cx, cy + r * 0.66F, inner * 0.2F, p.accent)
    }

    // ---------- 2 · forecast 20 min ----------
    /** minutes until value crosses limit at the given slope, or -1 if not within 20 min */
    private fun minutesTo(now: Float, limit: Float, slope: Float): Int {
        if (slope == 0F) return -1
        val m = (limit - now) / slope
        return if (m > 0F && m <= 20F) max(1, Math.round(m)) else -1
    }

    private fun forecastMessage(context: Context, now: Float, pred: Float, slope: Float): String {
        val pt = isPt(context)
        val tMin = ReceiveData.targetMinRaw
        val tMax = ReceiveData.targetMaxRaw
        val low = lowLimit()
        val rising = slope >= 1F
        val falling = slope <= -1F
        fun inMin(m: Int): String = if (m > 0) (if (pt) " em ~$m min" else " in ~$m min") else ""
        return when {
            now > tMax -> when {
                rising -> if (pt) "Acima do alvo e ainda a subir" else "Above target and still rising"
                falling && pred <= tMax -> (if (pt) "A descer · deve voltar ao alvo" else "Falling · should be back in range") + inMin(minutesTo(now, tMax, slope))
                falling -> if (pt) "A descer, mas ainda acima do alvo" else "Falling, but still above target"
                else -> if (pt) "Estável acima do alvo" else "Steady above target"
            }
            now < tMin -> when {
                rising && pred >= tMin -> (if (pt) "A subir · deve voltar ao alvo" else "Rising · should be back in range") + inMin(minutesTo(now, tMin, slope))
                rising -> if (pt) "A subir, mas ainda abaixo do alvo" else "Rising, but still below target"
                falling -> if (pt) "Abaixo do alvo e ainda a descer" else "Below target and still falling"
                else -> if (pt) "Estável abaixo do alvo" else "Steady below target"
            }
            pred < low -> (if (pt) "Pode descer abaixo de " else "Could drop below ") + fmtGlucose(low) + inMin(minutesTo(now, low, slope))
            pred < tMin -> (if (pt) "Pode sair do alvo (abaixo de " else "Could go below ") + fmtGlucose(tMin) + (if (pt) ")" else "") + inMin(minutesTo(now, tMin, slope))
            pred > tMax -> (if (pt) "Pode passar de " else "Could pass ") + fmtGlucose(tMax) + inMin(minutesTo(now, tMax, slope))
            else -> if (pt) "Deve manter-se no alvo" else "Should stay in range"
        }
    }

    private fun drawForecastLayout(context: Context, canvas: Canvas, w: Int, h: Int, d: Float, p: Palette, c: Colors, zone: Zone) {
        val pt = isPt(context)
        val pad = min(h * 0.14F, 20F * d)
        val text = ReceiveData.getGlucoseAsString()
        val unit = min(h * 0.3F, w * 0.15F)
        val numPaint = fit(textPaint(heavy, p.ink, unit, Paint.Align.LEFT), text, w * 0.4F, unit)
        val numY = pad + numPaint.textSize * 0.9F
        canvas.drawText(text, pad, numY, numPaint)
        val nowCap = if (pt) "agora" else "now"
        val ncPaint = textPaint(regular, p.sub, unit * 0.3F)
        canvas.drawText(nowCap, pad + numPaint.measureText(text) + unit * 0.15F, numY, ncPaint)

        val slope = if (zone == Zone.OBSOLETE) Float.NaN else recentSlope()
        if (slope.isNaN()) {
            val msg = if (pt) "Sem tendência suficiente para estimar" else "Not enough trend data"
            val mPaint = fit(textPaint(medium, p.sub, unit * 0.34F), msg, w - pad * 2F, unit * 0.34F)
            canvas.drawText(msg, pad, h - pad, mPaint)
            return
        }
        val now = ReceiveData.rawValue.toFloat()
        val pred = (now + slope * 20F).coerceIn(39F, 400F)
        val predColor = if (p.filled) p.ink else valueColor(Math.round(pred), c)

        // right block: "daqui a 20 min" + "≈ 321"
        val predTxt = "≈ " + fmtGlucose(pred)
        val pPaint = textPaint(heavy, predColor, unit * 0.62F, Paint.Align.RIGHT)
        val capTxt = if (pt) "daqui a 20 min" else "in 20 min"
        val cPaint = textPaint(regular, p.sub, unit * 0.3F, Paint.Align.RIGHT)
        canvas.drawText(capTxt, w - pad, numY - unit * 0.62F, cPaint)
        canvas.drawText(predTxt, w - pad, numY, pPaint)

        // trend bar from now to the estimate
        val slopeTxt = String.format(Locale.getDefault(), "%+.1f/min", if (ReceiveData.isMmol) GlucoDataUtils.mgToMmol(slope) else slope)
        val barY = numY + (h - pad - numY) * 0.38F
        val barH = max(6F * d, unit * 0.16F)
        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        val startColor = if (p.filled) (p.ink and 0x00FFFFFF) or 0x55000000 else valueColor(Math.round(now), c)
        bar.shader = android.graphics.LinearGradient(pad, 0F, w - pad, 0F, startColor, predColor, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(pad, barY - barH / 2F, w - pad, barY + barH / 2F), barH / 2F, barH / 2F, bar)
        drawArrow(canvas, w / 2F, barY - barH * 1.6F, barH * 1.6F, if (p.filled) p.ink else predColor)
        val sPaint = textPaint(regular, p.sub, unit * 0.28F, Paint.Align.CENTER)
        canvas.drawText(slopeTxt, w / 2F + barH * 3F, barY - barH * 1.1F, sPaint.apply { textAlign = Paint.Align.LEFT })

        val msg = forecastMessage(context, now, pred, slope)
        val mColor = if (p.filled) p.ink else if (pred < ReceiveData.targetMinRaw) c.red else if (pred > ReceiveData.targetMaxRaw) c.amber else p.sub
        val est = if (pt) "estimativa" else "estimate"
        val ePaint = textPaint(regular, p.sub, unit * 0.26F, Paint.Align.RIGHT)
        val mPaint = fit(textPaint(medium, mColor, unit * 0.34F), msg, w - pad * 3F - ePaint.measureText(est), unit * 0.34F)
        canvas.drawText(msg, pad, h - pad, mPaint)
        canvas.drawText(est, w - pad, h - pad, ePaint)
    }

    // ---------- 3 · the day in 24 bars ----------
    private fun drawDayLayout(context: Context, canvas: Canvas, w: Int, h: Int, d: Float, p: Palette, c: Colors) {
        val pt = isPt(context)
        val pad = min(h * 0.12F, 20F * d)
        val start = dayStart(0)
        val values = history.filter { it.timestamp >= start }
        val titleSize = min(h * 0.14F, 18F * d)
        val title = if (pt) "Hoje" else "Today"
        val tPaint = textPaint(heavy, p.ink, titleSize)
        canvas.drawText(title, pad, pad + titleSize, tPaint)
        if (values.isNotEmpty()) {
            val inRange = values.count { it.value >= ReceiveData.targetMinRaw && it.value <= ReceiveData.targetMaxRaw }
            val pct = Math.round(inRange * 100F / values.size)
            val avg = values.map { it.value }.average().toFloat()
            val info = pct.toString() + "% " + (if (pt) "no alvo · média " else "in range · avg ") + fmtGlucose(avg)
            val iPaint = fit(textPaint(medium, p.sub, titleSize * 0.8F, Paint.Align.RIGHT), info, w - pad * 3F - tPaint.measureText(title), titleSize * 0.8F)
            canvas.drawText(info, w - pad, pad + titleSize, iPaint)
        }
        val labelSize = max(8F * d, titleSize * 0.62F)
        val top = pad + titleSize * 1.6F
        val bottom = h - pad - labelSize * 1.4F
        val gap = max(1.5F * d, (w - pad * 2F) / 24F * 0.18F)
        val bw = ((w - pad * 2F) - gap * 23F) / 24F
        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        val nowHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        for (hour in 0..23) {
            val hs = start + hour * 3_600_000L
            val vals = values.filter { it.timestamp >= hs && it.timestamp < hs + 3_600_000L }
            val x = pad + hour * (bw + gap)
            if (vals.isEmpty()) {
                bar.color = c.track
                val stub = max(3F * d, (bottom - top) * 0.1F)
                canvas.drawRoundRect(RectF(x, bottom - stub, x + bw, bottom), bw * 0.3F, bw * 0.3F, bar)
                continue
            }
            val avg = vals.map { it.value }.average().toFloat()
            val frac = ((avg - 40F) / 260F).coerceIn(0.12F, 1F)
            bar.color = valueColor(Math.round(avg), c)
            if (hour == nowHour) bar.alpha = 170
            canvas.drawRoundRect(RectF(x, bottom - (bottom - top) * frac, x + bw, bottom), bw * 0.3F, bw * 0.3F, bar)
            bar.alpha = 255
        }
        val lPaint = textPaint(regular, p.sub, labelSize, Paint.Align.CENTER)
        listOf(0, 6, 12, 18, 23).forEach {
            val x = pad + it * (bw + gap) + bw / 2F
            canvas.drawText(String.format(Locale.getDefault(), "%02dh", it), x, h - pad, lPaint)
        }
    }

    // ---------- 4 · for the parents ----------
    private fun drawParentsLayout(context: Context, canvas: Canvas, w: Int, h: Int, d: Float, p: Palette, c: Colors, zone: Zone) {
        val pt = isPt(context)
        val pad = min(h * 0.14F, 20F * d)
        val name = GlucoDataService.patientName?.trim().orEmpty()
        val showRight = w >= 240F * d
        val avatar = min(h - pad * 2F, 64F * d)
        var x = pad
        if (h >= 70F * d) {
            val circle = Paint(Paint.ANTI_ALIAS_FLAG)
            circle.color = p.accent
            circle.alpha = if (p.filled) 60 else 45
            val acx = pad + avatar / 2F
            canvas.drawCircle(acx, h / 2F, avatar / 2F, circle)
            val initial = if (name.isNotEmpty()) name.substring(0, 1).uppercase(Locale.getDefault()) else "♥"
            val iPaint = textPaint(heavy, if (p.filled) p.ink else p.accent, avatar * 0.42F, Paint.Align.CENTER)
            canvas.drawText(initial, acx, h / 2F + iPaint.textSize * 0.36F, iPaint)
            x += avatar + pad * 0.8F
        }
        val rightW = if (showRight) w * 0.28F else 0F
        val avail = w - x - pad - rightW
        val nameSize = h * 0.13F
        val nPaint = fit(textPaint(medium, p.ink, nameSize), if (name.isNotEmpty()) name else (if (pt) "Glicose" else "Glucose"), avail, nameSize)
        canvas.drawText(if (name.isNotEmpty()) name else (if (pt) "Glicose" else "Glucose"), x, pad + nameSize, nPaint)
        val text = ReceiveData.getGlucoseAsString()
        val numPaint = fit(textPaint(heavy, p.ink, h * 0.36F), text, avail * 0.7F, h * 0.36F)
        if (zone == Zone.OBSOLETE) numPaint.isStrikeThruText = !ReceiveData.isObsoleteLong()
        val numY = h / 2F + numPaint.textSize * 0.38F
        canvas.drawText(text, x, numY, numPaint)
        val aSize = numPaint.textSize * 0.5F
        drawArrow(canvas, x + numPaint.measureText(text) + aSize * 0.9F, numY - numPaint.textSize * 0.34F, aSize, p.accent)
        val slope = if (zone == Zone.OBSOLETE) Float.NaN else recentSlope()
        val phrase = when (zone) {
            Zone.OK -> when {
                !slope.isNaN() && slope >= 1F -> if (pt) "a subir" else "rising"
                !slope.isNaN() && slope <= -1F -> if (pt) "a descer" else "falling"
                else -> if (pt) "tudo bem" else "all good"
            }
            else -> zoneLabel(context, zone).lowercase(Locale.getDefault())
        } + " · " + ReceiveData.getElapsedTimeMinuteAsString(context)
        val phPaint = fit(textPaint(regular, p.sub, h * 0.12F), phrase, avail, h * 0.12F)
        canvas.drawText(phrase, x, h - pad, phPaint)

        if (showRight) {
            val last = lowEpisodes(history).lastOrNull()
            val label = if (pt) "última baixa" else "last low"
            val value = if (last != null) whenText(context, last.start) else (if (pt) "nenhuma em 7 dias" else "none in 7 days")
            val lPaint = fit(textPaint(regular, p.sub, h * 0.11F, Paint.Align.RIGHT), label, rightW, h * 0.11F)
            val vPaint = fit(textPaint(heavy, p.ink, h * 0.14F, Paint.Align.RIGHT), value, rightW, h * 0.14F)
            canvas.drawText(label, w - pad, h / 2F - vPaint.textSize * 0.3F, lPaint)
            canvas.drawText(value, w - pad, h / 2F + vPaint.textSize * 1.0F, vPaint)
        }
    }

    // ---------- 5 · how the night went ----------
    private fun drawNightLayout(context: Context, canvas: Canvas, w: Int, h: Int, d: Float, p: Palette, c: Colors) {
        val pt = isPt(context)
        val pad = min(h * 0.12F, 20F * d)
        val now = System.currentTimeMillis()
        val start = dayStart(0)
        val nightMode = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) < 12
        val end = if (nightMode) min(now, start + 7 * 3_600_000L) else now
        val title = if (nightMode) (if (pt) "Noite 00h–07h" else "Night 00–07h") else (if (pt) "Hoje até agora" else "Today so far")
        val values = history.filter { it.timestamp in start..end }
        val titleSize = min(h * 0.15F, 18F * d)
        val tPaint = fit(textPaint(heavy, p.ink, titleSize), title, w * 0.6F, titleSize)
        canvas.drawText(title, pad, pad + titleSize, tPaint)
        if (values.isEmpty()) {
            val msg = if (pt) "Sem dados" else "No data"
            canvas.drawText(msg, pad, h - pad, textPaint(medium, p.sub, titleSize * 0.8F))
            return
        }
        val inRange = values.count { it.value >= ReceiveData.targetMinRaw && it.value <= ReceiveData.targetMaxRaw }
        val pct = Math.round(inRange * 100F / values.size).toString() + "%"
        val pctPaint = textPaint(heavy, c.green, titleSize * 1.25F, Paint.Align.RIGHT)
        canvas.drawText(pct, w - pad, pad + titleSize * 1.05F, pctPaint)

        val minV = values.minByOrNull { it.value }!!
        val maxV = values.maxByOrNull { it.value }!!
        val lows = lowEpisodes(values).size
        val top = pad + titleSize * 1.9F
        val tileH = h - pad - top
        if (tileH < 24F * d) return
        val gap = pad * 0.5F
        val tileW = (w - pad * 2F - gap * 2F) / 3F
        drawTile(canvas, RectF(pad, top, pad + tileW, top + tileH), if (pt) "mínimo " + hhmm(minV.timestamp) else "min " + hhmm(minV.timestamp),
            fmtGlucose(minV.value.toFloat()), valueColor(minV.value, c), p, c, d)
        drawTile(canvas, RectF(pad + tileW + gap, top, pad + tileW * 2F + gap, top + tileH), if (pt) "máximo " + hhmm(maxV.timestamp) else "max " + hhmm(maxV.timestamp),
            fmtGlucose(maxV.value.toFloat()), valueColor(maxV.value, c), p, c, d)
        drawTile(canvas, RectF(pad + (tileW + gap) * 2F, top, w - pad, top + tileH), if (pt) "baixas" else "lows",
            lows.toString(), if (lows > 0) c.red else p.ink, p, c, d)
    }

    // ---------- 6 · low counter ----------
    private fun drawLowsLayout(context: Context, canvas: Canvas, w: Int, h: Int, d: Float, p: Palette, c: Colors) {
        val pt = isPt(context)
        val pad = min(h * 0.14F, 20F * d)
        val episodes = lowEpisodes(history)
        var streak = 0
        for (day in 0 until HISTORY_DAYS) {
            val ds = dayStart(day)
            val de = ds + 86_400_000L
            if (episodes.any { it.start < de && it.end > ds }) break
            streak++
        }
        val oldest = history.minOfOrNull { it.timestamp } ?: System.currentTimeMillis()
        val full = streak >= HISTORY_DAYS || dayStart(streak) <= oldest
        val streakTxt = streak.toString() + (if (full && streak > 0) "+" else "")

        val leftW = min(w * 0.32F, h * 1.1F)
        val numPaint = fit(textPaint(heavy, if (streak > 0) c.green else p.ink, h * 0.42F, Paint.Align.CENTER), streakTxt, leftW * 0.9F, h * 0.42F)
        canvas.drawText(streakTxt, pad + leftW / 2F, h / 2F + numPaint.textSize * 0.2F, numPaint)
        val cap = if (pt) (if (streak == 1) "dia sem baixas" else "dias sem baixas") else (if (streak == 1) "day without lows" else "days without lows")
        val capPaint = fit(textPaint(regular, p.sub, h * 0.1F, Paint.Align.CENTER), cap, leftW, h * 0.1F)
        canvas.drawText(cap, pad + leftW / 2F, h / 2F + numPaint.textSize * 0.2F + capPaint.textSize * 1.6F, capPaint)

        val x = pad + leftW + pad * 0.6F
        val right = w - pad
        val todayLows = episodes.count { it.start >= dayStart(0) }
        val weekLows = episodes.size
        val weekMin = episodes.sumOf { (it.end - it.start) / 60_000L }
        val lowest = episodes.minByOrNull { it.min }
        val rows = listOf(
            Pair(if (pt) "Hoje" else "Today", todayLows.toString() + (if (pt) " baixas" else " lows")),
            Pair(if (pt) "7 dias" else "7 days", weekLows.toString() + (if (pt) " baixas · " else " lows · ") + weekMin + " min"),
            Pair(if (pt) "Mais baixa" else "Lowest", if (lowest != null) fmtGlucose(lowest.min.toFloat()) + " · " + whenText(context, lowest.minTime).substringBefore(" ") else "—")
        )
        val rowH = (h - pad * 2F) / 3F
        val size = min(rowH * 0.42F, 15F * d)
        rows.forEachIndexed { i, row ->
            val y = pad + rowH * i + rowH * 0.62F
            val lp = textPaint(regular, p.sub, size)
            canvas.drawText(row.first, x, y, lp)
            val vp = fit(textPaint(heavy, p.ink, size, Paint.Align.RIGHT), row.second, right - x - lp.measureText(row.first) - pad * 0.5F, size)
            canvas.drawText(row.second, right, y, vp)
        }
    }
}
