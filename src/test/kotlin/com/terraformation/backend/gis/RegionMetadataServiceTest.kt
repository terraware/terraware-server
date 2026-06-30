package com.terraformation.backend.gis

import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.tables.records.CountryBotanicalCountriesRecord
import com.terraformation.backend.db.default_schema.tables.records.WcvpDistributionsRecord
import com.terraformation.backend.gis.event.BotanicalCountriesImportedEvent
import com.terraformation.backend.rectangle
import com.terraformation.backend.species.event.WcvpImportedEvent
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RegionMetadataServiceTest : DatabaseTest() {
  private val service by lazy { RegionMetadataService(CountryDetector(), dslContext) }

  @Test
  fun `listens for events`() {
    assertIsEventListener<BotanicalCountriesImportedEvent>(service)
    assertIsEventListener<WcvpImportedEvent>(service)
  }

  @Nested
  inner class UpdateCountryBotanicalCountryMapping {
    @Test
    fun `updates mappings based on boundary intersections`() {
      // 500km square in eastern Africa
      val botanicalId1 =
          insertBotanicalCountry(boundary = rectangle(500_000, 500_000, 3_000_000, 1_000_000))
      // 500km square in South America
      val botanicalId2 =
          insertBotanicalCountry(boundary = rectangle(500_000, 500_000, -8_000_000, 0))

      // New mappings should replace existing ones.
      insertCountryBotanicalCountry()

      service.on(BotanicalCountriesImportedEvent())

      assertTableEquals(
          listOf(
              CountryBotanicalCountriesRecord("SD", botanicalId1),
              CountryBotanicalCountriesRecord("SS", botanicalId1),
              CountryBotanicalCountriesRecord("BR", botanicalId2),
              CountryBotanicalCountriesRecord("CO", botanicalId2),
              CountryBotanicalCountriesRecord("VE", botanicalId2),
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
