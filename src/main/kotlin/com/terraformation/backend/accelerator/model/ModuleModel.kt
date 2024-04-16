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
    val additionalResources: String? = null,
    val cohorts: List<CohortModuleModel> = emptyList(),
    val eventDescriptions: Map<EventType, String> = emptyMap(),
    val eventSessions: Map<EventType, List<EventModel>> = emptyMap(),
    val overview: String? = null,
    val preparationMaterials: String? = null,
) {
  companion object {
    fun of(
        record: Record,
        eventsField: Field<Map<EventType, List<EventModel>>>,
        cohortsField: Field<List<CohortModuleModel>>,
    ): ModuleModel {
      return ModuleModel(
          id = record[MODULES.ID]!!,
          name = record[MODULES.NAME]!!,
          phase = record[MODULES.PHASE_ID]!!,
          additionalResources = record[MODULES.ADDITIONAL_RESOURCES],
          cohorts = record[cohortsField] ?: emptyList(),
          eventDescriptions =
              listOfNotNull(
                      record[MODULES.LIVE_SESSION_DESCRIPTION]?.let { EventType.LiveSession to it },
                      record[MODULES.ONE_ON_ONE_SESSION_DESCRIPTION]?.let {
                        EventType.OneOnOneSession to it
                      },
                      record[MODULES.WORKSHOP_DESCRIPTION]?.let { EventType.Workshop to it },
                  )
                  .toMap(),
          eventSessions = record[eventsField] ?: emptyMap(),
          overview = record[MODULES.OVERVIEW],
          preparationMaterials = record[MODULES.PREPARATION_MATERIALS],
      )
    }

    fun of(
        record: Record,
        eventsField: Field<Map<EventType, List<EventModel>>>,
    ): ModuleModel {
      return ModuleModel(
          id = record[MODULES.ID]!!,
          name = record[MODULES.NAME]!!,
          phase = record[MODULES.PHASE_ID]!!,
          additionalResources = record[MODULES.ADDITIONAL_RESOURCES],
          cohorts =
              listOf(
                  CohortModuleModel(
                      record[COHORT_MODULES.COHORT_ID]!!,
                      record[COHORT_MODULES.START_DATE]!!,
                      record[COHORT_MODULES.END_DATE]!!,
                  )),
          eventDescriptions =
              listOfNotNull(
                      record[MODULES.LIVE_SESSION_DESCRIPTION]?.let { EventType.LiveSession to it },
                      record[MODULES.ONE_ON_ONE_SESSION_DESCRIPTION]?.let {
                        EventType.OneOnOneSession to it
                      },
                      record[MODULES.WORKSHOP_DESCRIPTION]?.let { EventType.Workshop to it },
                  )
                  .toMap(),
          eventSessions = record[eventsField] ?: emptyMap(),
          overview = record[MODULES.OVERVIEW],
          preparationMaterials = record[MODULES.PREPARATION_MATERIALS],
      )
    }
  }
}
