package com.terraformation.backend.gis.geoserver

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class FeatureProperty(
    val localType: String,
    val maxOccurs: Int,
    val minOccurs: Int,
    val name: String,
    val nillable: Boolean,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FeatureTypeDescription(
    val properties: List<FeatureProperty>,
    val targetPrefix: String? = null,
    val typeName: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FeatureTypeDescriptions(
    val featureTypes: List<FeatureTypeDescription>,
    val targetPrefix: String,
)
