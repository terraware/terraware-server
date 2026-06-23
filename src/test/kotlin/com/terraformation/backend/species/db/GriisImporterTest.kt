package com.terraformation.backend.species.db

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.tables.records.GriisResourcesRecord
import com.terraformation.backend.db.default_schema.tables.records.GriisTaxaRecord
import com.terraformation.backend.db.default_schema.tables.references.GRIIS_RESOURCES
import com.terraformation.backend.db.default_schema.tables.references.GRIIS_TAXA
import com.terraformation.backend.species.db.GriisImporter.GriisResource
import com.terraformation.backend.util.UriFetcher
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.UriBuilder
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class GriisImporterTest : DatabaseTest() {
  private val uriFetcher: UriFetcher = mockk()
  private val importer: GriisImporter by lazy {
    GriisImporter(dslContext, jacksonObjectMapper(), uriFetcher)
  }

  @Nested
  inner class ImportResource {
    @Test
    fun `imports plant species from archive`() {
      val updatedTime = Instant.ofEpochSecond(123)
      mockResource("test", javaClass.getResourceAsStream("/species/griis/griis.zip")!!)

      importer.importResource(GriisResource("test", updatedTime))

      assertTableEquals(
          GriisResourcesRecord(
              countryCode = "US",
              publicationDate = LocalDate.of(2026, 6, 22),
              resourceName = "test",
              updatedTime = updatedTime,
          )
      )

      val resourceId = dslContext.fetchValue(GRIIS_RESOURCES.ID)!!

      // The taxon list also has an animal species, but it should be ignored.
      assertTableEquals(
          GriisTaxaRecord(
              establishmentMeans = "Alien",
              griisResourceId = resourceId,
              habitat = "Mangrove",
              isInvasive = true,
              occurrenceStatus = "Present",
              scientificName = "Cenchrus echinatus L.",
              taxonId = 100001L,
              taxonomicStatus = "accepted",
              taxonRank = "species",
          )
      )
    }

    @Test
    fun `skips up-to-date resources`() {
      val updatedTime = Instant.ofEpochSecond(123)
      mockResource("test", javaClass.getResourceAsStream("/species/griis/griis.zip")!!)

      insertGriisResource(resourceName = "test", updatedTime = updatedTime)
      insertGriisTaxon()

      val expectedResources = dslContext.fetch(GRIIS_RESOURCES)
      val expectedTaxa = dslContext.fetch(GRIIS_TAXA)

      importer.importResource(GriisResource("test", updatedTime))

      assertTableEquals(expectedResources)
      assertTableEquals(expectedTaxa)
    }
  }

  @Nested
  inner class FetchResourceList {
    @Test
    fun `parses resource list response`() {
      val response =
          """
          {
              "aaData": [
                  [
                      "dummy", "dummy", "dummy", "dummy", "dummy",
                      "dummy", "2026-06-22 10:20:30", "dummy", "dummy", "dummy",
                      "dummy", "resource1", "dummy"
                  ],
                  [
                      "dummy", "dummy", "dummy", "dummy", "dummy",
                      "dummy", "2020-05-01 11:22:33", "dummy", "dummy", "dummy",
                      "dummy", "resource2", "dummy"
                  ]
              ],
              "iTotalDisplayRecords": 2,
              "iTotalRecords": 2
          }
          """
              .trimIndent()
              .byteInputStream()
      every { uriFetcher.openStream(GriisImporter.griisListResourcesUri) } returns response

      val expected =
          listOf(
              GriisResource(
                  "resource1",
                  ZonedDateTime.of(2026, 6, 22, 10, 20, 30, 0, ZoneOffset.UTC).toInstant(),
              ),
              GriisResource(
                  "resource2",
                  ZonedDateTime.of(2020, 5, 1, 11, 22, 33, 0, ZoneOffset.UTC).toInstant(),
              ),
          )

      val actual = importer.fetchResourceList()

      assertEquals(expected, actual)
    }
  }

  private fun mockResource(resourceName: String, content: InputStream) {
    val uri =
        UriBuilder.fromUri(GriisImporter.griisResourceUri).queryParam("r", resourceName).build()
    every { uriFetcher.openStream(uri) } returns content
  }
}
