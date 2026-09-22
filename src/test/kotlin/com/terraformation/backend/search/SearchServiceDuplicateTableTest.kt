package com.terraformation.backend.search

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests searches that refer to the same table more than once. Draft planting sites have both a
 * `createdBy` and a `modifiedBy` sublist and both of them point at the users table, so a query that
 * asks for fields from both has to join with the users table twice under different aliases.
 */
internal class SearchServiceDuplicateTableTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val tables = SearchTables(clock)
  private val prefix = SearchFieldPrefix(tables.draftPlantingSites)

  private lateinit var searchService: SearchService

  @BeforeEach
  fun setUp() {
    searchService = SearchService(dslContext)

    every { user.canReadOrganization(any()) } returns true
    every { user.organizationRoles } returns mapOf(insertOrganization() to Role.Manager)

    val creator = insertUser(firstName = "Ada")
    insertOrganizationUser(creator)
    val modifier = insertUser(firstName = "Grace")
    insertOrganizationUser(modifier)

    insertDraftPlantingSite(createdBy = creator, modifiedBy = modifier, name = "Draft")
  }

  @Test
  fun `can select fields from nested sublists that refer to the same table`() {
    val fields =
        listOf("name", "createdBy.firstName", "modifiedBy.firstName").map { prefix.resolve(it) }

    assertEquals(
        SearchResults(
            listOf(
                mapOf(
                    "name" to "Draft",
                    "createdBy" to mapOf("firstName" to "Ada"),
                    "modifiedBy" to mapOf("firstName" to "Grace"),
                )
            )
        ),
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
    )
  }

  @Test
  fun `can select fields from flattened sublists that refer to the same table`() {
    val fields =
        listOf("name", "createdBy_firstName", "modifiedBy_firstName").map { prefix.resolve(it) }

    assertEquals(
        SearchResults(
            listOf(
                mapOf(
                    "name" to "Draft",
                    "createdBy_firstName" to "Ada",
                    "modifiedBy_firstName" to "Grace",
                )
            )
        ),
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
    )
  }

  @Test
  fun `filters sublists that refer to the same table independently of each other`() {
    val fields = listOf(prefix.resolve("name"))

    assertEquals(
        SearchResults(listOf(mapOf("name" to "Draft"))),
        searchService.search(
            prefix,
            fields,
            mapOf(prefix to FieldNode(prefix.resolve("modifiedBy.firstName"), listOf("Grace"))),
        ),
        "Filtering on the value from one sublist",
    )

    assertEquals(
        SearchResults(emptyList()),
        searchService.search(
            prefix,
            fields,
            mapOf(prefix to FieldNode(prefix.resolve("modifiedBy.firstName"), listOf("Ada"))),
        ),
        "Filtering on the value from the other sublist",
    )
  }
}
