package com.terraformation.backend.eventlog

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.BiomassSpeciesId
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.eventlog.api.EventSubjectPayload
import com.terraformation.backend.eventlog.model.EventLogEntry
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEvent
import kotlin.reflect.KClass
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * Context used when transforming event log entries into payload objects. This is typically passed
 * to factory methods on the payload classes. They can use it to pull information from earlier
 * events or to render localized text.
 *
 * @param entries List of event log entries in chronological order.
 */
class EventLogPayloadContext(
    private val dslContext: DSLContext,
    private val entries: List<EventLogEntry<PersistentEvent>>,
    private val messages: Messages,
) {
  private val eventsByClass = entries.map { it.event }.groupBy { it::class }

  private val biomassSpeciesNames = mutableMapOf<BiomassSpeciesId, BiomassSpecies>()
  private val speciesNames = mutableMapOf<ObservationStore.RecordedSpeciesKey, String>()

  /**
   * Returns the name of a biomass species, possibly looking it up from the database the first time
   * it's requested.
   */
  fun getBiomassSpecies(biomassSpeciesId: BiomassSpeciesId): BiomassSpecies {
    return biomassSpeciesNames.getOrPut(biomassSpeciesId) {
      // If this is an "Other" species with no species ID, and we have its creation event at hand,
      // we can avoid having to hit the database.
      val createEvent =
          firstOrNull<BiomassSpeciesCreatedEvent> { it.biomassSpeciesId == biomassSpeciesId }
      if (createEvent != null && createEvent.scientificName != null) {
        BiomassSpecies(
            createEvent.scientificName,
            createEvent.scientificName,
            null,
        )
      } else {
        dslContext
            .select(
                DSL.coalesce(OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME, SPECIES.SCIENTIFIC_NAME),
                OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME,
                OBSERVATION_BIOMASS_SPECIES.SPECIES_ID,
            )
            .from(OBSERVATION_BIOMASS_SPECIES)
            .leftJoin(SPECIES)
            .on(OBSERVATION_BIOMASS_SPECIES.SPECIES_ID.eq(SPECIES.ID))
            .where(OBSERVATION_BIOMASS_SPECIES.ID.eq(biomassSpeciesId))
            .fetchOne()
            ?.let { BiomassSpecies(it.value1() ?: "$biomassSpeciesId", it.value2(), it.value3()) }
            ?: BiomassSpecies("$biomassSpeciesId", null, null)
      }
    }
  }

  /**
   * Returns the name of a recorded species, possibly looking it up from the database the first time
   * it's requested.
   */
  fun getRecordedSpeciesName(
      certainty: RecordedSpeciesCertainty,
      speciesId: SpeciesId?,
      speciesName: String?,
  ): String {
    val speciesKey = ObservationStore.RecordedSpeciesKey(certainty, speciesId, speciesName)
    return speciesNames.getOrPut(speciesKey) {
      when (certainty) {
        RecordedSpeciesCertainty.Known ->
            dslContext.fetchValue(SPECIES.SCIENTIFIC_NAME, SPECIES.ID.eq(speciesId)) ?: "$speciesId"
        RecordedSpeciesCertainty.Other -> speciesName!!
        RecordedSpeciesCertainty.Unknown -> "Unknown" // TODO: i18n
      }
    }
  }

  /** Returns the localized short text for a subject. */
  fun subjectShortText(kClass: KClass<out EventSubjectPayload>, args: Array<out Any>) =
      messages.eventSubjectShortText(kClass, *args)

  /** Returns the localized short text for a subject. */
  inline fun <reified T : EventSubjectPayload> subjectShortText(vararg args: Any) =
      subjectShortText(T::class, args)

  /** Returns the localized full text for a subject. */
  fun subjectFullText(kClass: KClass<out EventSubjectPayload>, args: Array<out Any>) =
      messages.eventSubjectFullText(kClass, *args)

  /** Returns the localized full text for a subject. */
  inline fun <reified T : EventSubjectPayload> subjectFullText(vararg args: Any) =
      subjectFullText(T::class, args)

  /** Returns all the events of a given class that were fetched from the database. */
  fun <T : PersistentEvent> getEvents(kClass: KClass<T>): List<T> {
    @Suppress("UNCHECKED_CAST")
    return (eventsByClass[kClass] as? List<T>) ?: emptyList()
  }

  /**
   * Returns the first event of a given class that matches a condition, or null if no events match.
   */
  inline fun <reified T : PersistentEvent> firstOrNull(predicate: (T) -> Boolean): T? =
      getEvents(T::class).firstOrNull { predicate(it) }

  /** Returns the first event of a given class that matches a condition. */
  inline fun <reified T : PersistentEvent> first(predicate: (T) -> Boolean): T =
      firstOrNull<T>(predicate)
          ?: throw IllegalStateException(
              "No matching event of type ${T::class.qualifiedName} found"
          )

  /**
   * Returns the first event of a given class that matches a condition and that was published before
   * the specified event, or null if no matching events were published before the specified one.
   * This can be used to find, e.g., the most recent rename event.
   */
  @Suppress("UNCHECKED_CAST")
  fun <T : PersistentEvent> lastEventBefore(
      event: Any,
      kClass: KClass<T>,
      predicate: (T) -> Boolean,
  ): T? {
    var lastMatch: EventLogEntry<PersistentEvent>? = null

    for (entry in entries) {
      if (entry.event === event) {
        return lastMatch?.event as T?
      }
      if (kClass.isInstance(entry.event) && predicate(entry.event as T)) {
        lastMatch = entry
      }
    }

    throw IllegalArgumentException("Stopping-point event does not appear in context")
  }

  /**
   * Returns the first event of a given class that matches a condition and that was published before
   * the specified event, or null if no matching events were published before the specified one.
   * This can be used to find, e.g., the most recent rename event.
   */
  inline fun <reified T : PersistentEvent> lastEventBefore(
      event: Any,
      noinline predicate: (T) -> Boolean,
  ): T? {
    return lastEventBefore(event, T::class, predicate)
  }

  data class BiomassSpecies(
      val displayName: String,
      val scientificName: String?,
      val speciesId: SpeciesId?,
  )
}
