package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import org.jooq.Field
import org.jooq.Record

data class ModuleModel(
    val id: ModuleId,
    val name: String,
    val phase: CohortPhase,
    val position: Int,
    val additionalResources: String? = null,
    val cohorts: List<CohortModuleModel> = emptyList(),
    val deliverables: List<ModuleDeliverableModel> = emptyList(),
    val eventDescriptions: Map<EventType, String> = emptyMap(),
    val eventSessions: Map<EventType, List<EventModel>> = emptyMap(),
    val overview: String? = null,
    val preparationMaterials: String? = null,
) {
  companion object {
    fun of(
        record: Record,
        deliverablesField: Field<List<ModuleDeliverableModel>>,
        eventsField: Field<Map<EventType, List<EventModel>>>,
        cohortsField: Field<List<CohortModuleModel>>,
    ): ModuleModel {
      return of(
          record,
          { it[deliverablesField] ?: emptyList() },
          { it[eventsField] ?: emptyMap() },
          { it[cohortsField] ?: emptyList() })
    }

    fun of(
        record: Record,
        deliverablesField: Field<List<ModuleDeliverableModel>>,
        eventsField: Field<Map<EventType, List<EventModel>>>,
    ): ModuleModel {
      return of(
          record,
          { it[deliverablesField] ?: emptyList() },
          { it[eventsField] ?: emptyMap() },
          {
            listOf(
                CohortModuleModel(
                    it[COHORT_MODULES.COHORT_ID]!!,
                    it[COHORT_MODULES.MODULE_ID]!!,
                    it[COHORT_MODULES.TITLE]!!,
                    it[COHORT_MODULES.START_DATE]!!,
                    it[COHORT_MODULES.END_DATE]!!,
                ))
          })
    }

    private fun of(
        record: Record,
        getDeliverables: (Record) -> List<ModuleDeliverableModel>,
        getEvents: (Record) -> Map<EventType, List<EventModel>>,
        getCohorts: (Record) -> List<CohortModuleModel>,
    ): ModuleModel {
      return ModuleModel(
          id = record[MODULES.ID]!!,
          name = record[MODULES.NAME]!!,
          phase = record[MODULES.PHASE_ID]!!,
          position = record[MODULES.POSITION]!!,
          additionalResources = record[MODULES.ADDITIONAL_RESOURCES],
          cohorts = getCohorts(record),
          deliverables = getDeliverables(record),
          eventDescriptions =
              listOfNotNull(
                      record[MODULES.LIVE_SESSION_DESCRIPTION]?.let { EventType.LiveSession to it },
                      record[MODULES.ONE_ON_ONE_SESSION_DESCRIPTION]?.let {
                        EventType.OneOnOneSession to it
                      },
                      record[MODULES.RECORDED_SESSION_DESCRIPTION]?.let {
                        EventType.RecordedSession to it
                      },
                      record[MODULES.WORKSHOP_DESCRIPTION]?.let { EventType.Workshop to it },
                  )
                  .toMap(),
          eventSessions = getEvents(record),
          overview = record[MODULES.OVERVIEW],
          preparationMaterials = record[MODULES.PREPARATION_MATERIALS],
      )
    }
  }
}
