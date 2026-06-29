package com.terraformation.backend.tracking

import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.gis.CountryDetector
import com.terraformation.backend.gis.EcoregionImporter
import com.terraformation.backend.gis.event.EcoregionsImportedEvent
import com.terraformation.backend.log.perClassLogger
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.exists
import org.junit.Assume.assumeNotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests the ecoregion importer. Importing the ecoregion data is fairly resource-intensive, so this
 * test is skipped unless you specifically ask for it by setting the `TEST_ECOREGION_IMPORTER`
 * environment variable.
 *
 * Since this is only expected to be run in local dev environments, a copy of the official
 * distribution of the data is downloaded and cached in the `build` directory.
 */
class EcoregionImporterTest : DatabaseTest() {
  private val eventPublisher = TestEventPublisher()
  private val importer by lazy { EcoregionImporter(CountryDetector(), dslContext, eventPublisher) }

  @BeforeEach
  fun maybeSkipTest() {
    assumeNotNull(System.getenv("TEST_ECOREGION_IMPORTER"))
  }

  @Test
  fun `import ecoregions data`() {
    val deletedEcoregionId = insertEcoregion(id = 1234567, ecoName = "Old Region")
    val path = Path("build/ecoregions.zip").toAbsolutePath()

    if (!path.exists()) {
      perClassLogger().info("Downloading ecoregions zipfile")

      EcoregionImporter.defaultZipFileUrl.toURL().openStream().use { zipFileStream ->
        Files.copy(zipFileStream, path, StandardCopyOption.REPLACE_EXISTING)
      }
    }

    val result = importer.importEcoregions(path)

    eventPublisher.assertEventPublished(EcoregionsImportedEvent())

    assertEquals(
        EcoregionImporter.EcoregionImportResult(
            obsoleteEcoregions = mapOf(deletedEcoregionId to "Old Region"),
        ),
        result,
    )
  }
}
