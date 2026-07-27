package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.seedbank.event.AccessionCreatedEvent
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
    }
  }
}
