package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.accelerator.tables.references.EVENTS
import com.terraformation.backend.db.accelerator.tables.references.EVENT_PROJECTS
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANTS
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANT_PROJECT_SPECIES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_VARIABLES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_INTERNAL_TAGS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.PROJECT_LAND_USE_MODEL_TYPES
import com.terraformation.backend.db.docprod.tables.references.DOCUMENTS
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.tracking.tables.references.DRAFT_PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class ProjectsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PROJECTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asMultiValueSublist("accessions", PROJECTS.ID.eq(ACCESSIONS.PROJECT_ID)),
          batches.asMultiValueSublist("batches", PROJECTS.ID.eq(BATCHES.PROJECT_ID)),
          countries.asSingleValueSublist("country", PROJECTS.COUNTRY_CODE.eq(COUNTRIES.CODE)),
          documents.asMultiValueSublist("documents", PROJECTS.ID.eq(DOCUMENTS.PROJECT_ID)),
          draftPlantingSites.asMultiValueSublist(
              "draftPlantingSites",
              PROJECTS.ID.eq(DRAFT_PLANTING_SITES.PROJECT_ID),
          ),
          events.asMultiValueSublist("events", eventsCondition),
          organizations.asSingleValueSublist(
              "organization",
              PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID),
          ),
          participants.asSingleValueSublist(
              "participant",
              PROJECTS.PARTICIPANT_ID.eq(PARTICIPANTS.ID),
              isRequired = false,
          ),
          participantProjectSpecies.asMultiValueSublist(
              "participantProjectSpecies",
              PROJECTS.ID.eq(PARTICIPANT_PROJECT_SPECIES.PROJECT_ID),
          ),
          plantingSites.asMultiValueSublist(
              "plantingSites",
              PROJECTS.ID.eq(PLANTING_SITE_SUMMARIES.PROJECT_ID),
          ),
          projectAcceleratorDetails.asSingleValueSublist(
              "acceleratorDetails",
              PROJECTS.ID.eq(PROJECT_ACCELERATOR_DETAILS.PROJECT_ID),
              isRequired = false,
          ),
          projectDeliverables.asMultiValueSublist(
              "projectDeliverables",
              PROJECTS.ID.eq(PROJECT_DELIVERABLES.PROJECT_ID),
          ),
          projectLandUseModelTypes.asMultiValueSublist(
              "landUseModelTypes",
              PROJECTS.ID.eq(PROJECT_LAND_USE_MODEL_TYPES.PROJECT_ID),
          ),
          projectVariables.asMultiValueSublist(
              "variables",
              PROJECTS.ID.eq(PROJECT_VARIABLES.PROJECT_ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField("createdTime", PROJECTS.CREATED_TIME),
          textField("description", PROJECTS.DESCRIPTION),
          idWrapperField("id", PROJECTS.ID) { ProjectId(it) },
          timestampField("modifiedTime", PROJECTS.MODIFIED_TIME),
          textField("name", PROJECTS.NAME),
      )

  override fun conditionForVisibility(): Condition {
    val acceleratorCondition =
        if (currentUser().canReadAllAcceleratorDetails()) {
          DSL.exists(
              DSL.selectOne()
                  .from(ORGANIZATION_INTERNAL_TAGS)
                  .where(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID.eq(PROJECTS.ORGANIZATION_ID))
                  .and(ORGANIZATION_INTERNAL_TAGS.INTERNAL_TAG_ID.eq(InternalTagIds.Accelerator))
          )
        } else {
          null
        }

    return DSL.or(
        listOfNotNull(
            PROJECTS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys),
            acceleratorCondition,
        )
    )
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(PROJECTS.ID)

  private val eventsCondition: Condition =
      DSL.exists(
          DSL.selectOne()
              .from(EVENT_PROJECTS)
              .where(EVENT_PROJECTS.PROJECT_ID.eq(PROJECTS.ID))
              .and(EVENT_PROJECTS.EVENT_ID.eq(EVENTS.ID))
      )
}
