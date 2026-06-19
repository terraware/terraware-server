package com.terraformation.backend.gis

import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.tables.references.BOTANICAL_COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.ECOREGIONS
import com.terraformation.backend.db.default_schema.tables.references.ECOREGION_BOTANICAL_COUNTRIES
import com.terraformation.backend.gis.event.BotanicalCountriesImportedEvent
import com.terraformation.backend.gis.event.EcoregionsImportedEvent
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.util.intersectsFast
import jakarta.inject.Named
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.event.EventListener

@Named
class RegionMetadataService(private val dslContext: DSLContext) {
  private val log = perClassLogger()

  @EventListener
  fun on(event: BotanicalCountriesImportedEvent) {
    updateEcoregionMapping()
  }

  @EventListener
  fun on(event: EcoregionsImportedEvent) {
    updateEcoregionMapping()
  }

  private fun updateEcoregionMapping() {
    log.info("Calculating ecoregion to botanical country mapping")

    dslContext.transaction { _ ->
      val botanicalCountries =
          dslContext
              .select(BOTANICAL_COUNTRIES.ID, BOTANICAL_COUNTRIES.BOUNDARY.asNonNullable())
              .from(BOTANICAL_COUNTRIES)
              .fetch()

      val ecoregions =
          dslContext
              .select(ECOREGIONS.ID, ECOREGIONS.BOUNDARY.asNonNullable())
              .from(ECOREGIONS)
              .fetch()

      val ecoregionBotanicalCountriesRows =
          runBlocking(Dispatchers.Default) {
            ecoregions
                .map { (ecoregionId, ecoregionBoundary) ->
                  async {
                    botanicalCountries.mapNotNull { (botanicalCountryId, botanicalBoundary) ->
                      if (ecoregionBoundary.intersectsFast(botanicalBoundary)) {
                        DSL.row(ecoregionId, botanicalCountryId)
                      } else {
                        null
                      }
                    }
                  }
                }
                .awaitAll()
                .flatten()
          }

      dslContext.deleteFrom(ECOREGION_BOTANICAL_COUNTRIES).execute()

      with(ECOREGION_BOTANICAL_COUNTRIES) {
        dslContext
            .insertInto(ECOREGION_BOTANICAL_COUNTRIES, ECOREGION_ID, BOTANICAL_COUNTRY_ID)
            .valuesOfRows(ecoregionBotanicalCountriesRows)
            .execute()
      }
    }
  }
}
