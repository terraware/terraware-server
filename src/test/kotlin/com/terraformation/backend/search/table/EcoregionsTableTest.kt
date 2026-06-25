package com.terraformation.backend.search.table

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.use
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EcoregionsTableTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val tables = SearchTables(clock)

  private val searchService: SearchService by lazy { SearchService(dslContext) }

  @Test
  fun `returns localized ecoregion names`() {
    insertEcoregion(id = 103, ecoName = "Namib Desert")
    insertEcoregionCountry(countryCode = "NA")

    val prefix = SearchFieldPrefix(tables.ecoregions)
    val fields =
        listOf(
            prefix.resolve("id"),
            prefix.resolve("name"),
            prefix.resolve("ecoregionCountries_country_name"),
        )

    val result =
        Locales.SPANISH.use {
          searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()))
        }

    assertEquals(
        listOf(
            mapOf(
                "id" to "103",
                "name" to "Desierto de Namib",
                "ecoregionCountries_country_name" to "Namibia",
            )
        ),
        result.results,
    )
  }

  @Test
  fun `can search for localized ecoregion names`() {
    insertEcoregion(id = 103, ecoName = "Namib Desert")
    insertEcoregionCountry(countryCode = "NA")

    val prefix = SearchFieldPrefix(tables.ecoregions)
    val fields =
        listOf(
            prefix.resolve("id"),
            prefix.resolve("ecoregionCountries_country_name"),
        )

    val result =
        Locales.SPANISH.use {
          searchService.search(
              prefix,
              fields,
              mapOf(
                  prefix to
                      FieldNode(
                          prefix.resolve("name"),
                          listOf("Desierto de Namib"),
                      )
              ),
          )
        }

    assertEquals(
        listOf(
            mapOf(
                "id" to "103",
                "ecoregionCountries_country_name" to "Namibia",
            )
        ),
        result.results,
    )
  }

  @Test
  fun `can find ecoregions for a botanical country`() {
    insertBotanicalCountry(level3Code = "NAM", name = "Namibia")
    insertEcoregion(id = 103, ecoName = "Namib Desert")
    insertEcoregionBotanicalCountry()
    insertEcoregion(id = 104, ecoName = "Namibian savanna woodlands")
    insertEcoregionBotanicalCountry()
    insertBotanicalCountry(level3Code = "RUW", name = "Northwest European Russia")
    insertEcoregion(id = 200, ecoName = "Flinders-Lofty montane woodlands")
    insertEcoregionBotanicalCountry()

    val prefix = SearchFieldPrefix(tables.ecoregions)
    val fields =
        listOf(
            prefix.resolve("id"),
            prefix.resolve("name"),
        )

    val result =
        searchService.search(
            prefix,
            fields,
            mapOf(
                prefix to
                    FieldNode(
                        prefix.resolve("ecoregionBotanicalCountries.botanicalCountry.level3Code"),
                        listOf("NAM"),
                    )
            ),
        )

    assertEquals(
        listOf(
            mapOf(
                "id" to "103",
                "name" to "Namib Desert",
            ),
            mapOf(
                "id" to "104",
                "name" to "Namibian savanna woodlands",
            ),
        ),
        result.results,
    )
  }

  @Test
  fun `can find botanical countries for an ecoregion`() {
    insertBotanicalCountry(level3Code = "NAM", name = "Namibia")
    insertEcoregion(id = 103, ecoName = "Namib Desert")
    insertEcoregionBotanicalCountry()
    insertBotanicalCountry(level3Code = "RUW", name = "Northwest European Russia")
    insertEcoregion(id = 200, ecoName = "Flinders-Lofty montane woodlands")
    insertEcoregionBotanicalCountry()

    val prefix = SearchFieldPrefix(tables.botanicalCountries)
    val fields =
        listOf(
            prefix.resolve("level3Code"),
            prefix.resolve("name"),
        )

    val result =
        Locales.SPANISH.use {
          searchService.search(
              prefix,
              fields,
              mapOf(
                  prefix to
                      FieldNode(
                          prefix.resolve("ecoregionBotanicalCountries.ecoregion.id"),
                          listOf("200"),
                      )
              ),
          )
        }

    assertEquals(
        listOf(
            mapOf(
                "level3Code" to "RUW",
                "name" to "Rusia Europea Noroccidental",
            ),
        ),
        result.results,
    )
  }
}
