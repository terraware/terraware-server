package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANT_PROJECT_SPECIES
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class ParticipantProjectSpeciesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PARTICIPANT_PROJECT_SPECIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          projects.asSingleValueSublist(
              "projects", PARTICIPANT_PROJECT_SPECIES.PROJECT_ID.eq(PROJECTS.ID)),
          species.asSingleValueSublist(
              "species", PARTICIPANT_PROJECT_SPECIES.SPECIES_ID.eq(SPECIES.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("id", PARTICIPANT_PROJECT_SPECIES.ID) { ParticipantProjectSpeciesId(it) },
          textField("feedback", PARTICIPANT_PROJECT_SPECIES.FEEDBACK),
          textField("rationale", PARTICIPANT_PROJECT_SPECIES.RATIONALE),
          enumField(
              "submissionStatus",
              PARTICIPANT_PROJECT_SPECIES.SUBMISSION_STATUS_ID,
              nullable = false),
      )

  override fun conditionForVisibility(): Condition {
    return if (currentUser().canReadAllAcceleratorDetails()) {
      DSL.trueCondition()
    } else {
      DSL.falseCondition()
    }
  }
}
