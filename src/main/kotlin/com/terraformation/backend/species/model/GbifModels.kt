package com.terraformation.backend.species.model

import com.terraformation.backend.db.GbifTaxonId

data class GbifTaxonModel(
    val taxonId: GbifTaxonId,
    val scientificName: String,
    val familyName: String,
    val commonNames: List<String>,
    val threatStatus: String?,
) {
  val isEndangered: Boolean?
    get() =
        when (threatStatus) {
          "critically endangered", "endangered", "extinct", "extinct in the wild" -> true
          "near threatened", "least concern", "vulnerable" -> false
          else -> null
        }
}
