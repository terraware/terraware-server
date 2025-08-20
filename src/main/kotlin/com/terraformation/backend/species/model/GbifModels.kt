package com.terraformation.backend.species.model

import com.terraformation.backend.db.default_schema.ConservationCategory
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
      record[GBIF_VERNACULAR_NAMES.LANGUAGE],
  )
}

data class GbifTaxonModel(
    val taxonId: GbifTaxonId,
    val scientificName: String,
    val familyName: String,
    val vernacularNames: List<GbifVernacularNameModel>,
    /** IUCN Red List conservation category name in lower case. */
    val threatStatus: String?,
) {
  val conservationCategory: ConservationCategory?
    get() = threatStatus?.let { conservationCategories[it] }

  companion object {
    private val conservationCategories =
        mapOf(
            "least concern" to ConservationCategory.LeastConcern,
            "near threatened" to ConservationCategory.NearThreatened,
            "vulnerable" to ConservationCategory.Vulnerable,
            "endangered" to ConservationCategory.Endangered,
            "critically endangered" to ConservationCategory.CriticallyEndangered,
            "extinct in the wild" to ConservationCategory.ExtinctInTheWild,
            "extinct" to ConservationCategory.Extinct,
            "data deficient" to ConservationCategory.DataDeficient,
            "not evaluated" to ConservationCategory.NotEvaluated,
        )
  }
}
