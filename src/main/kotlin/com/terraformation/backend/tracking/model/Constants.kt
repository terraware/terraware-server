package com.terraformation.backend.tracking.model

import java.math.BigDecimal

/** Number of digits after the decimal point to retain in area (hectares) calculations. */
const val HECTARES_SCALE = 1

/** The maximum size of the envelope (bounding box) of a site. */
val MAX_SITE_ENVELOPE_AREA_HA = BigDecimal(20000)

/** Monitoring plot width and height in meters. */
const val MONITORING_PLOT_SIZE: Double = 30.0

/** Monitoring plot width and height in meters. */
const val MONITORING_PLOT_SIZE_INT = MONITORING_PLOT_SIZE.toInt()
