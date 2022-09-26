package com.terraformation.backend.species.model

import com.terraformation.backend.db.default_schema.GbifTaxonId
import com.terraformation.backend.db.default_schema.tables.references.GBIF_VERNACULAR_NAMES
import org.jooq.Record

data class GbifVernacularNameModel(
    val name: String,
    val language: String?,
) {
  constructor(
      record: Record
  ) : this(
      record[GBIF_VERNACULAR_NAMES.VERNACULAR_NAME]
          ?: throw IllegalArgumentException("Name must be non-null"),
      record[GBIF_VERNACULAR_NAMES.LANGUAGE])
}

data class GbifTaxonModel(
    val taxonId: GbifTaxonId,
    val scientificName: String,
    val familyName: String,
    val vernacularNames: List<GbifVernacularNameModel>,
    val threatStatus: String?,
) {
  /**
   * Whether or not the species should be considered endangered by our app. This is derived from the
   * "threat status" value in the GBIF distributions dataset; we consider certain threat statuses to
   * be endangered, certain statuses to be non-endangered, and unrecognized statuses to be
   * inconclusive.
   */
  val isEndangered: Boolean?
    get() = threatStatus?.let { endangeredThreatStatuses[it] }

  companion object {
    private val endangeredThreatStatuses =
        mapOf(
            // IUCN Red List categories
            "least concern" to false,
            "near threatened" to false,
            "vulnerable" to true,
            "endangered" to true,
            "critically endangered" to true,
            "extinct in the wild" to true,
            "extinct" to true,
        )
  }
}
