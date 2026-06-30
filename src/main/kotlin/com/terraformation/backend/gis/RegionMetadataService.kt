package com.terraformation.backend.gis

import com.terraformation.backend.db.default_schema.tables.references.BOTANICAL_COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.WCVP_DISTRIBUTIONS
import com.terraformation.backend.gis.event.BotanicalCountriesImportedEvent
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.species.event.WcvpImportedEvent
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.event.EventListener

@Named
class RegionMetadataService(private val dslContext: DSLContext) {
  private val log = perClassLogger()

  @EventListener
  fun on(event: BotanicalCountriesImportedEvent) {
    updateWcvpBotanicalCountryMapping()
  }

  @EventListener
  fun on(event: WcvpImportedEvent) {
    updateWcvpBotanicalCountryMapping()
  }

  private fun updateWcvpBotanicalCountryMapping() {
    log.info("Linking botanical countries to WCVP distributions")

    dslContext.transaction { _ ->
      dslContext
          .update(WCVP_DISTRIBUTIONS)
          .set(
              WCVP_DISTRIBUTIONS.BOTANICAL_COUNTRY_ID,
              DSL.select(BOTANICAL_COUNTRIES.ID)
                  .from(BOTANICAL_COUNTRIES)
                  .where(BOTANICAL_COUNTRIES.LEVEL3_CODE.eq(WCVP_DISTRIBUTIONS.LEVEL3_CODE)),
          )
          .execute()

      val botanicalCountriesPopulated = dslContext.fetchExists(BOTANICAL_COUNTRIES)
      if (botanicalCountriesPopulated) {
        val unknownLevel3Codes =
            dslContext
                .selectDistinct(WCVP_DISTRIBUTIONS.LEVEL3_CODE)
                .from(WCVP_DISTRIBUTIONS)
                .where(WCVP_DISTRIBUTIONS.LEVEL3_CODE.isNotNull)
                .and(WCVP_DISTRIBUTIONS.BOTANICAL_COUNTRY_ID.isNull)
                .orderBy(WCVP_DISTRIBUTIONS.LEVEL3_CODE)
                .fetch(WCVP_DISTRIBUTIONS.LEVEL3_CODE)

        if (unknownLevel3Codes.isNotEmpty()) {
          log.warn(
              "WCVP distributions had level 3 region codes that were not in botanical countries " +
                  "list: $unknownLevel3Codes"
          )
        }
      }
    }
  }
}
