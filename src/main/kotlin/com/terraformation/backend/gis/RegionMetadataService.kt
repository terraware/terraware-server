package com.terraformation.backend.gis

import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.tables.references.BOTANICAL_COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.COUNTRY_BOTANICAL_COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.WCVP_DISTRIBUTIONS
import com.terraformation.backend.gis.event.BotanicalCountriesImportedEvent
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.species.event.WcvpImportedEvent
import jakarta.inject.Named
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.event.EventListener

@Named
class RegionMetadataService(
    private val countryDetector: CountryDetector,
    private val dslContext: DSLContext,
) {
  private val log = perClassLogger()

  @EventListener
  fun on(event: BotanicalCountriesImportedEvent) {
    updateCountryBotanicalCountryMapping()
    updateWcvpBotanicalCountryMapping()
  }

  @EventListener
  fun on(event: WcvpImportedEvent) {
    updateWcvpBotanicalCountryMapping()
  }

  private fun updateCountryBotanicalCountryMapping() {
    log.info("Linking countries to botanical countries")

    val validCountryCodes =
        dslContext.select(COUNTRIES.CODE).from(COUNTRIES).fetchSet(COUNTRIES.CODE.asNonNullable())

    val botanicalCountriesRecords = dslContext.selectFrom(BOTANICAL_COUNTRIES).fetch()

    val countryBotanicalCountriesRows =
        runBlocking(Dispatchers.Default) {
          botanicalCountriesRecords
              .map { record ->
                async {
                  log.debug("Calculating for ${record.name}")

                  val botanicalCountryId = record.id!!
                  val countries = countryDetector.getCountries(record.boundary!!)

                  countries
                      .filter { it in validCountryCodes }
                      .map { countryCode -> DSL.row(countryCode, botanicalCountryId) }
                }
              }
              .awaitAll()
              .flatten()
        }

    dslContext.transaction { _ ->
      dslContext.deleteFrom(COUNTRY_BOTANICAL_COUNTRIES).execute()

      dslContext
          .insertInto(
              COUNTRY_BOTANICAL_COUNTRIES,
              COUNTRY_BOTANICAL_COUNTRIES.COUNTRY_CODE,
              COUNTRY_BOTANICAL_COUNTRIES.BOTANICAL_COUNTRY_ID,
          )
          .valuesOfRows(countryBotanicalCountriesRows)
          .execute()
    }
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
