package com.terraformation.backend.eventlog.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.LongIdWrapper
import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.EVENT_LOG
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.eventlog.CircularEventUpgradePathDetectedException
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.eventlog.UpgradableEvent
import com.terraformation.backend.eventlog.model.EventLogEntry
import jakarta.inject.Named
import java.time.InstantSource
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.DataType
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.databind.ObjectMapper

@Named
class EventLogStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val objectMapper: ObjectMapper,
) {
  private val eventUpgradeUtils = EventUpgradeUtils(dslContext, this)

  fun insertEvent(event: PersistentEvent): EventLogId {
    return with(EVENT_LOG) {
      dslContext
          .insertInto(EVENT_LOG)
          .set(CREATED_BY, currentUser().userId)
          .set(CREATED_TIME, clock.instant())
          .set(EVENT_CLASS, event.javaClass.name)
          .set(PAYLOAD, JSONB.valueOf(objectMapper.writeValueAsString(event)))
          .returning(ID)
          .fetchOne(ID)!!
    }
  }

  fun <T : PersistentEvent> fetchByOrganizationId(
      organizationId: OrganizationId,
      eventClasses: Collection<KClass<out T>>,
  ): List<EventLogEntry<T>> {
    return fetchByIdField(organizationId, "organizationId", ORGANIZATIONS.ID.dataType, eventClasses)
  }

  inline fun <reified T : PersistentEvent> fetchByOrganizationId(
      organizationId: OrganizationId
  ): List<EventLogEntry<T>> {
    return fetchByOrganizationId(organizationId, listOf(T::class))
  }

  fun <T : PersistentEvent> fetchByProjectId(
      projectId: ProjectId,
      eventClasses: Collection<KClass<out T>>,
  ): List<EventLogEntry<T>> {
    return fetchByIdField(projectId, "projectId", PROJECTS.ID.dataType, eventClasses)
  }

  inline fun <reified T : PersistentEvent> fetchByProjectId(
      projectId: ProjectId
  ): List<EventLogEntry<T>> {
    return fetchByProjectId(projectId, listOf(T::class))
  }

  fun <T : PersistentEvent> fetchByIntegerField(
      fieldName: String,
      fieldValue: Long,
      eventClasses: Collection<KClass<out T>>,
  ): List<EventLogEntry<T>> {
    return fetchByIdField(fieldValue, fieldName, SQLDataType.BIGINT, eventClasses)
  }

  fun <T : PersistentEvent> fetchByIds(
      ids: Collection<LongIdWrapper<*>>,
      requestedClasses: Collection<KClass<out T>>? = null,
  ): List<EventLogEntry<T>> {
    val requestedConcreteClasses = requestedClasses?.let { getConcreteClasses(it) }
    val classConditions = requestedConcreteClasses?.let { getLikeConditions(it) } ?: emptyList()
    val idCondition = getConditionForIds(ids)

    val condition =
        DSL.and(
            listOfNotNull(
                idCondition,
                if (classConditions.isNotEmpty()) DSL.or(classConditions) else null,
            )
        )

    return fetchByCondition(condition, requestedConcreteClasses)
  }

  fun <T : PersistentEvent> fetchLastByIds(
      ids: Collection<LongIdWrapper<*>>,
      requestedClasses: Collection<KClass<out T>>? = null,
      beforeEventLogId: EventLogId? = null,
  ): EventLogEntry<T>? {
    val requestedConcreteClasses = requestedClasses?.let { getConcreteClasses(it) }
    val classConditions = requestedConcreteClasses?.let { getLikeConditions(it) } ?: emptyList()
    val idCondition = getConditionForIds(ids)

    val condition =
        DSL.and(
            listOfNotNull(
                idCondition,
                if (classConditions.isNotEmpty()) DSL.or(classConditions) else null,
                beforeEventLogId?.let { EVENT_LOG.ID.lt(it) },
            )
        )

    return fetchLastByCondition(condition, requestedConcreteClasses)
  }

  inline fun <reified T : PersistentEvent> fetchLastByIds(
      ids: Collection<LongIdWrapper<*>>,
      beforeEventLogId: EventLogId? = null,
  ): EventLogEntry<T>? {
    return fetchLastByIds(ids, listOf(T::class), beforeEventLogId)
  }

  inline fun <reified T : PersistentEvent> fetchLastById(
      id: LongIdWrapper<*>,
      beforeEventLogId: EventLogId? = null,
  ): EventLogEntry<T>? {
    return fetchLastByIds(listOf(id), beforeEventLogId)
  }

  private fun <I : Any, T : PersistentEvent> fetchByIdField(
      id: I,
      idFieldName: String,
      idDataType: DataType<I?>,
      requestedClasses: Collection<KClass<out T>>,
  ): List<EventLogEntry<T>> {
    return with(EVENT_LOG) {
      val requestedConcreteClasses = getConcreteClasses(requestedClasses)
      val likeConditions = getLikeConditions(requestedConcreteClasses)
      val condition =
          DSL.and(
              payloadField(idFieldName, idDataType).eq(id),
              DSL.or(likeConditions),
          )

      fetchByCondition(condition, requestedConcreteClasses)
    }
  }

  private fun getLikeConditions(
      requestedConcreteClasses: List<KClass<out PersistentEvent>>
  ): List<Condition> =
      requestedConcreteClasses.map {
        EVENT_LOG.EVENT_CLASS.like(it.java.name.substringBeforeLast('V') + "V%")
      }

  private fun <T : PersistentEvent> getConcreteClasses(
      requestedClasses: Collection<KClass<out T>>
  ): List<KClass<out T>> {
    val requestedConcreteClasses =
        requestedClasses.flatMap { requestedClass ->
          if (requestedClass.isSealed) {
            requestedClass.sealedSubclasses.filterNot { it.isSubclassOf(UpgradableEvent::class) }
          } else {
            listOf(requestedClass)
          }
        }
    return requestedConcreteClasses
  }

  private fun <T : PersistentEvent> fetchByCondition(
      condition: Condition,
      requestedClasses: Collection<KClass<out T>>?,
  ): List<EventLogEntry<T>> {
    validateRequestedClasses(requestedClasses)

    return with(EVENT_LOG) {
      dslContext
          .select(CREATED_BY, CREATED_TIME, EVENT_CLASS, ID, PAYLOAD)
          .from(EVENT_LOG)
          .where(condition)
          .orderBy(CREATED_TIME, ID)
          .fetch { eventLogEntryFromRecord(it, requestedClasses) }
    }
  }

  private fun <T : PersistentEvent> fetchLastByCondition(
      condition: Condition,
      requestedClasses: Collection<KClass<out T>>?,
  ): EventLogEntry<T>? {
    validateRequestedClasses(requestedClasses)

    return with(EVENT_LOG) {
      dslContext
          .select(CREATED_BY, CREATED_TIME, EVENT_CLASS, ID, PAYLOAD)
          .from(EVENT_LOG)
          .where(condition)
          .orderBy(CREATED_TIME.desc(), ID.desc())
          .limit(1)
          .fetchOne { eventLogEntryFromRecord(it, requestedClasses) }
    }
  }

  private fun validateRequestedClasses(requestedClasses: Collection<KClass<out PersistentEvent>>?) {
    requestedClasses?.forEach { requestedClass ->
      if (requestedClass.isSubclassOf(UpgradableEvent::class)) {
        throw IllegalArgumentException(
            "${requestedClass.java.name} is upgradable; only the latest version may be queried",
        )
      }
      if (requestedClass.isAbstract || requestedClass.java.isInterface) {
        throw IllegalArgumentException("${requestedClass.java.name} is not a concrete class")
      }
    }
  }

  /**
   * Returns an [EventLogEntry] containing the latest version of an event from an [EVENT_LOG]
   * record. If the event as stored in the database is using an out-of-date version of the event
   * class, updates the database to store the new version.
   */
  private fun <T : PersistentEvent> eventLogEntryFromRecord(
      record: Record,
      requestedClasses: Collection<KClass<out T>>?,
  ): EventLogEntry<T> {
    val eventLogId = record[EVENT_LOG.ID]!!
    val className = record[EVENT_LOG.EVENT_CLASS]!!
    val rawEventObject =
        objectMapper.readValue(record[EVENT_LOG.PAYLOAD]!!.data(), Class.forName(className))
    if (rawEventObject !is PersistentEvent) {
      throw IllegalStateException("Event class $className does not implement PersistentEvent")
    }

    val eventObject = upgradeToLatest(eventLogId, rawEventObject)

    if (requestedClasses != null && requestedClasses.none { it.isInstance(eventObject) }) {
      val requestedClassNames = requestedClasses.joinToString()
      throw IllegalStateException(
          "Event class $className is not on requested list: $requestedClassNames"
      )
    }

    if (eventObject != rawEventObject) {
      writeNewEventVersion(record[EVENT_LOG.ID]!!, eventObject)
    }

    @Suppress("UNCHECKED_CAST")
    return EventLogEntry(
        createdBy = record[EVENT_LOG.CREATED_BY]!!,
        createdTime = record[EVENT_LOG.CREATED_TIME]!!,
        event = eventObject as T,
        id = eventLogId,
    )
  }

  /**
   * Updates an event's payload and class in the database after it is upgraded to a new version.
   *
   * The original class and payload (that is, the class and payload from when the event was
   * originally published) are preserved. If an event is upgraded repeatedly as new versions are
   * introduced over time, the intermediate versions are not preserved, just the original one and
   * the latest one.
   */
  private fun writeNewEventVersion(eventLogId: EventLogId, eventObject: PersistentEvent) {
    with(EVENT_LOG) {
      dslContext
          .update(EVENT_LOG)
          .set(ORIGINAL_EVENT_CLASS, DSL.coalesce(ORIGINAL_EVENT_CLASS, EVENT_CLASS))
          .set(ORIGINAL_PAYLOAD, DSL.coalesce(ORIGINAL_PAYLOAD, PAYLOAD))
          .set(EVENT_CLASS, eventObject.javaClass.name)
          .set(PAYLOAD, JSONB.valueOf(objectMapper.writeValueAsString(eventObject)))
          .where(ID.eq(eventLogId))
          .execute()
    }
  }

  /**
   * Returns the latest version of an event. If the event is already the latest version, returns it
   * unmodified. If not, calls [UpgradableEvent.toNextVersion] repeatedly until there are no more
   * upgrades available.
   */
  private fun upgradeToLatest(eventLogId: EventLogId, event: PersistentEvent): PersistentEvent {
    val seenClasses = mutableSetOf<Class<out PersistentEvent>>()
    var upgradedEvent: PersistentEvent = event

    while (upgradedEvent is UpgradableEvent) {
      val eventClass = upgradedEvent.javaClass
      if (eventClass in seenClasses) {
        throw CircularEventUpgradePathDetectedException(seenClasses.toList() + eventClass)
      }

      seenClasses.add(eventClass)

      upgradedEvent = upgradedEvent.toNextVersion(eventLogId, eventUpgradeUtils)
    }

    return upgradedEvent
  }

  private fun getConditionForIds(ids: Collection<LongIdWrapper<*>>): Condition {
    require(ids.isNotEmpty()) { "No IDs specified" }

    return DSL.and(
        ids.map { payloadField(it.eventLogPropertyName, SQLDataType.BIGINT).eq(it.value) }
    )
  }

  private fun <T : Any?> payloadField(fieldName: String, dataType: DataType<T>): Field<T> {
    return DSL.cast(DSL.jsonbGetAttributeAsText(EVENT_LOG.PAYLOAD, fieldName), dataType)
  }
}
