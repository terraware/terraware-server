package com.terraformation.backend.species

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.ExternalDatasetType
import com.terraformation.backend.db.default_schema.tables.references.EXTERNAL_DATASET_IMPORTS
import com.terraformation.backend.db.default_schema.tables.references.WCVP_DISTRIBUTIONS
import com.terraformation.backend.db.default_schema.tables.references.WCVP_TAXA
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.mockUser
import com.terraformation.backend.species.event.WcvpImportedEvent
import io.mockk.every
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.io.path.Path
import kotlin.io.path.exists
import org.junit.Assume.assumeNotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests the WCVP importer. Importing the data is fairly resource-intensive, so this test is skipped
 * unless you specifically ask for it by setting the `TEST_WCVP_IMPORTER` environment variable. This
 * test is intended as a local development tool when working on the importer code.
 *
 * Since this is only expected to be run in local dev environments, a copy of the official
 * distribution of the data is downloaded and cached in the `build` directory.
 */
class WcvpImporterTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val importer by lazy { WcvpImporter(clock, dslContext, eventPublisher) }

  @BeforeEach
  fun maybeSkipTest() {
    assumeNotNull(System.getenv("TEST_WCVP_IMPORTER"))
  }

  @Test
  fun `import WCVP data`() {
    every { user.canImportGlobalSpeciesData() } returns true

    clock.instant = Instant.ofEpochSecond(123)

    val path = Path("build/wcvp.zip").toAbsolutePath()

    if (!path.exists()) {
      perClassLogger().info("Downloading WCVP zipfile")

      WcvpImporter.defaultZipFileUrl.toURL().openStream().use { zipFileStream ->
        Files.copy(zipFileStream, path, StandardCopyOption.REPLACE_EXISTING)
      }
    }

    ZipFile(path.toFile()).use { zipFile -> importer.import(zipFile) }

    eventPublisher.assertEventPublished(WcvpImportedEvent())

    assertNotEquals(0, dslContext.fetchCount(WCVP_TAXA), "Number of taxon rows inserted")
    assertNotEquals(
        0,
        dslContext.fetchCount(WCVP_DISTRIBUTIONS),
        "Number of distribution rows inserted",
    )

    assertEquals(
        clock.instant,
        dslContext.fetchValue(
            EXTERNAL_DATASET_IMPORTS.IMPORTED_TIME,
            EXTERNAL_DATASET_IMPORTS.EXTERNAL_DATASET_TYPE_ID.eq(ExternalDatasetType.WCVP),
        ),
        "Import timestamp",
    )
  }
}
