package com.terraformation.backend.gis.geoserver

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement

data class FeatureType(
    val name: String,
    val title: String = name,
)

data class FeatureTypeList(
    @JacksonXmlProperty(localName = "FeatureType") //
    val featureTypes: List<FeatureType>,
)

data class Operation(@JacksonXmlProperty(isAttribute = true) val name: String)

data class OperationsMetadata(
    @JacksonXmlProperty(localName = "Operation") //
    val operations: List<Operation>,
)

@JacksonXmlRootElement(localName = "WFS_Capabilities")
data class WfsCapabilities(
    @JacksonXmlProperty(localName = "FeatureTypeList") //
    val featureTypeList: FeatureTypeList,
    @JacksonXmlProperty(localName = "OperationsMetadata")
    val operationsMetadata: OperationsMetadata,
) {
  val featureTypes
    get() = featureTypeList.featureTypes

  val operations
    get() = operationsMetadata.operations
}
