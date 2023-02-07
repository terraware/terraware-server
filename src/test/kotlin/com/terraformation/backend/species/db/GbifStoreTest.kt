package com.terraformation.backend.species.db

import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ScientificNameNotFoundException
import com.terraformation.backend.db.default_schema.GbifNameId
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class GbifStoreTest : DatabaseTest() {
  override val tablesToResetSequences = listOf(GBIF_NAMES)

  private val store: GbifStore by lazy { GbifStore(dslContext) }

  @Nested
  inner class FindNamesByWordPrefixes {
    @Test
    fun `matches prefixes but not non-prefix substrings`() {
      insertTaxon(1, "Scientific name")
      insertTaxon(2, "Unscientific name")

      val expected = listOf(namesRow(1, 1, "Scientific name"))

      val actual = store.findNamesByWordPrefixes(listOf("sci"))
      assertEquals(expected, actual)
    }

    @Test
    fun `only returns names that match all the prefixes`() {
      insertTaxon(1, "Real name")
      insertTaxon(2, "Fake identity")
      insertTaxon(3, "Some fake kind of name")

      val expected = listOf(namesRow(3, 3, "Some fake kind of name"))

      val actual = store.findNamesByWordPrefixes(listOf("fake", "name"))
      assertEquals(expected, actual)
    }

    @Test
    fun `does case-insensitive prefix matching of scientific names`() {
      insertTaxon(1, "Scientific name")
      insertTaxon(2, "Unscientific name", listOf("Scientific name but it is common" to null))
      insertTaxon(3, "Scientific balderdash")

      val expected = listOf(namesRow(1, 1, "Scientific name"))

      val actual = store.findNamesByWordPrefixes(listOf("sc", "N"))
      assertEquals(expected, actual)
    }

    @Test
    fun `ignores non-alphabetic characters in prefixes`() {
      insertTaxon(1, "Matching result")

      val expected = listOf(namesRow(1, 1, "Matching result"))

      val actual = store.findNamesByWordPrefixes(listOf("!?m%%[]\$_a...", "...", "(r)E"))
      assertEquals(expected, actual)
    }

    @Test
    fun `can search common names`() {
      insertTaxon(1, "Scientific name", listOf("Common name" to null))
      insertTaxon(2, "Somewhat common name", listOf("No match" to null))

      val expected = listOf(namesRow(2, 1, "Common name", isScientific = false))

      val actual = store.findNamesByWordPrefixes(listOf("co", "na"), scientific = false)
      assertEquals(expected, actual)
    }

    @Test
    fun `is sensitive to order of prefixes`() {
      insertTaxon(1, "Scientific name")
      insertTaxon(2, "Name scientific")

      val expected = listOf(namesRow(1, 1, "Scientific name"))

      val actual = store.findNamesByWordPrefixes(listOf("sci", "na"))
      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if prefix list has no valid prefixes`() {
      assertThrows<IllegalArgumentException> {
        store.findNamesByWordPrefixes(listOf("...", "", "     "))
      }
    }

    @Test
    fun `returns results in alphabetical order with first-word matches at the top`() {
      insertTaxon(1, "Species c")
      insertTaxon(2, "Species a")
      insertTaxon(3, "Species b")
      insertTaxon(4, "Another species")

      val expected =
          listOf(
              namesRow(2, 2, "Species a"),
              namesRow(3, 3, "Species b"),
              namesRow(1, 1, "Species c"),
              namesRow(4, 4, "Another species"),
          )

      val actual = store.findNamesByWordPrefixes(listOf("species"))
      assertEquals(expected, actual)
    }

    @Test
    fun `ignores diacritics`() {
      insertTaxon(1, "Spécies a")
      insertTaxon(2, "Species b")

      val expected = listOf(namesRow(1, 1, "Spécies a"), namesRow(2, 2, "Species b"))

      val actual = store.findNamesByWordPrefixes(listOf("species"))
      assertEquals(expected, actual)
    }

    @Test
    fun `returns partial list of results if number of matches exceeds the maximum`() {
      insertTaxon(1, "Species a")
      insertTaxon(2, "Species b")
      insertTaxon(3, "Species c")

      val expected =
          listOf(
              namesRow(1, 1, "Species a"),
              namesRow(2, 2, "Species b"),
          )

      val actual = store.findNamesByWordPrefixes(listOf("species"), maxResults = 2)
      assertEquals(expected, actual)
    }

    @Test
    fun `does not return duplicate scientific names`() {
      insertTaxon(1, "Species a", fullScientificName = "Species a (Someone 1985)")
      insertTaxon(2, "Species a", fullScientificName = "Species a (Someone else 1986)")

      val expected = listOf(namesRow(1, 1, "Species a"))

      val actual = store.findNamesByWordPrefixes(listOf("species"))
      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class FetchOneByScientificName {
    @Test
    fun `throws exception if scientific name does not exist`() {
      assertThrows<ScientificNameNotFoundException> {
        store.fetchOneByScientificName("nonexistent")
      }
    }

    @Test
    fun `returns vernacular names in multiple languages if no language specified`() {
      insertTaxon(
          1,
          "Scientific name",
          listOf("Common en" to "en", "Common xx" to "xx", "Common unknown" to null),
          "Family",
          "endangered")

      val expected =
          GbifTaxonModel(
              GbifTaxonId(1),
              "Scientific name",
              "Family",
              listOf(
                  GbifVernacularNameModel("Common en", "en"),
                  GbifVernacularNameModel("Common unknown", null),
                  GbifVernacularNameModel("Common xx", "xx"),
              ),
              "endangered")

      val actual = store.fetchOneByScientificName("Scientific name")
      assertEquals(expected, actual)
    }

    @Test
    fun `excludes vernacular names in languages other than the specified one`() {
      insertTaxon(
          1,
          "Scientific name",
          listOf("Common en" to "en", "Common xx" to "xx", "Common unknown" to null),
          "Family")

      val expected =
          GbifTaxonModel(
              GbifTaxonId(1),
              "Scientific name",
              "Family",
              listOf(
                  GbifVernacularNameModel("Common en", "en"),
                  GbifVernacularNameModel("Common unknown", null),
              ),
              null)

      val actual = store.fetchOneByScientificName("Scientific name", "en")
      assertEquals(expected, actual)
    }

    @Test
    fun `returns data for accepted name if multiple taxa have same name`() {
      insertTaxon(
          1,
          "Scientific name",
          fullScientificName = "Scientific name (1)",
          taxonomicStatus = "doubtful")
      insertTaxon(
          2,
          "Scientific name",
          fullScientificName = "Scientific name (2)",
          taxonomicStatus = "accepted")
      insertTaxon(
          3,
          "Scientific name",
          fullScientificName = "Scientific name (3)",
          taxonomicStatus = "synonym")

      val expected =
          GbifTaxonModel(GbifTaxonId(2), "Scientific name", "Scientific", emptyList(), null)

      val actual = store.fetchOneByScientificName("Scientific name", "en")
      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class CheckScientificName {
    @Test
    fun `returns null if name is already correct`() {
      val scientificName = "Scientific name"
      insertTaxon(1, scientificName)

      val actual = store.checkScientificName(scientificName)
      assertNull(actual)
    }

    @Test
    fun `returns not-found problem if there are no close matches`() {
      insertTaxon(1, "Scientific name")

      val expected =
          SpeciesProblemsRow(
              fieldId = SpeciesProblemField.ScientificName,
              typeId = SpeciesProblemType.NameNotFound)

      val actual = store.checkScientificName("Nowhere close")
      assertEquals(expected, actual)
    }

    @Test
    fun `returns suggested name if it is a close match`() {
      val scientificName = "Scientific name"
      insertTaxon(1, scientificName)

      val expected =
          SpeciesProblemsRow(
              fieldId = SpeciesProblemField.ScientificName,
              typeId = SpeciesProblemType.NameMisspelled,
              suggestedValue = scientificName)

      val actual = store.checkScientificName("Scietific nam")
      assertEquals(expected, actual)
    }

    @Test
    fun `returns accepted name if input is a synonym`() {
      val newName = "New name"
      val oldName = "Older synonym"
      insertTaxon(1, oldName, taxonomicStatus = "synonym", acceptedNameUsageId = 2)
      insertTaxon(2, newName)

      val expected =
          SpeciesProblemsRow(
              fieldId = SpeciesProblemField.ScientificName,
              typeId = SpeciesProblemType.NameIsSynonym,
              suggestedValue = newName)

      val actual = store.checkScientificName(oldName)
      assertEquals(expected, actual)
    }

    @Test
    fun `returns accepted name if input is a misspelled synonym`() {
      val newName = "New name"
      insertTaxon(1, "Correct synonym", taxonomicStatus = "synonym", acceptedNameUsageId = 2)
      insertTaxon(2, newName)

      val expected =
          SpeciesProblemsRow(
              fieldId = SpeciesProblemField.ScientificName,
              typeId = SpeciesProblemType.NameIsSynonym,
              suggestedValue = newName)

      val actual = store.checkScientificName("Corect synonm")
      assertEquals(expected, actual)
    }

    @Test
    fun `returns null if there are multiple taxa whose scientific names we render identically`() {
      insertTaxon(1, "Scientific name", fullScientificName = "Scientific name (author1)")
      insertTaxon(
          2,
          "Scientific name",
          fullScientificName = "Scientific name (author2)",
          taxonomicStatus = "doubtful")

      assertNull(store.checkScientificName("Scientific name"))
    }
  }

  private fun insertTaxon(
      id: Any,
      scientificName: String,
      commonNames: Collection<Pair<String, String?>> = emptyList(),
      familyName: String = scientificName.substringBefore(' '),
      threatStatus: String? = null,
      fullScientificName: String = scientificName,
      acceptedNameUsageId: Any? = null,
      taxonomicStatus: String = GbifStore.TAXONOMIC_STATUS_ACCEPTED,
  ): GbifTaxonId {
    val taxonId = id.toIdWrapper { GbifTaxonId(it) }

    dslContext
        .insertInto(GBIF_TAXA)
        .set(GBIF_TAXA.TAXON_ID, taxonId)
        .set(GBIF_TAXA.SCIENTIFIC_NAME, fullScientificName)
        .set(GBIF_TAXA.FAMILY, familyName)
        .set(GBIF_TAXA.TAXON_RANK, "species")
        .set(GBIF_TAXA.TAXONOMIC_STATUS, taxonomicStatus)
        .set(GBIF_TAXA.ACCEPTED_NAME_USAGE_ID, acceptedNameUsageId?.toIdWrapper { GbifTaxonId(it) })
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
      id: Any,
      taxonId: Any,
      name: String,
      language: String? = null,
      isScientific: Boolean = true
  ): GbifNamesRow {
    return GbifNamesRow(
        id = id.toIdWrapper { GbifNameId(it) },
        isScientific = isScientific,
        language = language,
        name = name,
        taxonId = taxonId.toIdWrapper { GbifTaxonId(it) },
    )
  }
}
