package com.terraformation.backend.species.db

import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.GbifTaxonId
import com.terraformation.backend.db.default_schema.SpeciesProblemField
import com.terraformation.backend.db.default_schema.SpeciesProblemType
import com.terraformation.backend.db.default_schema.tables.pojos.GbifNamesRow
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesProblemsRow
import com.terraformation.backend.species.model.GbifTaxonModel
import com.terraformation.backend.species.model.GbifVernacularNameModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class GbifStoreTest : DatabaseTest() {
  private val store: GbifStore by lazy { GbifStore(dslContext) }

  @Nested
  inner class FindNamesByWordPrefixes {
    @Test
    fun `matches prefixes but not non-prefix substrings`() {
      val taxonId1 = insertGbifTaxon("Scientific name")
      insertGbifTaxon("Unscientific name")

      val expected = listOf(namesRow(taxonId1, "Scientific name"))

      val actual = store.findNamesByWordPrefixes(listOf("sci"))
      assertNamesEqual(expected, actual)
    }

    @Test
    fun `only returns names that match all the prefixes`() {
      insertGbifTaxon("Real name")
      insertGbifTaxon("Fake identity")
      val taxonId3 = insertGbifTaxon("Some fake kind of name")

      val expected = listOf(namesRow(taxonId3, "Some fake kind of name"))

      val actual = store.findNamesByWordPrefixes(listOf("fake", "name"))
      assertNamesEqual(expected, actual)
    }

    @Test
    fun `does case-insensitive prefix matching of scientific names`() {
      val taxonId1 = insertGbifTaxon("Scientific name")
      insertGbifTaxon("Unscientific name", listOf("Scientific name but it is common" to null))
      insertGbifTaxon("Scientific balderdash")

      val expected = listOf(namesRow(taxonId1, "Scientific name"))

      val actual = store.findNamesByWordPrefixes(listOf("sc", "N"))
      assertNamesEqual(expected, actual)
    }

    @Test
    fun `ignores non-alphabetic characters in prefixes`() {
      val taxonId = insertGbifTaxon("Matching result")

      val expected = listOf(namesRow(taxonId, "Matching result"))

      val actual = store.findNamesByWordPrefixes(listOf("!?m%%[]\$_a...", "...", "(r)E"))
      assertNamesEqual(expected, actual)
    }

    @Test
    fun `can search common names`() {
      val taxonId1 = insertGbifTaxon("Scientific name", listOf("Common name" to null))
      insertGbifTaxon("Somewhat common name", listOf("No match" to null))

      val expected = listOf(namesRow(taxonId1, "Common name", isScientific = false))

      val actual = store.findNamesByWordPrefixes(listOf("co", "na"), scientific = false)
      assertNamesEqual(expected, actual)
    }

    @Test
    fun `is sensitive to order of prefixes`() {
      val taxonId1 = insertGbifTaxon("Scientific name")
      insertGbifTaxon("Name scientific")

      val expected = listOf(namesRow(taxonId1, "Scientific name"))

      val actual = store.findNamesByWordPrefixes(listOf("sci", "na"))
      assertNamesEqual(expected, actual)
    }

    @Test
    fun `throws exception if prefix list has no valid prefixes`() {
      assertThrows<IllegalArgumentException> {
        store.findNamesByWordPrefixes(listOf("...", "", "     "))
      }
    }

    @Test
    fun `returns results in alphabetical order with first-word matches at the top`() {
      val taxonId1 = insertGbifTaxon("Species c")
      val taxonId2 = insertGbifTaxon("Species a")
      val taxonId3 = insertGbifTaxon("Species b")
      val taxonId4 = insertGbifTaxon("Another species")

      val expected =
          listOf(
              namesRow(taxonId2, "Species a"),
              namesRow(taxonId3, "Species b"),
              namesRow(taxonId1, "Species c"),
              namesRow(taxonId4, "Another species"),
          )

      val actual = store.findNamesByWordPrefixes(listOf("species"))
      assertNamesEqual(expected, actual)
    }

    @Test
    fun `ignores diacritics`() {
      val taxonId1 = insertGbifTaxon("Spécies a")
      val taxonId2 = insertGbifTaxon("Species b")

      val expected = listOf(namesRow(taxonId1, "Spécies a"), namesRow(taxonId2, "Species b"))

      val actual = store.findNamesByWordPrefixes(listOf("species"))
      assertNamesEqual(expected, actual)
    }

    @Test
    fun `returns partial list of results if number of matches exceeds the maximum`() {
      val taxonId1 = insertGbifTaxon("Species a")
      val taxonId2 = insertGbifTaxon("Species b")
      insertGbifTaxon("Species c")

      val expected =
          listOf(
              namesRow(taxonId1, "Species a"),
              namesRow(taxonId2, "Species b"),
          )

      val actual = store.findNamesByWordPrefixes(listOf("species"), maxResults = 2)
      assertNamesEqual(expected, actual)
    }

    @Test
    fun `does not return duplicate scientific names`() {
      val taxonId1 = insertGbifTaxon("Species a", fullScientificName = "Species a (Someone 1985)")
      insertGbifTaxon("Species a", fullScientificName = "Species a (Someone else 1986)")

      val expected = listOf(namesRow(taxonId1, "Species a"))

      val actual = store.findNamesByWordPrefixes(listOf("species"))
      assertNamesEqual(expected, actual)
    }
  }

  @Nested
  inner class FetchOneByScientificName {
    @Test
    fun `returns null if scientific name does not exist`() {
      assertNull(store.fetchOneByScientificName("nonexistent"))
    }

    @Test
    fun `returns vernacular names in multiple languages if no language specified`() {
      val taxonId =
          insertGbifTaxon(
              "Scientific name",
              listOf("Common en" to "en", "Common xx" to "xx", "Common unknown" to null),
              "Family",
              "endangered",
          )

      val expected =
          GbifTaxonModel(
              taxonId,
              "Scientific name",
              "Family",
              listOf(
                  GbifVernacularNameModel("Common en", "en"),
                  GbifVernacularNameModel("Common unknown", null),
                  GbifVernacularNameModel("Common xx", "xx"),
              ),
              "endangered",
          )

      val actual = store.fetchOneByScientificName("Scientific name")
      assertEquals(expected, actual)
    }

    @Test
    fun `excludes vernacular names in languages other than the specified one`() {
      val taxonId =
          insertGbifTaxon(
              "Scientific name",
              listOf(
                  "Common es" to "es",
                  "Common xx" to "xx",
                  "Common unknown" to null,
              ),
              "Family",
          )

      val expected =
          GbifTaxonModel(
              taxonId,
              "Scientific name",
              "Family",
              listOf(
                  GbifVernacularNameModel("Common es", "es"),
                  GbifVernacularNameModel("Common unknown", null),
              ),
              null,
          )

      val actual = store.fetchOneByScientificName("Scientific name", "es")
      assertEquals(expected, actual)
    }

    @Test
    fun `falls back to English if no vernacular names exist in requested language`() {
      val taxonId =
          insertGbifTaxon(
              "Scientific name",
              listOf(
                  "Common en" to "en",
                  "Common xx" to "xx",
                  "Common unknown" to null,
              ),
              "Family",
          )

      val expected =
          GbifTaxonModel(
              taxonId,
              "Scientific name",
              "Family",
              listOf(
                  GbifVernacularNameModel("Common en", "en"),
                  GbifVernacularNameModel("Common unknown", null),
              ),
              null,
          )

      val actual = store.fetchOneByScientificName("Scientific name", "fr")
      assertEquals(expected, actual)
    }

    @Test
    fun `returns data for accepted name if multiple taxa have same name`() {
      insertGbifTaxon(
          "Scientific name",
          fullScientificName = "Scientific name (1)",
          taxonomicStatus = "doubtful",
      )
      val taxonId2 =
          insertGbifTaxon(
              "Scientific name",
              fullScientificName = "Scientific name (2)",
              taxonomicStatus = "accepted",
          )
      insertGbifTaxon(
          "Scientific name",
          fullScientificName = "Scientific name (3)",
          taxonomicStatus = "synonym",
      )

      val expected = GbifTaxonModel(taxonId2, "Scientific name", "Scientific", emptyList(), null)

      val actual = store.fetchOneByScientificName("Scientific name", "en")
      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class CheckScientificName {
    @Test
    fun `returns null if name is already correct`() {
      val scientificName = "Scientific name"
      insertGbifTaxon(scientificName)

      val actual = store.checkScientificName(scientificName)
      assertNull(actual)
    }

    @Test
    fun `returns not-found problem if there are no close matches`() {
      insertGbifTaxon("Scientific name")

      val expected =
          SpeciesProblemsRow(
              fieldId = SpeciesProblemField.ScientificName,
              typeId = SpeciesProblemType.NameNotFound,
          )

      val actual = store.checkScientificName("Nowhere close")
      assertEquals(expected, actual)
    }

    @Test
    fun `returns suggested name if it is a close match`() {
      val scientificName = "Scientific name"
      insertGbifTaxon(scientificName)

      val expected =
          SpeciesProblemsRow(
              fieldId = SpeciesProblemField.ScientificName,
              typeId = SpeciesProblemType.NameMisspelled,
              suggestedValue = scientificName,
          )

      val actual = store.checkScientificName("Scietific nam")
      assertEquals(expected, actual)
    }

    @Test
    fun `returns accepted name if input is a synonym`() {
      val newName = "New name"
      val oldName = "Older synonym"
      val newNameTaxonId = insertGbifTaxon(newName)
      insertGbifTaxon(oldName, taxonomicStatus = "synonym", acceptedNameUsageId = newNameTaxonId)

      val expected =
          SpeciesProblemsRow(
              fieldId = SpeciesProblemField.ScientificName,
              typeId = SpeciesProblemType.NameIsSynonym,
              suggestedValue = newName,
          )

      val actual = store.checkScientificName(oldName)
      assertEquals(expected, actual)
    }

    @Test
    fun `returns accepted name if input is a misspelled synonym`() {
      val newName = "New name"
      val newNameTaxonId = insertGbifTaxon(newName)
      insertGbifTaxon(
          "Correct synonym",
          taxonomicStatus = "synonym",
          acceptedNameUsageId = newNameTaxonId,
      )

      val expected =
          SpeciesProblemsRow(
              fieldId = SpeciesProblemField.ScientificName,
              typeId = SpeciesProblemType.NameIsSynonym,
              suggestedValue = newName,
          )

      val actual = store.checkScientificName("Corect synonm")
      assertEquals(expected, actual)
    }

    @Test
    fun `returns null if there are multiple taxa whose scientific names we render identically`() {
      insertGbifTaxon("Scientific name", fullScientificName = "Scientific name (author1)")
      insertGbifTaxon(
          "Scientific name",
          fullScientificName = "Scientific name (author2)",
          taxonomicStatus = "doubtful",
      )

      assertNull(store.checkScientificName("Scientific name"))
    }
  }

  private fun namesRow(
      taxonId: GbifTaxonId,
      name: String,
      language: String? = null,
      isScientific: Boolean = true,
  ): GbifNamesRow {
    return GbifNamesRow(
        isScientific = isScientific,
        language = language,
        name = name,
        taxonId = taxonId,
    )
  }

  private fun assertNamesEqual(expected: List<GbifNamesRow>, actual: List<GbifNamesRow>) {
    assertEquals(expected, actual.map { it.copy(id = null) })
  }
}
