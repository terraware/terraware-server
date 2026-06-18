package com.terraformation.backend.tracking

import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.gis.BotanicalCountryImporter
import com.terraformation.backend.gis.event.BotanicalCountriesImportedEvent
import com.terraformation.backend.log.perClassLogger
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import org.junit.Assume.assumeNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests the botanical country importer. Importing the data is fairly resource-intensive, so this
 * test is skipped unless you specifically ask for it by setting the `TEST_BOTANICAL_IMPORTER`
 * environment variable.
 *
 * Since this is only expected to be run in local dev environments, a copy of the official
 * distribution of the data is downloaded and cached in the `build` directory.
 */
class BotanicalCountryImporterTest : DatabaseTest() {
  private val eventPublisher = TestEventPublisher()
  private val importer by lazy { BotanicalCountryImporter(dslContext, eventPublisher) }

  @BeforeEach
  fun maybeSkipTest() {
    assumeNotNull(System.getenv("TEST_BOTANICAL_IMPORTER"))
  }

  @Test
  fun `import botanical countries`() {
    val path = Path("build/level3.geojson").toAbsolutePath()

    if (!path.exists()) {
      perClassLogger().info("Downloading TDWG level 3 regions file")

      BotanicalCountryImporter.defaultGeoJsonUrl.toURL().openStream().use { fileStream ->
        Files.copy(fileStream, path, StandardCopyOption.REPLACE_EXISTING)
      }
    }

    path.inputStream().use { importer.importBotanicalCountries(it) }

    eventPublisher.assertEventPublished(BotanicalCountriesImportedEvent())
  }
}
