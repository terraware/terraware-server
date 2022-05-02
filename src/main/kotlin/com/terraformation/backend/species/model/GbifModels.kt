package com.terraformation.backend.species.model

import com.terraformation.backend.db.GbifTaxonId
import com.terraformation.backend.db.tables.references.GBIF_DISTRIBUTIONS
import com.terraformation.backend.db.tables.references.GBIF_NAMES
import com.terraformation.backend.db.tables.references.GBIF_TAXA
import org.jooq.Field
import org.jooq.Record
import org.jooq.Record1
import org.jooq.Result

data class GbifTaxonModel(
    val taxonId: GbifTaxonId,
    val scientificName: String,
    val familyName: String,
    val commonNames: List<String>,
    val threatStatus: String?,
) {
  constructor(
      record: Record,
      commonNamesMultiset: Field<Result<Record1<String?>>>
  ) : this(
      taxonId = record[GBIF_TAXA.TAXON_ID]
              ?: throw IllegalArgumentException("Taxon ID must be non-null"),
      scientificName = record[GBIF_NAMES.NAME]
              ?: throw IllegalArgumentException("Scientific name must be non-null"),
      familyName = record[GBIF_TAXA.FAMILY]
              ?: throw IllegalArgumentException("Family name must be non-null"),
      commonNames = record[commonNamesMultiset]?.mapNotNull { it.value1() } ?: emptyList(),
      threatStatus = record[GBIF_DISTRIBUTIONS.THREAT_STATUS],
  )

  val isEndangered: Boolean?
    get() =
        when (threatStatus) {
          "critically endangered", "endangered", "extinct", "extinct in the wild" -> true
          "near threatened", "least concern", "vulnerable" -> false
          else -> null
        }
}
