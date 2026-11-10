package com.terraformation.backend.tracking.model

import java.math.BigDecimal

/** Number of digits after the decimal point to retain in area (hectares) calculations. */
const val HECTARES_SCALE = 3

/** The maximum size of the envelope (bounding box) of a site. */
val MAX_SITE_ENVELOPE_AREA_HA = BigDecimal(20000)

/** Monitoring plot width and height in meters. */
const val MONITORING_PLOT_SIZE: Double = 30.0

/** Monitoring plot width and height in meters. */
const val MONITORING_PLOT_SIZE_INT = MONITORING_PLOT_SIZE.toInt()

/** Minimum edge-to-edge gaps to try, in order, when placing new permanent monitoring plots. */
val PERMANENT_PLOT_SPACING_METERS = listOf(200.0, 100.0, 0.0)
