package com.terraformation.backend.search

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.search.table.SearchTables
import org.jooq.conf.ParamType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class SearchServiceVisibilityTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val tables = SearchTables(clock)
  private val prefix = SearchFieldPrefix(tables.strata)

  private lateinit var searchService: SearchService

  @BeforeEach
  fun setUp() {
    searchService = SearchService(dslContext)

    insertOrganization()
    insertOrganizationUser()
    insertPlantingSite(name = "Site")
    insertStratum(name = "Visible")

    insertOrganization()
    insertPlantingSite(name = "Site")
    insertStratum(name = "Invisible")
  }

  @Test
  fun `filters out rows whose parent table is not visible`() {
    assertEquals(
        SearchResults(listOf(mapOf("name" to "Visible"))),
        searchService.search(prefix, listOf(prefix.resolve("name")), emptyMap()),
    )
  }

  @Test
  fun `filters out rows whose parent table is not visible when filtering on parent table field`() {
    assertEquals(
        SearchResults(listOf(mapOf("name" to "Visible"))),
        searchService.search(
            prefix,
            listOf(prefix.resolve("name")),
            mapOf(prefix to FieldNode(prefix.resolve("plantingSite.name"), listOf("Site"))),
        ),
    )
  }

  @Test
  fun `reuses parent table from filter criteria for visibility check`() {
    val sql =
        searchService
            .buildQuery(
                prefix,
                listOf(prefix.resolve("name")),
                mapOf(prefix to FieldNode(prefix.resolve("plantingSite.name"), listOf("Site"))),
            )
            .toSelectQuery()
            .getSQL(ParamType.INLINED)

    assertEquals(1, Regex("planting_site_summaries").findAll(sql).count(), sql)
  }
}
