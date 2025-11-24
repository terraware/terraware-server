package com.terraformation.backend.eventlog.db

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
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.eventlog.UpgradableEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class EventUpgradeUtilsTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventLogStore: EventLogStore by lazy {
    EventLogStore(clock, dslContext, jacksonObjectMapper())
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
      insertEvent(ProjectCreatedEventV1("Old name", organizationId, projectId))

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
      insertEvent(ProjectCreatedEventV1("Old name", organizationId, projectId))
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
      insertEvent(ProjectCreatedEventV1("Old name", organizationId, projectId))
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
