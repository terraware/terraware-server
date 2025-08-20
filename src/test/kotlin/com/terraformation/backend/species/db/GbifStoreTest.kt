package com.terraformation.backend.species.db

import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.GbifTaxonId
import com.terraformation.backend.db.default_schema.SpeciesProblemField
import com.terraformation.backend.db.default_schema.SpeciesProblemType
import com.terraformation.backend.db.default_schema.tables.pojos.GbifNamesRow
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesProblemsRow
import com.terraformation.backend.db.default_schema.tables.references.GBIF_DISTRIBUTIONS
import com.terraformation.backend.db.default_schema.tables.references.GBIF_NAMES
import com.terraformation.backend.db.default_schema.tables.references.GBIF_NAME_WORDS
import com.terraformation.backend.db.default_schema.tables.references.GBIF_TAXA
import com.terraformation.backend.db.default_schema.tables.references.GBIF_VERNACULAR_NAMES
import com.terraformation.backend.species.model.GbifTaxonModel
import com.terraformation.backend.species.model.GbifVernacularNameModel
import com.terraformation.backend.util.removeDiacritics
import java.util.concurrent.atomic.AtomicLong
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
      val taxonId1 = insertTaxon("Scientific name")
      insertTaxon("Unscientific name")

      val expected = listOf(namesRow(taxonId1, "Scientific name"))

      val actual = store.findNamesByWordPrefixes(listOf("sci"))
      assertNamesEqual(expected, actual)
    }

    @Test
    fun `only returns names that match all the prefixes`() {
      insertTaxon("Real name")
      insertTaxon("Fake identity")
      val taxonId3 = insertTaxon("Some fake kind of name")

      val expected = listOf(namesRow(taxonId3, "Some fake kind of name"))

      val actual = store.findNamesByWordPrefixes(listOf("fake", "name"))
      assertNamesEqual(expected, actual)
    }

    @Test
    fun `does case-insensitive prefix matching of scientific names`() {
      val taxonId1 = insertTaxon("Scientific name")
      insertTaxon("Unscientific name", listOf("Scientific name but it is common" to null))
      insertTaxon("Scientific balderdash")

      val expected = listOf(namesRow(taxonId1, "Scientific name"))

      val actual = store.findNamesByWordPrefixes(listOf("sc", "N"))
      assertNamesEqual(expected, actual)
    }

    @Test
    fun `ignores non-alphabetic characters in prefixes`() {
      val taxonId = insertTaxon("Matching result")

      val expected = listOf(namesRow(taxonId, "Matching result"))

      val actual = store.findNamesByWordPrefixes(listOf("!?m%%[]\$_a...", "...", "(r)E"))
      assertNamesEqual(expected, actual)
    }

    @Test
    fun `can search common names`() {
      val taxonId1 = insertTaxon("Scientific name", listOf("Common name" to null))
      insertTaxon("Somewhat common name", listOf("No match" to null))

      val expected = listOf(namesRow(taxonId1, "Common name", isScientific = false))

      val actual = store.findNamesByWordPrefixes(listOf("co", "na"), scientific = false)
      assertNamesEqual(expected, actual)
    }

    @Test
    fun `is sensitive to order of prefixes`() {
      val taxonId1 = insertTaxon("Scientific name")
      insertTaxon("Name scientific")

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
      val taxonId1 = insertTaxon("Species c")
      val taxonId2 = insertTaxon("Species a")
      val taxonId3 = insertTaxon("Species b")
      val taxonId4 = insertTaxon("Another species")

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
      val taxonId1 = insertTaxon("Spécies a")
      val taxonId2 = insertTaxon("Species b")

      val expected = listOf(namesRow(taxonId1, "Spécies a"), namesRow(taxonId2, "Species b"))

      val actual = store.findNamesByWordPrefixes(listOf("species"))
      assertNamesEqual(expected, actual)
    }

    @Test
    fun `returns partial list of results if number of matches exceeds the maximum`() {
      val taxonId1 = insertTaxon("Species a")
      val taxonId2 = insertTaxon("Species b")
      insertTaxon("Species c")

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
      val taxonId1 = insertTaxon("Species a", fullScientificName = "Species a (Someone 1985)")
      insertTaxon("Species a", fullScientificName = "Species a (Someone else 1986)")

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
          insertTaxon(
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
          insertTaxon(
              "Scientific name",
              listOf("Common en" to "en", "Common xx" to "xx", "Common unknown" to null),
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

      val actual = store.fetchOneByScientificName("Scientific name", "en")
      assertEquals(expected, actual)
    }

    @Test
    fun `returns data for accepted name if multiple taxa have same name`() {
      insertTaxon(
          "Scientific name",
          fullScientificName = "Scientific name (1)",
          taxonomicStatus = "doubtful",
      )
      val taxonId2 =
          insertTaxon(
              "Scientific name",
              fullScientificName = "Scientific name (2)",
              taxonomicStatus = "accepted",
          )
      insertTaxon(
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
      insertTaxon(scientificName)

      val actual = store.checkScientificName(scientificName)
      assertNull(actual)
    }

    @Test
    fun `returns not-found problem if there are no close matches`() {
      insertTaxon("Scientific name")

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
      insertTaxon(scientificName)

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
      val newNameTaxonId = insertTaxon(newName)
      insertTaxon(oldName, taxonomicStatus = "synonym", acceptedNameUsageId = newNameTaxonId)

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
      val newNameTaxonId = insertTaxon(newName)
      insertTaxon(
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
      insertTaxon("Scientific name", fullScientificName = "Scientific name (author1)")
      insertTaxon(
          "Scientific name",
          fullScientificName = "Scientific name (author2)",
          taxonomicStatus = "doubtful",
      )

      assertNull(store.checkScientificName("Scientific name"))
    }
  }

  private fun insertTaxon(
      scientificName: String,
      commonNames: Collection<Pair<String, String?>> = emptyList(),
      familyName: String = scientificName.substringBefore(' '),
      threatStatus: String? = null,
      fullScientificName: String = scientificName,
      acceptedNameUsageId: GbifTaxonId? = null,
      taxonomicStatus: String = GbifStore.TAXONOMIC_STATUS_ACCEPTED,
  ): GbifTaxonId {
    val taxonId = GbifTaxonId(nextTaxonId.getAndIncrement())

    dslContext
        .insertInto(GBIF_TAXA)
        .set(GBIF_TAXA.TAXON_ID, taxonId)
        .set(GBIF_TAXA.SCIENTIFIC_NAME, fullScientificName)
        .set(GBIF_TAXA.FAMILY, familyName)
        .set(GBIF_TAXA.TAXON_RANK, "species")
        .set(GBIF_TAXA.TAXONOMIC_STATUS, taxonomicStatus)
        .set(GBIF_TAXA.ACCEPTED_NAME_USAGE_ID, acceptedNameUsageId)
        .execute()

    insertName(taxonId, scientificName, true)

    commonNames.forEach { (name, language) ->
      dslContext
          .insertInto(GBIF_VERNACULAR_NAMES)
          .set(GBIF_VERNACULAR_NAMES.TAXON_ID, taxonId)
          .set(GBIF_VERNACULAR_NAMES.VERNACULAR_NAME, name)
          .set(GBIF_VERNACULAR_NAMES.LANGUAGE, language)
          .execute()

      insertName(taxonId, name, false)
    }

    if (threatStatus != null) {
      dslContext
          .insertInto(GBIF_DISTRIBUTIONS)
          .set(GBIF_DISTRIBUTIONS.TAXON_ID, taxonId)
          .set(GBIF_DISTRIBUTIONS.THREAT_STATUS, threatStatus)
          .execute()
    }

    return taxonId
  }

  private fun insertName(taxonId: GbifTaxonId, name: String, isScientific: Boolean) {
    val nameId =
        dslContext
            .insertInto(GBIF_NAMES)
            .set(GBIF_NAMES.TAXON_ID, taxonId)
            .set(GBIF_NAMES.NAME, name)
            .set(GBIF_NAMES.IS_SCIENTIFIC, isScientific)
            .returning(GBIF_NAMES.ID)
            .fetchOne(GBIF_NAMES.ID)!!

    name.split(' ').forEach { word ->
      dslContext
          .insertInto(GBIF_NAME_WORDS)
          .set(GBIF_NAME_WORDS.GBIF_NAME_ID, nameId)
          .set(GBIF_NAME_WORDS.WORD, word.removeDiacritics().lowercase())
          .execute()
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

  companion object {
    /**
     * Taxon IDs are defined in the GBIF dataset, not allocated locally by the database, but they
     * are required to be unique in our data model. Use taxon IDs that are unique across threads to
     * avoid database deadlocks if multiple of these tests are running concurrently and try to
     * insert the same taxon ID at the same time.
     *
     * This starts at a high number to avoid colliding with the taxon IDs in [GbifImporterTest].
     */
    val nextTaxonId = AtomicLong(1000000)
  }
}
