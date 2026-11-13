package de.michelinside.glucodatahandler.widget

import de.michelinside.glucodatahandler.R

class RingMiniWidget : GlucoseBaseWidget(WidgetType.RING_MINI, true, true, true, false) {
    override fun getLayout(): Int = R.layout.ring_widget
    override fun getRingKind(): RingWidgetRenderer.Kind = RingWidgetRenderer.Kind.MINI
}

class RingForecastWidget : GlucoseBaseWidget(WidgetType.RING_FORECAST, true, true, true, false) {
    override fun getLayout(): Int = R.layout.ring_widget
    override fun getRingKind(): RingWidgetRenderer.Kind = RingWidgetRenderer.Kind.FORECAST
}

class RingDayWidget : GlucoseBaseWidget(WidgetType.RING_DAY, true, true, true, false) {
    override fun getLayout(): Int = R.layout.ring_widget
    override fun getRingKind(): RingWidgetRenderer.Kind = RingWidgetRenderer.Kind.DAY
}

class RingParentsWidget : GlucoseBaseWidget(WidgetType.RING_PARENTS, true, true, true, false) {
    override fun getLayout(): Int = R.layout.ring_widget
    override fun getRingKind(): RingWidgetRenderer.Kind = RingWidgetRenderer.Kind.PARENTS
}

class RingNightWidget : GlucoseBaseWidget(WidgetType.RING_NIGHT, true, true, true, false) {
    override fun getLayout(): Int = R.layout.ring_widget
    override fun getRingKind(): RingWidgetRenderer.Kind = RingWidgetRenderer.Kind.NIGHT
}

class RingLowsWidget : GlucoseBaseWidget(WidgetType.RING_LOWS, true, true, true, false) {
    override fun getLayout(): Int = R.layout.ring_widget
    override fun getRingKind(): RingWidgetRenderer.Kind = RingWidgetRenderer.Kind.LOWS
}

