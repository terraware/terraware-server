package com.terraformation.backend.search

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SearchServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables = SearchTables(clock)

  @BeforeEach
  fun setUp() {
    val organizationId = insertOrganization()
    insertOrganizationUser(currentUser().userId, inserted.organizationId)
    every { user.canReadOrganization(inserted.organizationId) } returns true
    every { user.organizationRoles } returns mapOf(organizationId to Role.Contributor)
  }

  @Nested
  inner class TextFieldSearch {
    @Test
    fun `Exact search returns every result that contains the string`() {
      val speciesKoa = insertSpecies(scientificName = "Koa")
      val speciesKoaia = insertSpecies(scientificName = "Koaia")
      val speciesKoaAcacia = insertSpecies(scientificName = "Koa Acacia")
      insertSpecies(scientificName = "Monstera Deliciosa")

      val prefix = SearchFieldPrefix(searchTables.species)

      val fields = listOf("id", "scientificName").map { prefix.resolve(it) }

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "$speciesKoa",
                      "scientificName" to "Koa",
                  ),
                  mapOf(
                      "id" to "$speciesKoaia",
                      "scientificName" to "Koaia",
                  ),
                  mapOf(
                      "id" to "$speciesKoaAcacia",
                      "scientificName" to "Koa Acacia",
                  ),
              )
          )

      val conditions =
          FieldNode(prefix.resolve("scientificName"), listOf("Koa"), SearchFilterType.Exact)

      assertJsonEquals(expected, searchService.search(prefix, fields, mapOf(prefix to conditions)))
    }

    @Test
    fun `Fuzzy search returns every result that is similar the string`() {
      val speciesKoa = insertSpecies(scientificName = "Koa")
      val speciesKoaia = insertSpecies(scientificName = "Koaia")
      val speciesKoaAcacia = insertSpecies(scientificName = "Koa Acacia")
      insertSpecies(scientificName = "Monstera Deliciosa")

      val prefix = SearchFieldPrefix(searchTables.species)

      val fields = listOf("id", "scientificName").map { prefix.resolve(it) }

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "$speciesKoa",
                      "scientificName" to "Koa",
                  ),
                  mapOf(
                      "id" to "$speciesKoaia",
                      "scientificName" to "Koaia",
                  ),
                  mapOf(
                      "id" to "$speciesKoaAcacia",
                      "scientificName" to "Koa Acacia",
                  ),
              )
          )

      val conditions =
          FieldNode(prefix.resolve("scientificName"), listOf("Koaa"), SearchFilterType.Fuzzy)

      assertJsonEquals(expected, searchService.search(prefix, fields, mapOf(prefix to conditions)))
    }

    @Test
    fun `Phrase match search with empty phrase should return empty list`() {
      insertSpecies(scientificName = "Koa")
      insertSpecies(scientificName = "Koaia")
      insertSpecies(scientificName = "Koa Acacia")
      insertSpecies(scientificName = "Monstera Deliciosa")

      val prefix = SearchFieldPrefix(searchTables.species)

      val fields = listOf("id", "scientificName").map { prefix.resolve(it) }

      val conditions =
          FieldNode(prefix.resolve("scientificName"), listOf(""), SearchFilterType.PhraseMatch)

      assertJsonEquals(
          SearchResults(emptyList()),
          searchService.search(prefix, fields, mapOf(prefix to conditions)),
      )
    }

    @Test
    fun `Phrase match search returns every result that has the exact phrase in text`() {
      val speciesKoa = insertSpecies(scientificName = "Koa")
      insertSpecies(scientificName = "Koaia")
      val speciesKoaAcacia = insertSpecies(scientificName = "Koa Acacia")
      insertSpecies(scientificName = "Monstera Deliciosa")

      val prefix = SearchFieldPrefix(searchTables.species)

      val fields = listOf("id", "scientificName").map { prefix.resolve(it) }

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "$speciesKoa",
                      "scientificName" to "Koa",
                  ),
                  mapOf(
                      "id" to "$speciesKoaAcacia",
                      "scientificName" to "Koa Acacia",
                  ),
              )
          )

      val conditions =
          FieldNode(prefix.resolve("scientificName"), listOf("Koa"), SearchFilterType.PhraseMatch)

      assertJsonEquals(expected, searchService.search(prefix, fields, mapOf(prefix to conditions)))
    }

    @Test
    fun `Phrase match search returns results containing multi-word phrase`() {
      insertSpecies(scientificName = "Apple")
      insertSpecies(scientificName = "Green Apple")
      insertSpecies(scientificName = "Red Apple")
      insertSpecies(scientificName = "Pineapple")
      val appleTree = insertSpecies(scientificName = "Apple Tree")
      val redAppleTree = insertSpecies(scientificName = "Red Apple Tree")
      insertSpecies(scientificName = "Pineapple Tree")
      insertSpecies(scientificName = "Pineapple Tree Farm")
      val brightRedAppleTree = insertSpecies(scientificName = "Bright Red Apple Tree")
      insertSpecies(scientificName = "Apple Treelet")
      insertSpecies(scientificName = "Red Pineapple")
      val brightRedAppleTreeFarm = insertSpecies(scientificName = "Bright Red Apple Tree Farm")

      val prefix = SearchFieldPrefix(searchTables.species)

      val fields = listOf("id", "scientificName").map { prefix.resolve(it) }

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "$appleTree",
                      "scientificName" to "Apple Tree",
                  ),
                  mapOf(
                      "id" to "$redAppleTree",
                      "scientificName" to "Red Apple Tree",
                  ),
                  mapOf(
                      "id" to "$brightRedAppleTree",
                      "scientificName" to "Bright Red Apple Tree",
                  ),
                  mapOf(
                      "id" to "$brightRedAppleTreeFarm",
                      "scientificName" to "Bright Red Apple Tree Farm",
                  ),
              )
          )

      val conditions =
          FieldNode(
              prefix.resolve("scientificName"),
              listOf("Apple Tree"),
              SearchFilterType.PhraseMatch,
          )

      assertJsonEquals(expected, searchService.search(prefix, fields, mapOf(prefix to conditions)))
    }
  }

  @Nested
  inner class BuildQuery {
    @Test
    fun `buildQuery throws exception`() {
      val prefix = SearchFieldPrefix(searchTables.species)
      val fields =
          listOf("nurseryProjects.project.id", "organization.id").map { prefix.resolve(it) }

      assertThrows<IllegalArgumentException> {
        searchService.buildQuery(prefix, fields, emptyMap(), excludeMultisets = true)
      }
    }
  }
}
