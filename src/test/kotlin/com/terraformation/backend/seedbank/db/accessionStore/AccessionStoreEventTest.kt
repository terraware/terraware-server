package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.seedbank.event.AccessionCreatedEvent
import com.terraformation.backend.seedbank.event.AccessionUploadedEvent
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class AccessionStoreEventTest : AccessionStoreTest() {
  @Nested
  inner class Create {
    @Test
    fun `create publishes AccessionCreatedEvent for a web accession`() {
      val model = create(accessionModel())

      publisher.assertEventPublished(
          AccessionCreatedEvent(
              accessionNumber = model.accessionNumber!!,
              dataSource = DataSource.Web,
              accessionId = model.id!!,
              facilityId = facilityId,
              organizationId = organizationId,
          )
      )
      publisher.assertEventNotPublished<AccessionUploadedEvent>()
    }

    @Test
    fun `create publishes AccessionUploadedEvent for a file import`() {
      val model = create(accessionModel(source = DataSource.FileImport))

      publisher.assertEventPublished(
          AccessionUploadedEvent(
              accessionNumber = model.accessionNumber!!,
              accessionId = model.id!!,
              facilityId = facilityId,
              organizationId = organizationId,
          )
      )
      publisher.assertEventNotPublished<AccessionCreatedEvent>()
    }
  }
}
