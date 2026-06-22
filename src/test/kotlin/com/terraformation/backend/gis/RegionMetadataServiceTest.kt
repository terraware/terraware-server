package com.terraformation.backend.gis

import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.tables.records.EcoregionBotanicalCountriesRecord
import com.terraformation.backend.db.default_schema.tables.records.WcvpDistributionsRecord
import com.terraformation.backend.db.default_schema.tables.references.ECOREGION_BOTANICAL_COUNTRIES
import com.terraformation.backend.gis.event.BotanicalCountriesImportedEvent
import com.terraformation.backend.gis.event.EcoregionsImportedEvent
import com.terraformation.backend.rectangle
import com.terraformation.backend.species.event.WcvpImportedEvent
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RegionMetadataServiceTest : DatabaseTest() {
  private val service by lazy { RegionMetadataService(dslContext) }

  @Test
  fun `listens for events`() {
    assertIsEventListener<BotanicalCountriesImportedEvent>(service)
    assertIsEventListener<EcoregionsImportedEvent>(service)
    assertIsEventListener<WcvpImportedEvent>(service)
  }

  @Nested
  inner class UpdateEcoregionMapping {
    @Test
    fun `does nothing if only the botanical countries have been imported`() {
      insertBotanicalCountry()

      service.on(BotanicalCountriesImportedEvent())

      assertTableEmpty(ECOREGION_BOTANICAL_COUNTRIES)
    }

    @Test
    fun `does nothing if only the ecoregions have been imported`() {
      insertEcoregion()

      service.on(EcoregionsImportedEvent())

      assertTableEmpty(ECOREGION_BOTANICAL_COUNTRIES)
    }

    @Test
    fun `links ecoregions and botanical countries based on boundary intersections`() {
      val countryId1 =
          insertBotanicalCountry(boundary = rectangle(x = 0, y = 0, width = 10, height = 10))
      val countryId2 =
          insertBotanicalCountry(boundary = rectangle(x = 10, y = 0, width = 10, height = 10))
      insertBotanicalCountry(boundary = rectangle(x = 0, y = 100, width = 10, height = 10))

      service.on(BotanicalCountriesImportedEvent())

      val ecoregionId1 = insertEcoregion(boundary = rectangle(x = 0, y = 0, width = 5, height = 1))
      val ecoregionId2 = insertEcoregion(boundary = rectangle(x = 5, y = 0, width = 10, height = 1))
      insertEcoregion(boundary = rectangle(x = 0, y = 200, width = 10, height = 10))

      service.on(EcoregionsImportedEvent())

      assertTableEquals(
          listOf(
              EcoregionBotanicalCountriesRecord(ecoregionId1, countryId1),
              EcoregionBotanicalCountriesRecord(ecoregionId2, countryId1),
              EcoregionBotanicalCountriesRecord(ecoregionId2, countryId2),
          )
      )
    }
  }

  @Nested
  inner class UpdateWcvpBotanicalCountryMapping {
    @Test
    fun `updates botanical country IDs based on level 3 codes`() {
      val countryId1 = insertBotanicalCountry(level3Code = "AAA")
      val countryId2 = insertBotanicalCountry(level3Code = "BBB")
      insertBotanicalCountry(level3Code = "CCC")

      val taxonId1 = insertWcvpTaxon()
      insertWcvpDistribution(level3Code = "AAA")
      val taxonId2 = insertWcvpTaxon()
      insertWcvpDistribution(level3Code = "AAA")
      insertWcvpDistribution(level3Code = "BBB")
      val taxonId3 = insertWcvpTaxon()
      insertWcvpDistribution(level3Code = "XXX")

      service.on(WcvpImportedEvent())

      assertTableEquals(
          listOf(
              WcvpDistributionsRecord(
                  taxonId = taxonId1,
                  level3Code = "AAA",
                  botanicalCountryId = countryId1,
              ),
              WcvpDistributionsRecord(
                  taxonId = taxonId2,
                  level3Code = "AAA",
                  botanicalCountryId = countryId1,
              ),
              WcvpDistributionsRecord(
                  taxonId = taxonId2,
                  level3Code = "BBB",
                  botanicalCountryId = countryId2,
              ),
              WcvpDistributionsRecord(
                  taxonId = taxonId3,
                  level3Code = "XXX",
              ),
          )
      )
    }
  }
}
