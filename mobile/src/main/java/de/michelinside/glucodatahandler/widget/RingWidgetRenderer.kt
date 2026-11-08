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

    private class Colors(dark: Boolean) {
        val green = if (dark) 0xFF4ADE80.toInt() else 0xFF15803D.toInt()
        val amber = if (dark) 0xFFFBBF24.toInt() else 0xFFD97706.toInt()
        val red = if (dark) 0xFFF87171.toInt() else 0xFFB91C1C.toInt()
        val card = if (dark) 0xFF1E1D1C.toInt() else 0xFFFFFFFF.toInt()
        val ink = if (dark) 0xFFF5F5F4.toInt() else 0xFF1C1917.toInt()
        val sub = if (dark) 0xFFB5AFA9.toInt() else 0xFF57534E.toInt()
        val track = if (dark) 0xFF3A3836.toInt() else 0xFFECE7DF.toInt()
        val grid = if (dark) 0xFF3A3836.toInt() else 0xFFE7E5E4.toInt()
        val obsCard = if (dark) 0xFF2A2928.toInt() else 0xFFE7E5E4.toInt()
        val obsInk = if (dark) 0xFF9C9690.toInt() else 0xFF78716C.toInt()
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
            dbAccess.getGlucoseValues(now - TIR_HOURS * 3_600_000L)
        } catch (exc: Exception) {
            Log.e(LOG_ID, "loadHistory exception: " + exc.message.toString())
            emptyList()
        }
        cacheKey = ReceiveData.time
        cacheTime = now
    }

    private class Tir(val low: Float, val inRange: Float, val high: Float)

    private fun getTir(): Tir? {
        if (history.size < 12)
            return null
        val minT = ReceiveData.targetMinRaw
        val maxT = ReceiveData.targetMaxRaw
        var lo = 0
        var ok = 0
        var hi = 0
        history.forEach {
            when {
                it.value < minT -> lo++
                it.value > maxT -> hi++
                else -> ok++
            }
        }
        val total = (lo + ok + hi).toFloat()
        return Tir(lo / total, ok / total, hi / total)
    }

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

    fun render(context: Context, spec: Spec, widthDp: Int, heightDp: Int, transparency: Int): Bitmap {
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
            val c = Colors(isDark(context))
            val zone = getZone()
            val p = getPalette(zone, c)
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
        val cap = if (isPt(context)) "alvo 24h" else "range 24h"
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
            val cap = (if (pt) "No alvo 24h " else "In range 24h ") + Math.round(tir.inRange * 100F) + "%"
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
}
