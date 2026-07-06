package com.terraformation.backend.species.db

import com.terraformation.backend.db.default_schema.ExternalDatasetType
import com.terraformation.backend.db.default_schema.SpeciesNativity
import com.terraformation.backend.db.default_schema.tables.references.EXTERNAL_DATASET_IMPORTS
import com.terraformation.backend.db.default_schema.tables.references.GRIIS_RESOURCES
import com.terraformation.backend.db.default_schema.tables.references.GRIIS_TAXA
import com.terraformation.backend.db.default_schema.tables.references.WCVP_DISTRIBUTIONS
import com.terraformation.backend.db.default_schema.tables.references.WCVP_TAXA
import com.terraformation.backend.species.model.SourcedSpeciesNativity
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class SpeciesNativityCalculator(
    private val dslContext: DSLContext,
) {
  fun calculateNativities(
      botanicalCountryCode: String,
      countryCode: String,
      scientificNames: Collection<String>,
  ): Map<String, SourcedSpeciesNativity> {
    // Species nativities are defined in descending order of precedence, so if we sort by them,
    // we get the highest-precedence nativity for a given species first.
    val griisRecords =
        dslContext
            .select(
                GRIIS_TAXA.SCIENTIFIC_NAME,
                GRIIS_TAXA.SPECIES_NATIVITY_ID,
                EXTERNAL_DATASET_IMPORTS.LAST_PUBLICATION_DATE,
            )
            .from(GRIIS_TAXA)
            .join(GRIIS_RESOURCES)
            .on(GRIIS_TAXA.GRIIS_RESOURCE_ID.eq(GRIIS_RESOURCES.ID))
            .join(EXTERNAL_DATASET_IMPORTS)
            .on(EXTERNAL_DATASET_IMPORTS.EXTERNAL_DATASET_TYPE_ID.eq(ExternalDatasetType.GRIIS))
            .where(GRIIS_TAXA.SCIENTIFIC_NAME.`in`(scientificNames))
            .and(GRIIS_RESOURCES.COUNTRY_CODE.eq(countryCode))
            .orderBy(GRIIS_TAXA.SPECIES_NATIVITY_ID)
            .fetchGroups(GRIIS_TAXA.SCIENTIFIC_NAME)
            .mapValues { (_, records) -> records.first() }
    val wcvpRecords =
        dslContext
            .select(
                WCVP_TAXA.SCIENTIFIC_NAME,
                WCVP_DISTRIBUTIONS.SPECIES_NATIVITY_ID,
                EXTERNAL_DATASET_IMPORTS.LAST_PUBLICATION_DATE,
            )
            .from(WCVP_DISTRIBUTIONS)
            .join(WCVP_TAXA)
            .on(WCVP_DISTRIBUTIONS.TAXON_ID.eq(WCVP_TAXA.TAXON_ID))
            .join(EXTERNAL_DATASET_IMPORTS)
            .on(EXTERNAL_DATASET_IMPORTS.EXTERNAL_DATASET_TYPE_ID.eq(ExternalDatasetType.WCVP))
            .where(WCVP_DISTRIBUTIONS.BOTANICAL_COUNTRY_CODE.eq(botanicalCountryCode))
            .and(WCVP_TAXA.SCIENTIFIC_NAME.`in`(scientificNames))
            .orderBy(WCVP_DISTRIBUTIONS.SPECIES_NATIVITY_ID)
            .fetchGroups(WCVP_TAXA.SCIENTIFIC_NAME)
            .mapValues { (_, records) -> records.first() }

    return scientificNames.associateWith { scientificName ->
      val griisRecord = griisRecords[scientificName]
      val wcvpRecord = wcvpRecords[scientificName]
      val griisNativity = griisRecord?.get(GRIIS_TAXA.SPECIES_NATIVITY_ID)
      val wcvpNativity = wcvpRecord?.get(WCVP_DISTRIBUTIONS.SPECIES_NATIVITY_ID)

      when {
        griisNativity == SpeciesNativity.Invasive || griisNativity == SpeciesNativity.Introduced ->
            SourcedSpeciesNativity(
                griisNativity,
                ExternalDatasetType.GRIIS,
                griisRecord[EXTERNAL_DATASET_IMPORTS.LAST_PUBLICATION_DATE],
            )

        wcvpNativity == SpeciesNativity.Native || wcvpNativity == SpeciesNativity.Introduced ->
            SourcedSpeciesNativity(
                wcvpNativity,
                ExternalDatasetType.WCVP,
                wcvpRecord[EXTERNAL_DATASET_IMPORTS.LAST_PUBLICATION_DATE],
            )

        else -> SourcedSpeciesNativity(SpeciesNativity.Unknown, null, null)
      }
    }
  }
}
