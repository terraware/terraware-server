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
      val bcCode1 =
          insertBotanicalCountry(boundary = rectangle(500_000, 500_000, 3_000_000, 1_000_000))
      // 500km square in South America
      val bcCode2 = insertBotanicalCountry(boundary = rectangle(500_000, 500_000, -8_000_000, 0))

      // New mappings should replace existing ones.
      insertCountryBotanicalCountry()

      service.on(BotanicalCountriesImportedEvent())

      assertTableEquals(
          listOf(
              CountryBotanicalCountriesRecord("SD", bcCode1),
              CountryBotanicalCountriesRecord("SS", bcCode1),
              CountryBotanicalCountriesRecord("BR", bcCode2),
              CountryBotanicalCountriesRecord("CO", bcCode2),
              CountryBotanicalCountriesRecord("VE", bcCode2),
          )
      )
    }
  }

  @Nested
  inner class UpdateWcvpBotanicalCountryMapping {
    @Test
    fun `updates botanical country codes based on level 3 codes`() {
      insertBotanicalCountry(level3Code = "AAA")
      insertBotanicalCountry(level3Code = "BBB")
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
                  botanicalCountryCode = "AAA",
                  level3Code = "AAA",
                  taxonId = taxonId1,
              ),
              WcvpDistributionsRecord(
                  botanicalCountryCode = "AAA",
                  level3Code = "AAA",
                  taxonId = taxonId2,
              ),
              WcvpDistributionsRecord(
                  botanicalCountryCode = "BBB",
                  level3Code = "BBB",
                  taxonId = taxonId2,
              ),
              WcvpDistributionsRecord(
                  level3Code = "XXX",
                  taxonId = taxonId3,
              ),
          )
      )
    }
  }
}
