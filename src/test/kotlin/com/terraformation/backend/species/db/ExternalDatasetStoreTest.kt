package com.terraformation.backend.species.db

import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.ExternalDatasetType
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ExternalDatasetStoreTest : DatabaseTest() {
  private val store: ExternalDatasetStore by lazy { ExternalDatasetStore(dslContext) }

  @Nested
  inner class GetDatasetDate {
    @Test
    fun `returns publication date if present`() {
      val publicationDate = LocalDate.of(2026, 2, 2)
      insertExternalDatasetImport(ExternalDatasetType.GRIIS, lastPublicationDate = publicationDate)

      assertEquals(publicationDate, store.getDatasetDate(ExternalDatasetType.GRIIS))
    }

    @Test
    fun `returns UTC date of import if publication date not present`() {
      val importDate = LocalDate.of(2026, 2, 2)
      insertExternalDatasetImport(
          ExternalDatasetType.GRIIS,
          importedTime =
              ZonedDateTime.of(importDate, LocalTime.of(23, 59, 59), ZoneOffset.UTC).toInstant(),
          lastPublicationDate = null,
      )

      assertEquals(importDate, store.getDatasetDate(ExternalDatasetType.GRIIS))
    }

    @Test
    fun `throws exception if no import record for dataset`() {
      assertThrows<IllegalStateException> { store.getDatasetDate(ExternalDatasetType.WCVP) }
    }
  }
}
