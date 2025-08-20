package com.terraformation.backend.seedbank.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.util.equalsIgnoreScale
import java.math.BigDecimal
import java.math.RoundingMode

@JsonInclude(JsonInclude.Include.NON_NULL)
class Geolocation(
    val latitude: BigDecimal,
    val longitude: BigDecimal,
    val accuracy: BigDecimal? = null,
) {
  /** Tests property values for numeric equality, disregarding differences in decimal scale. */
  override fun equals(other: Any?): Boolean {
    return other is Geolocation &&
        latitude.equalsIgnoreScale(other.latitude) &&
        longitude.equalsIgnoreScale(other.longitude) &&
        accuracy.equalsIgnoreScale(other.accuracy)
  }

  override fun hashCode(): Int {
    return latitude.setScale(10, RoundingMode.DOWN).hashCode() xor
        longitude.setScale(10, RoundingMode.DOWN).hashCode() xor
        (accuracy?.setScale(10, RoundingMode.DOWN)?.hashCode() ?: 13)
  }

  override fun toString() =
      "Geolocation(latitude=${latitude.toPlainString()}, longitude=${longitude.toPlainString()}, " +
          "accuracy=${accuracy?.toPlainString()})"
}
