package de.michelinside.glucodatahandler

import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import de.michelinside.glucodatahandler.common.ReceiveData
import de.michelinside.glucodatahandler.common.database.dbAccess
import de.michelinside.glucodatahandler.common.utils.GlucoDataUtils
import de.michelinside.glucodatahandler.common.utils.Log
import de.michelinside.glucodatahandler.common.utils.Utils
import java.util.Locale

/**
 * GDH Ring: 20 minute forecast complication.
 * Same calculation as the phone "Previsão 20 min" ring widget:
 * linear regression over the last 16 minutes of values, fallback to the current rate.
 * Only a straight continuation of the trend: it does not know insulin or carbs.
 */
object Forecast {
    private const val LOG_ID = "GDH.Forecast"
    const val MINUTES = 20F
    const val MAX = 280F

    /** mg/dl per minute, NaN if no usable data */
    fun slope(): Float {
        return try {
            val now = System.currentTimeMillis()
            val pts = dbAccess.getGlucoseValues(now - 16 * 60_000L).filter { it.timestamp >= now - 16 * 60_000L }
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
            ReceiveData.rate
        } catch (exc: Exception) {
            Log.e(LOG_ID, "slope exception: " + exc.message.toString())
            ReceiveData.rate
        }
    }

    /** predicted value in mg/dl, NaN if not available */
    fun predicted(): Float {
        // at the sensor limits (>= 400 / <= 40) the trend is flat by definition: no estimate
        if (ReceiveData.isObsoleteShort() || ReceiveData.rawValue <= 40 || ReceiveData.rawValue >= 400)
            return Float.NaN
        val s = slope()
        if (s.isNaN())
            return Float.NaN
        return (ReceiveData.rawValue + s * MINUTES).coerceIn(39F, 400F)
    }

    fun arrow(slope: Float): String = when {
        slope.isNaN() -> ""
        slope >= 2F -> "↑"
        slope >= 1F -> "↗"
        slope <= -2F -> "↓"
        slope <= -1F -> "↘"
        else -> "→"
    }

    fun format(mg: Float): String =
        if (ReceiveData.isMmol) String.format(Locale.getDefault(), "%.1f", GlucoDataUtils.mgToMmol(mg)) else Math.round(mg).toString()

    fun text(): String {
        val pred = predicted()
        if (pred.isNaN())
            return "---"
        return "≈ " + format(pred) + " " + arrow(slope())
    }
}

class ForecastComplication : BgValueComplicationService() {
    override fun getText(): PlainComplicationText = plainText(Forecast.text())
    override fun getTitle(): PlainComplicationText = plainText("20′")

    override fun getRangeValueComplicationData(id: Int): ComplicationData {
        val pred = Forecast.predicted()
        val value = if (pred.isNaN()) 0F else pred
        val color = if (pred.isNaN()) ReceiveData.getGlucoseColor() else ReceiveData.getValueColor(Math.round(pred))
        return RangedValueComplicationData.Builder(
            value = Utils.rangeValue(value, 0F, Forecast.MAX),
            min = 0F,
            max = Forecast.MAX,
            contentDescription = descriptionText()
        )
            .setTitle(getTitle())
            .setText(getText())
            .setTapAction(getTapAction(id))
            .setColorRamp(ColorRamp(intArrayOf(color), false))
            .build()
    }

    override fun getDescription(): String = "Previsão 20 min"
}
