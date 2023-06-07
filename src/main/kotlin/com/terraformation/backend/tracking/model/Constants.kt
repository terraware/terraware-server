package com.terraformation.backend.tracking.model

/** Monitoring plot width and height in meters. */
const val MONITORING_PLOT_SIZE: Double = 25.0

/** Number of square meters in a hectare. */
const val SQUARE_METERS_PER_HECTARE: Double = 10000.0

/** Number of square meters in a monitoring plot. */
const val SQUARE_METERS_PER_MONITORING_PLOT: Double = MONITORING_PLOT_SIZE * MONITORING_PLOT_SIZE

/** Number of monitoring plots in a hectare. */
const val MONITORING_PLOTS_PER_HECTARE: Double =
    SQUARE_METERS_PER_HECTARE / SQUARE_METERS_PER_MONITORING_PLOT
