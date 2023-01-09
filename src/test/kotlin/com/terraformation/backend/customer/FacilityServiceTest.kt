package com.terraformation.backend.customer

import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.event.FacilityTimeZoneChangedEvent
import com.terraformation.backend.customer.event.OrganizationTimeZoneChangedEvent
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Test

class FacilityServiceTest {
  private val facilityStore: FacilityStore = mockk()
  private val publisher = TestEventPublisher()
  private val systemUser = SystemUser(mockk())

  private val service = FacilityService(facilityStore, publisher, systemUser)

  @Test
  fun `publishes FacilityTimeZoneChangedEvent when organization time zone changes`() {
    val organizationId = OrganizationId(1)
    val facilityWithoutTimeZone =
        FacilityModel(
            connectionState = FacilityConnectionState.NotConnected,
            createdTime = Instant.EPOCH,
            description = null,
            id = FacilityId(1),
            lastTimeseriesTime = null,
            maxIdleMinutes = 1,
            modifiedTime = Instant.EPOCH,
            name = "Facility",
            nextNotificationTime = Instant.EPOCH,
            organizationId = organizationId,
            type = FacilityType.SeedBank,
        )
    val facilityWithTimeZone =
        facilityWithoutTimeZone.copy(id = FacilityId(2), timeZone = ZoneId.of("Europe/London"))

    every { facilityStore.fetchByOrganizationId(organizationId) } returns
        listOf(facilityWithoutTimeZone, facilityWithTimeZone)

    service.on(OrganizationTimeZoneChangedEvent(organizationId))

    publisher.assertExactEventsPublished(
        listOf(FacilityTimeZoneChangedEvent(facilityWithoutTimeZone)))
    assertIsEventListener<OrganizationTimeZoneChangedEvent>(service)
  }
}
