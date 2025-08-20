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
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.report.event.SeedFundReportSubmittedEvent
import com.terraformation.backend.report.model.SeedFundReportBodyModelV1
import io.mockk.Runs
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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
            facilityNumber = 1,
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

    service.on(OrganizationTimeZoneChangedEvent(organizationId, null, ZoneOffset.UTC))

    publisher.assertExactEventsPublished(
        listOf(FacilityTimeZoneChangedEvent(facilityWithoutTimeZone))
    )
    assertIsEventListener<OrganizationTimeZoneChangedEvent>(service)
  }

  @Test
  fun `updates nurseries and seed banks with information from submitted reports`() {
    val organizationId = OrganizationId(1)
    val unpopulatedNurseryId = FacilityId(1)
    val unpopulatedSeedBankId = FacilityId(2)
    val populatedNurseryId = FacilityId(3)
    val populatedSeedBankId = FacilityId(4)

    val unpopulatedNurseryModel =
        FacilityModel(
            connectionState = FacilityConnectionState.NotConnected,
            createdTime = Instant.EPOCH,
            description = null,
            facilityNumber = 1,
            id = unpopulatedNurseryId,
            lastTimeseriesTime = null,
            maxIdleMinutes = 0,
            modifiedTime = Instant.EPOCH,
            name = "Nursery without any dates or capacity",
            nextNotificationTime = Instant.EPOCH,
            organizationId = organizationId,
            type = FacilityType.Nursery,
        )

    val populatedNurseryModel =
        unpopulatedNurseryModel.copy(
            buildCompletedDate = LocalDate.of(2023, 1, 2),
            buildStartedDate = LocalDate.of(2023, 1, 1),
            capacity = 50,
            id = populatedNurseryId,
            name = "Nursery that already has dates and capacity filled in",
            operationStartedDate = LocalDate.of(2023, 1, 3),
        )

    val unpopulatedSeedBankModel =
        unpopulatedNurseryModel.copy(
            id = unpopulatedSeedBankId,
            name = "Seed bank without any dates",
            type = FacilityType.SeedBank,
        )

    val populatedSeedBankModel =
        unpopulatedSeedBankModel.copy(
            buildCompletedDate = LocalDate.of(2023, 2, 2),
            buildStartedDate = LocalDate.of(2023, 2, 1),
            id = populatedSeedBankId,
            name = "Seed bank that already has dates filled in",
            operationStartedDate = LocalDate.of(2023, 2, 3),
        )

    val event =
        SeedFundReportSubmittedEvent(
            SeedFundReportId(1),
            SeedFundReportBodyModelV1(
                nurseries =
                    listOf(
                        SeedFundReportBodyModelV1.Nursery(
                            buildCompletedDate = LocalDate.of(2023, 3, 2),
                            buildStartedDate = LocalDate.of(2023, 3, 1),
                            capacity = 123,
                            id = unpopulatedNurseryId,
                            mortalityRate = 0,
                            name = "name",
                            operationStartedDate = LocalDate.of(2023, 3, 3),
                            totalPlantsPropagated = 0,
                        ),
                        SeedFundReportBodyModelV1.Nursery(
                            buildCompletedDate = LocalDate.of(2023, 4, 2),
                            buildStartedDate = LocalDate.of(2023, 4, 1),
                            capacity = 123,
                            id = populatedNurseryId,
                            mortalityRate = 0,
                            name = "name",
                            operationStartedDate = LocalDate.of(2023, 4, 3),
                            totalPlantsPropagated = 0,
                        ),
                    ),
                organizationName = "org",
                seedBanks =
                    listOf(
                        SeedFundReportBodyModelV1.SeedBank(
                            buildCompletedDate = LocalDate.of(2023, 5, 2),
                            buildStartedDate = LocalDate.of(2023, 5, 1),
                            id = unpopulatedSeedBankId,
                            name = "name",
                            operationStartedDate = LocalDate.of(2023, 5, 3),
                        ),
                        SeedFundReportBodyModelV1.SeedBank(
                            buildCompletedDate = LocalDate.of(2023, 6, 2),
                            buildStartedDate = LocalDate.of(2023, 6, 1),
                            id = populatedSeedBankId,
                            name = "name",
                            operationStartedDate = LocalDate.of(2023, 6, 3),
                        ),
                    ),
            ),
        )

    val newlyPopulatedNurseryModel =
        unpopulatedNurseryModel.copy(
            buildCompletedDate = LocalDate.of(2023, 3, 2),
            buildStartedDate = LocalDate.of(2023, 3, 1),
            capacity = 123,
            operationStartedDate = LocalDate.of(2023, 3, 3),
        )

    val newlyPopulatedSeedBankModel =
        unpopulatedSeedBankModel.copy(
            buildCompletedDate = LocalDate.of(2023, 5, 2),
            buildStartedDate = LocalDate.of(2023, 5, 1),
            operationStartedDate = LocalDate.of(2023, 5, 3),
        )

    every { facilityStore.fetchOneById(unpopulatedNurseryId) } returns unpopulatedNurseryModel
    every { facilityStore.fetchOneById(unpopulatedSeedBankId) } returns unpopulatedSeedBankModel
    every { facilityStore.fetchOneById(populatedNurseryId) } returns populatedNurseryModel
    every { facilityStore.fetchOneById(populatedSeedBankId) } returns populatedSeedBankModel
    every { facilityStore.update(any()) } just Runs
    excludeRecords { facilityStore.fetchOneById(any()) }

    service.on(event)

    verify { facilityStore.update(newlyPopulatedNurseryModel) }
    verify { facilityStore.update(newlyPopulatedSeedBankModel) }

    // Shouldn't attempt to update the other facilities.
    confirmVerified(facilityStore)

    assertIsEventListener<SeedFundReportSubmittedEvent>(service)
  }
}
