package com.terraformation.backend.species

import com.opencsv.CSVParser
import com.terraformation.backend.util.ParsedCsvReader
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DarwinCoreReaderTest {
  @Test
  fun `reads core taxon data`() {
    val reader = DarwinCoreReader(openZipFile("griis.zip"))
    val rows = reader.parseFile("Taxon", ::TestCsvReader).toList()

    assertEquals(
        listOf(
            mapOf(
                "id" to "100000",
                "taxonID" to "100000",
                "scientificName" to "Gekko gekko",
                "acceptedNameUsage" to null,
                "kingdom" to "Animalia",
                "phylum" to "Chordata",
                "class" to "Reptilia",
                "order" to "Squamata",
                "family" to "Gekkonidae",
                "taxonRank" to "SPECIES",
                "taxonomicStatus" to "ACCEPTED",
            ),
            mapOf(
                "id" to "100001",
                "taxonID" to "100001",
                "scientificName" to "Cenchrus echinatus L.",
                "acceptedNameUsage" to null,
                "kingdom" to "Plantae",
                "phylum" to "Tracheophyta",
                "class" to "Liliopsida",
                "order" to "Poales",
                "family" to "Poaceae",
                "taxonRank" to "SPECIES",
                "taxonomicStatus" to "ACCEPTED",
            ),
        ),
        rows,
    )
  }

  @Test
  fun `reads extension files`() {
    val reader = DarwinCoreReader(openZipFile("griis.zip"))
    val rows = reader.parseFile("SpeciesProfile", ::TestCsvReader).toList()

    assertEquals(
        listOf(
            mapOf(
                "id" to "100000",
                "isInvasive" to "Null",
                "habitat" to "Terrestrial",
            ),
            mapOf(
                "id" to "100001",
                "isInvasive" to "Invasive",
                "habitat" to "Mangrove",
            ),
        ),
        rows,
    )
  }

  @Test
  fun `throws exception if zipfile does not contain metadata`() {
    val reader = DarwinCoreReader(openZipFile("no-meta.zip"))
    assertThrows<FileNotFoundException> { reader.parseFile("Taxon", ::TestCsvReader) }
  }

  @Test
  fun `throws exception if requested row type is not present in zipfile`() {
    val reader = DarwinCoreReader(openZipFile("griis.zip"))
    assertThrows<FileNotFoundException> { reader.parseFile("Nonexistent", ::TestCsvReader) }
  }

  class TestCsvReader(
      inputStream: InputStream,
      csvParser: CSVParser,
      private val columnNames: List<String>,
  ) : ParsedCsvReader<Map<String, String?>>(inputStream, csvParser, columnNames) {
    override fun parseRow(row: Array<String?>): Map<String, String?> {
      return columnNames.zip(row).toMap()
    }
  }

  private fun openZipFile(fileName: String): ZipFile {
    return ZipFile(File("src/test/resources/species/griis/$fileName"))
  }
}
