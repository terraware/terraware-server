package com.terraformation.backend.eventlog.db

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.event.OrganizationCreatedEventV1
import com.terraformation.backend.customer.event.OrganizationRenamedEventV1
import com.terraformation.backend.customer.event.OrganizationRenamedEventV2
import com.terraformation.backend.customer.event.ProjectCreatedEventV1
import com.terraformation.backend.customer.event.ProjectRenamedEventV1
import com.terraformation.backend.customer.event.ProjectRenamedEventV2
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.eventlog.UpgradableEvent
import com.terraformation.backend.seedbank.event.ViabilityTestCreatedEventV1
import com.terraformation.backend.seedbank.event.ViabilityTestCreatedEventV2
import com.terraformation.backend.seedbank.event.ViabilityTestUpdatedEventV1
import com.terraformation.backend.seedbank.event.ViabilityTestUpdatedEventValues
import com.terraformation.backend.seedbank.event.WithdrawalCreatedEventV1
import com.terraformation.backend.seedbank.event.WithdrawalCreatedEventV2
import com.terraformation.backend.seedbank.event.WithdrawalUpdatedEventV1
import com.terraformation.backend.seedbank.event.WithdrawalUpdatedEventValues
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EventUpgradeUtilsTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
  private val eventLogStore: EventLogStore by lazy {
    EventLogStore(clock, dslContext, objectMapper)
  }
  private val eventUpgradeUtils: EventUpgradeUtils by lazy {
    EventUpgradeUtils(dslContext, eventLogStore)
  }

  private val organizationId = OrganizationId(1)
  private val projectId = ProjectId(2)

  @Nested
  inner class GetPreviousOrganizationName {
    @Test
    fun `upgrades first OrganizationRenamedEventV1`() {
      insertEvent(OrganizationCreatedEventV1(organizationId, "Old name"))

      testUpgrade(
          OrganizationRenamedEventV1(organizationId, "New name"),
          OrganizationRenamedEventV2(
              changedFrom = OrganizationRenamedEventV2.Values("Old name"),
              changedTo = OrganizationRenamedEventV2.Values("New name"),
              organizationId = organizationId,
          ),
      )
    }

    @Test
    fun `upgrades second OrganizationRenamedEventV1`() {
      insertEvent(OrganizationCreatedEventV1(organizationId, "Old name"))
      insertEvent(OrganizationRenamedEventV1(organizationId, "Middle name"))

      testUpgrade(
          OrganizationRenamedEventV1(organizationId, "New name"),
          OrganizationRenamedEventV2(
              changedFrom = OrganizationRenamedEventV2.Values("Middle name"),
              changedTo = OrganizationRenamedEventV2.Values("New name"),
              organizationId = organizationId,
          ),
      )
    }

    // This could happen if there are two V1 renames and we're querying them for the first time; the
    // first one would be upgraded and written to the database before the second one was upgraded.
    @Test
    fun `upgrades OrganizationRenamedEventV1 that follows an already-upgraded rename`() {
      insertEvent(OrganizationCreatedEventV1(organizationId, "Old name"))
      insertEvent(
          OrganizationRenamedEventV2(
              changedFrom = OrganizationRenamedEventV2.Values("Old name"),
              changedTo = OrganizationRenamedEventV2.Values("Middle name"),
              organizationId = organizationId,
          )
      )

      testUpgrade(
          OrganizationRenamedEventV1(organizationId, "New name"),
          OrganizationRenamedEventV2(
              changedFrom = OrganizationRenamedEventV2.Values("Middle name"),
              changedTo = OrganizationRenamedEventV2.Values("New name"),
              organizationId = organizationId,
          ),
      )
    }
  }

  @Nested
  inner class GetPreviousProjectName {
    @Test
    fun `upgrades first ProjectRenamedEventV1`() {
      insertEvent(
          ProjectCreatedEventV1(
              countryCode = null,
              name = "Old name",
              organizationId = organizationId,
              projectId = projectId,
          )
      )

      testUpgrade(
          ProjectRenamedEventV1("New name", organizationId, projectId),
          ProjectRenamedEventV2(
              changedFrom = ProjectRenamedEventV2.Values("Old name"),
              changedTo = ProjectRenamedEventV2.Values("New name"),
              organizationId = organizationId,
              projectId = projectId,
          ),
      )
    }

    @Test
    fun `upgrades second ProjectRenamedEventV1`() {
      insertEvent(
          ProjectCreatedEventV1(
              countryCode = null,
              name = "Old name",
              organizationId = organizationId,
              projectId = projectId,
          )
      )
      insertEvent(ProjectRenamedEventV1("Middle name", organizationId, projectId))

      testUpgrade(
          ProjectRenamedEventV1("New name", organizationId, projectId),
          ProjectRenamedEventV2(
              changedFrom = ProjectRenamedEventV2.Values("Middle name"),
              changedTo = ProjectRenamedEventV2.Values("New name"),
              organizationId = organizationId,
              projectId = projectId,
          ),
      )
    }

    // This could happen if there are two V1 renames and we're querying them for the first time; the
    // first one would be upgraded and written to the database before the second one was upgraded.
    @Test
    fun `upgrades ProjectRenamedEventV1 that follows an already-upgraded rename`() {
      insertEvent(
          ProjectCreatedEventV1(
              countryCode = null,
              name = "Old name",
              organizationId = organizationId,
              projectId = projectId,
          )
      )
      insertEvent(
          ProjectRenamedEventV2(
              changedFrom = ProjectRenamedEventV2.Values("Old name"),
              changedTo = ProjectRenamedEventV2.Values("Middle name"),
              organizationId = organizationId,
              projectId = projectId,
          )
      )

      testUpgrade(
          ProjectRenamedEventV1("New name", organizationId, projectId),
          ProjectRenamedEventV2(
              changedFrom = ProjectRenamedEventV2.Values("Middle name"),
              changedTo = ProjectRenamedEventV2.Values("New name"),
              organizationId = organizationId,
              projectId = projectId,
          ),
      )
    }
  }

  @Nested
  inner class GetWithdrawalValuesMissingFromV1 {
    private val withdrawalDate = LocalDate.of(2021, 1, 1)

    private lateinit var accessionId: AccessionId
    private lateinit var facilityId: FacilityId
    private lateinit var otherUserId: UserId

    @BeforeEach
    fun setUpAccession() {
      otherUserId = insertUser()
      insertOrganization()
      facilityId = insertFacility()
      accessionId = insertAccession()
    }

    @Test
    fun `uses the current withdrawal row when no later edit touched the fields`() {
      val viabilityTestId = insertViabilityTest()
      val withdrawalId =
          insertSeedbankWithdrawal(
              batchId = null,
              destination = "Lab",
              // The withdrawals_test_id_requires_purpose constraint ties the two together.
              purpose = WithdrawalPurpose.ViabilityTesting,
              viabilityTestId = viabilityTestId,
              withdrawnBy = otherUserId,
          )

      testUpgrade(
          createdEventV1(withdrawalId),
          createdEventV2(
              withdrawalId,
              destination = "Lab",
              viabilityTestId = viabilityTestId,
              withdrawnByUserId = otherUserId,
          ),
      )
    }

    @Test
    fun `uses the value from before the earliest edit that changed the field`() {
      val withdrawalId =
          insertSeedbankWithdrawal(
              batchId = null,
              destination = "Greenhouse",
              withdrawnBy = otherUserId,
          )
      insertEvent(createdEventV1(withdrawalId))

      insertEvent(
          updatedEvent(
              withdrawalId,
              changedFrom = WithdrawalUpdatedEventValues(destination = "Lab"),
              changedTo = WithdrawalUpdatedEventValues(destination = "Nursery"),
          )
      )
      insertEvent(
          updatedEvent(
              withdrawalId,
              changedFrom = WithdrawalUpdatedEventValues(destination = "Nursery"),
              changedTo = WithdrawalUpdatedEventValues(destination = "Greenhouse"),
          )
      )

      assertEquals(
          "Lab",
          upgrade(withdrawalId).destination,
          "Should use the changedFrom of the first edit, not the current row",
      )
    }

    @Test
    fun `ignores later edits that left the field alone`() {
      val withdrawalId =
          insertSeedbankWithdrawal(
              batchId = null,
              destination = "Greenhouse",
              withdrawnBy = user.userId,
          )
      insertEvent(createdEventV1(withdrawalId))

      // Only touches withdrawnByUserId, so it says nothing about the destination.
      insertEvent(
          updatedEvent(
              withdrawalId,
              changedFrom = WithdrawalUpdatedEventValues(withdrawnByUserId = user.userId),
              changedTo = WithdrawalUpdatedEventValues(withdrawnByUserId = otherUserId),
          )
      )
      insertEvent(
          updatedEvent(
              withdrawalId,
              changedFrom = WithdrawalUpdatedEventValues(destination = "Lab"),
              changedTo = WithdrawalUpdatedEventValues(destination = "Greenhouse"),
          )
      )

      val upgraded = upgrade(withdrawalId)

      assertEquals("Lab", upgraded.destination, "destination")
      assertEquals(user.userId, upgraded.withdrawnByUserId, "withdrawnByUserId")
    }

    // A null changedFrom can mean either "this edit left the field alone" or "this edit gave the
    // field its first value". Only the changedTo side tells them apart.
    @Test
    fun `treats a field that an edit populated as null at creation time`() {
      val withdrawalId =
          insertSeedbankWithdrawal(batchId = null, destination = "Lab", withdrawnBy = user.userId)
      insertEvent(createdEventV1(withdrawalId))

      insertEvent(
          updatedEvent(
              withdrawalId,
              changedFrom = WithdrawalUpdatedEventValues(destination = null),
              changedTo = WithdrawalUpdatedEventValues(destination = "Lab"),
          )
      )

      assertNull(
          upgrade(withdrawalId).destination,
          "Destination was empty at creation time even though the row has one now",
      )
    }

    @Test
    fun `returns nulls if the withdrawal has been deleted`() {
      val withdrawalId = insertSeedbankWithdrawal(batchId = null, destination = "Lab")
      insertEvent(createdEventV1(withdrawalId))

      withdrawalsDao.deleteById(withdrawalId)

      assertEquals(createdEventV2(withdrawalId), upgrade(withdrawalId))
    }

    private fun upgrade(withdrawalId: WithdrawalId) =
        eventUpgradeUtils.withdrawalValuesMissingFromV1.upgrade(createdEventV1(withdrawalId))

    private fun createdEventV1(withdrawalId: WithdrawalId) =
        WithdrawalCreatedEventV1(
            purpose = WithdrawalPurpose.Other,
            date = withdrawalDate,
            withdrawalId = withdrawalId,
            accessionId = accessionId,
            facilityId = facilityId,
            organizationId = inserted.organizationId,
        )

    private fun createdEventV2(
        withdrawalId: WithdrawalId,
        destination: String? = null,
        viabilityTestId: ViabilityTestId? = null,
        withdrawnByUserId: UserId? = null,
    ) =
        WithdrawalCreatedEventV2(
            purpose = WithdrawalPurpose.Other,
            date = withdrawalDate,
            destination = destination,
            viabilityTestId = viabilityTestId,
            withdrawnByUserId = withdrawnByUserId,
            withdrawalId = withdrawalId,
            accessionId = accessionId,
            facilityId = facilityId,
            organizationId = inserted.organizationId,
        )

    private fun updatedEvent(
        withdrawalId: WithdrawalId,
        changedFrom: WithdrawalUpdatedEventValues,
        changedTo: WithdrawalUpdatedEventValues,
    ) =
        WithdrawalUpdatedEventV1(
            changedFrom = changedFrom,
            changedTo = changedTo,
            withdrawalId = withdrawalId,
            accessionId = accessionId,
            facilityId = facilityId,
            organizationId = inserted.organizationId,
        )
  }

  @Nested
  inner class GetViabilityTestValuesMissingFromV1 {
    private lateinit var accessionId: AccessionId
    private lateinit var facilityId: FacilityId

    @BeforeEach
    fun setUpAccession() {
      insertOrganization()
      facilityId = insertFacility()
      accessionId = insertAccession()
    }

    @Test
    fun `uses the current viability test row when no later edit touched the fields`() {
      val viabilityTestId =
          insertViabilityTest(notes = "initial notes", staffResponsible = "Some Person")

      testUpgrade(
          createdEventV1(viabilityTestId),
          createdEventV2(
              viabilityTestId,
              notes = "initial notes",
              staffResponsible = "Some Person",
          ),
      )
    }

    @Test
    fun `uses the value from before the earliest edit that changed the field`() {
      val viabilityTestId = insertViabilityTest(notes = "current notes")
      val createdEventLogId = insertEvent(createdEventV1(viabilityTestId))

      insertEvent(
          updatedEvent(
              viabilityTestId,
              changedFrom = ViabilityTestUpdatedEventValues(notes = "initial notes"),
              changedTo = ViabilityTestUpdatedEventValues(notes = "middle notes"),
          )
      )
      insertEvent(
          updatedEvent(
              viabilityTestId,
              changedFrom = ViabilityTestUpdatedEventValues(notes = "middle notes"),
              changedTo = ViabilityTestUpdatedEventValues(notes = "current notes"),
          )
      )

      assertEquals(
          "initial notes",
          eventUpgradeUtils
              .getViabilityTestValuesMissingFromV1(viabilityTestId, createdEventLogId)
              .notes,
      )
    }

    @Test
    fun `returns nulls if the viability test has been deleted`() {
      val viabilityTestId = insertViabilityTest(notes = "notes")
      val createdEventLogId = insertEvent(createdEventV1(viabilityTestId))

      viabilityTestsDao.deleteById(viabilityTestId)

      assertEquals(
          EventUpgradeUtils.ViabilityTestValuesMissingFromV1(null, null),
          eventUpgradeUtils.getViabilityTestValuesMissingFromV1(viabilityTestId, createdEventLogId),
      )
    }

    private fun createdEventV1(viabilityTestId: ViabilityTestId) =
        ViabilityTestCreatedEventV1(
            testType = ViabilityTestType.Lab,
            viabilityTestId = viabilityTestId,
            accessionId = accessionId,
            facilityId = facilityId,
            organizationId = inserted.organizationId,
        )

    private fun createdEventV2(
        viabilityTestId: ViabilityTestId,
        notes: String? = null,
        staffResponsible: String? = null,
    ) =
        ViabilityTestCreatedEventV2(
            testType = ViabilityTestType.Lab,
            notes = notes,
            staffResponsible = staffResponsible,
            viabilityTestId = viabilityTestId,
            accessionId = accessionId,
            facilityId = facilityId,
            organizationId = inserted.organizationId,
        )

    private fun updatedEvent(
        viabilityTestId: ViabilityTestId,
        changedFrom: ViabilityTestUpdatedEventValues,
        changedTo: ViabilityTestUpdatedEventValues,
    ) =
        ViabilityTestUpdatedEventV1(
            changedFrom = changedFrom,
            changedTo = changedTo,
            viabilityTestId = viabilityTestId,
            accessionId = accessionId,
            facilityId = facilityId,
            organizationId = inserted.organizationId,
        )
  }

  private fun insertEvent(event: PersistentEvent): EventLogId {
    clock.instant = clock.instant.plusSeconds(1)
    return eventLogStore.insertEvent(event)
  }

  private fun testUpgrade(oldEvent: UpgradableEvent, expected: PersistentEvent) {
    val eventLogId = insertEvent(oldEvent)
    val upgradedEvent = oldEvent.toNextVersion(eventLogId, eventUpgradeUtils)

    assertEquals(expected, upgradedEvent)
  }
}
