package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.default_schema.ProjectId
import java.time.LocalDate
import org.jooq.Record

data class ModuleModel(
    val id: ModuleId,
    val name: String,
    val phase: CohortPhase,
    val position: Int,
    val additionalResources: String? = null,
    val eventDescriptions: Map<EventType, String> = emptyMap(),
    val overview: String? = null,
    val preparationMaterials: String? = null,
    val cohortId: CohortId? = null,
    val projectId: ProjectId? = null,
    val title: String? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
) {
  companion object {
    fun of(record: Record): ModuleModel {
      return ModuleModel(
          id = record[MODULES.ID]!!,
          name = record[MODULES.NAME]!!,
          phase = record[MODULES.PHASE_ID]!!,
          position = record[MODULES.POSITION]!!,
          additionalResources = record[MODULES.ADDITIONAL_RESOURCES],
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
          overview = record[MODULES.OVERVIEW],
          preparationMaterials = record[MODULES.PREPARATION_MATERIALS],
      )
    }
  }
}
