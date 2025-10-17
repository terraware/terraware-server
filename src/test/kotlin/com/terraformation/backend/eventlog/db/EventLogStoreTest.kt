package com.terraformation.backend.eventlog.db

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.records.EventLogRecord
import com.terraformation.backend.db.default_schema.tables.references.EVENT_LOG
import com.terraformation.backend.eventlog.CircularEventUpgradePathDetectedException
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.eventlog.SkipPersistentEventTest
import com.terraformation.backend.eventlog.UpgradableEvent
import com.terraformation.backend.eventlog.model.EventLogEntry
import java.time.Instant
import org.jooq.JSONB
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail

class EventLogStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val objectMapper = jacksonObjectMapper()
  private val store: EventLogStore by lazy {
    EventLogStore(clock, dslContext, EventUpgradeUtils(dslContext), objectMapper)
  }

  @Nested
  inner class FetchByOrganizationId {
    @Test
    fun `can fetch single class by organization id`() {
      val event = TestOrganizationCreatedEventV1(OrganizationId(1), "One")
      val eventLogId = store.insertEvent(event)
      store.insertEvent(TestOrganizationDeletedEventV1(OrganizationId(1)))
      store.insertEvent(TestOrganizationCreatedEventV1(OrganizationId(2), "Two"))

      assertEquals(
          listOf(EventLogEntry(user.userId, Instant.EPOCH, event, eventLogId)),
          store.fetchByOrganizationId<TestOrganizationCreatedEventV1>(OrganizationId(1)),
      )
    }

    @Test
    fun `can fetch multiple classes`() {
      val event1 = TestOrganizationCreatedEventV1(OrganizationId(1), "One")
      val eventLogId1 = store.insertEvent(event1)
      clock.instant = Instant.ofEpochSecond(1)
      val event2 = TestOrganizationDeletedEventV3(OrganizationId(1))
      val eventLogId2 = store.insertEvent(event2)

      // Event for different org ID shouldn't be returned
      store.insertEvent(TestOrganizationCreatedEventV1(OrganizationId(2), "Two"))

      val expected =
          listOf(
              EventLogEntry(user.userId, Instant.EPOCH, event1, eventLogId1),
              EventLogEntry(user.userId, Instant.ofEpochSecond(1), event2, eventLogId2),
          )

      val actual: List<EventLogEntry<TestOrganizationEvent>> =
          store.fetchByOrganizationId(
              OrganizationId(1),
              listOf(
                  TestOrganizationCreatedEventV1::class,
                  TestOrganizationDeletedEventV3::class,
              ),
          )

      assertEquals(expected, actual)
    }

    @Test
    fun `can fetch all events implementing sealed interface`() {
      val event1 = TestOrganizationCreatedEventV1(OrganizationId(1), "One")
      val eventLogId1 = store.insertEvent(event1)
      clock.instant = Instant.ofEpochSecond(1)
      val event2 = TestOrganizationDeletedEventV3(OrganizationId(1))
      val eventLogId2 = store.insertEvent(event2)

      val expected =
          listOf(
              EventLogEntry(user.userId, Instant.EPOCH, event1, eventLogId1),
              EventLogEntry(user.userId, Instant.ofEpochSecond(1), event2, eventLogId2),
          )

      // We're specifying an interface here, not a list of concrete classes.
      val actual = store.fetchByOrganizationId<TestOrganizationEvent>(OrganizationId(1))

      assertEquals(expected, actual)
    }

    @Test
    fun `upgrades old event to latest version`() {
      val organizationId = OrganizationId(1)
      val oldEvent = TestOrganizationDeletedEventV1(organizationId)
      val eventLogId = store.insertEvent(oldEvent)

      val eventRecord = dslContext.selectFrom(EVENT_LOG).fetchSingle()

      val expectedEvent = TestOrganizationDeletedEventV3(organizationId)

      clock.instant = Instant.ofEpochSecond(99)

      assertEquals(
          listOf(EventLogEntry(user.userId, Instant.EPOCH, expectedEvent, eventLogId)),
          store.fetchByOrganizationId<TestOrganizationDeletedEventV3>(organizationId),
      )

      assertTableEquals(
          EventLogRecord(
              id = eventRecord.id,
              createdBy = eventRecord.createdBy,
              createdTime = eventRecord.createdTime,
              eventClass = TestOrganizationDeletedEventV3::class.java.name,
              payload = JSONB.valueOf(objectMapper.writeValueAsString(expectedEvent)),
              originalEventClass = oldEvent.javaClass.name,
              originalPayload = eventRecord.payload,
          )
      )
    }

    @Test
    fun `leaves original event in place when upgrading an already-upgraded event`() {
      val organizationId = OrganizationId(1)
      val eventV1 = TestOrganizationDeletedEventV1(organizationId)
      val eventV2 = TestOrganizationDeletedEventV2(organizationId)
      val eventV3 = TestOrganizationDeletedEventV3(organizationId)

      val eventLogId = store.insertEvent(eventV1)

      // Simulate an earlier upgrade to V2
      dslContext
          .fetchSingle(EVENT_LOG)
          .also {
            it.originalEventClass = it.eventClass
            it.originalPayload = it.payload
            it.eventClass = eventV2.javaClass.name
            it.payload = JSONB.valueOf(objectMapper.writeValueAsString(eventV2))
          }
          .update()

      store.fetchByOrganizationId<TestOrganizationDeletedEventV3>(organizationId)

      assertTableEquals(
          EventLogRecord(
              id = eventLogId,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              eventClass = eventV3.javaClass.name,
              payload = JSONB.valueOf(objectMapper.writeValueAsString(eventV3)),
              originalEventClass = eventV1.javaClass.name,
              originalPayload = JSONB.valueOf(objectMapper.writeValueAsString(eventV1)),
          )
      )
    }

    @Test
    fun `throws exception if requested class is not the latest version`() {
      assertThrows<IllegalArgumentException> {
        store.fetchByOrganizationId<TestOrganizationDeletedEventV1>(OrganizationId(1))
      }
    }

    @Test
    fun `throws exception if requested class is not concrete`() {
      assertThrows<IllegalArgumentException> {
        store.fetchByOrganizationId<TestInterfaceEventV1>(OrganizationId(1))
      }
    }

    @Test
    fun `throws exception if upgrade of old event version has a cycle`() {
      store.insertEvent(TestCircularEventV1(OrganizationId(1)))
      try {
        store.fetchByOrganizationId<TestCircularEventV3>(OrganizationId(1))
        fail("Should have thrown exception")
      } catch (e: CircularEventUpgradePathDetectedException) {
        assertEquals(
            listOf(
                TestCircularEventV1::class.java,
                TestCircularEventV2::class.java,
                TestCircularEventV1::class.java,
            ),
            e.upgradePath,
            "Upgrade path cycle",
        )
      }
    }
  }

  @Nested
  inner class InsertEvent {
    @Test
    fun `serializes event as JSON`() {
      clock.instant = Instant.ofEpochSecond(33)

      val event = TestOrganizationCreatedEventV1(OrganizationId(123), "Org")
      val eventLogId = store.insertEvent(event)

      assertTableEquals(
          EventLogRecord(
              id = eventLogId,
              createdBy = user.userId,
              createdTime = clock.instant,
              eventClass = event.javaClass.name,
              payload = JSONB.valueOf("""{"organizationId": 123, "name": "Org"}"""),
          )
      )
    }
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // Events used by the tests. We don't use real application events because we don't want to start
  // failing if new event versions are introduced in the application code, and also because we need
  // to test handling of malformed event classes.

  sealed interface TestOrganizationEvent : PersistentEvent

  data class TestOrganizationCreatedEventV1(val organizationId: OrganizationId, val name: String) :
      TestOrganizationEvent

  data class TestOrganizationDeletedEventV1(
      val organizationId: OrganizationId,
      val dummy1: Int = 1,
  ) : TestOrganizationEvent, UpgradableEvent {
    override fun toNextVersion(eventUpgradeUtils: EventUpgradeUtils) =
        TestOrganizationDeletedEventV2(organizationId)
  }

  data class TestOrganizationDeletedEventV2(
      val organizationId: OrganizationId,
      val dummy2: Int = 2,
  ) : TestOrganizationEvent, UpgradableEvent {
    override fun toNextVersion(eventUpgradeUtils: EventUpgradeUtils) =
        TestOrganizationDeletedEventV3(organizationId)
  }

  data class TestOrganizationDeletedEventV3(
      val organizationId: OrganizationId,
      val dummy3: Int = 3,
  ) : TestOrganizationEvent

  @SkipPersistentEventTest
  data class TestCircularEventV1(val organizationId: OrganizationId) : UpgradableEvent {
    override fun toNextVersion(eventUpgradeUtils: EventUpgradeUtils) =
        TestCircularEventV2(organizationId)
  }

  @SkipPersistentEventTest
  data class TestCircularEventV2(val organizationId: OrganizationId) : UpgradableEvent {
    override fun toNextVersion(eventUpgradeUtils: EventUpgradeUtils) =
        TestCircularEventV1(organizationId)
  }

  data class TestCircularEventV3(val organizationId: OrganizationId) : PersistentEvent

  @SkipPersistentEventTest interface TestInterfaceEventV1 : PersistentEvent
}
