package com.terraformation.backend.species.db

import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.GbifNameId
import com.terraformation.backend.db.GbifTaxonId
import com.terraformation.backend.db.tables.pojos.GbifNamesRow
import com.terraformation.backend.db.tables.references.GBIF_NAMES
import com.terraformation.backend.db.tables.references.GBIF_NAME_WORDS
import com.terraformation.backend.db.tables.references.GBIF_TAXA
import com.terraformation.backend.db.tables.references.GBIF_VERNACULAR_NAMES
import org.junit.jupiter.api.Assertions.assertEquals
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
      insertTaxon(2, "Unscientific name", listOf("Scientific name but it is common"))
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
      insertTaxon(1, "Scientific name", listOf("Common name"))
      insertTaxon(2, "Somewhat common name", listOf("No match"))

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
    fun `returns results in alphabetical order`() {
      insertTaxon(1, "Species c")
      insertTaxon(2, "Species a")
      insertTaxon(3, "Species b")

      val expected =
          listOf(
              namesRow(2, 2, "Species a"),
              namesRow(3, 3, "Species b"),
              namesRow(1, 1, "Species c"),
          )

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
  }

  private fun insertTaxon(
      id: Any,
      scientificName: String,
      commonNames: Collection<String> = emptyList(),
  ): GbifTaxonId {
    val taxonId = id.toIdWrapper { GbifTaxonId(it) }

    dslContext
        .insertInto(GBIF_TAXA)
        .set(GBIF_TAXA.TAXON_ID, taxonId)
        .set(GBIF_TAXA.SCIENTIFIC_NAME, scientificName)
        .set(GBIF_TAXA.TAXON_RANK, "species")
        .set(GBIF_TAXA.TAXONOMIC_STATUS, "accepted")
        .execute()

    insertName(taxonId, scientificName, true)

    commonNames.forEach { name ->
      dslContext
          .insertInto(GBIF_VERNACULAR_NAMES)
          .set(GBIF_VERNACULAR_NAMES.TAXON_ID, taxonId)
          .set(GBIF_VERNACULAR_NAMES.VERNACULAR_NAME, name)
          .execute()

      insertName(taxonId, name, false)
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
          .set(GBIF_NAME_WORDS.WORD, word.lowercase())
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
