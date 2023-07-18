package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.nursery.tables.references.BATCH_SUMMARIES
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField

class ProjectsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PROJECTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asMultiValueSublist("accessions", PROJECTS.ID.eq(ACCESSIONS.PROJECT_ID)),
          batches.asMultiValueSublist("batches", PROJECTS.ID.eq(BATCH_SUMMARIES.PROJECT_ID)),
          organizations.asSingleValueSublist(
              "organization", PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)),
          plantingSites.asMultiValueSublist(
              "plantingSites", PROJECTS.ID.eq(PLANTING_SITE_SUMMARIES.PROJECT_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField("createdTime", PROJECTS.CREATED_TIME, nullable = false),
          textField("description", PROJECTS.DESCRIPTION),
          idWrapperField("id", PROJECTS.ID) { ProjectId(it) },
          timestampField("modifiedTime", PROJECTS.MODIFIED_TIME, nullable = false),
          textField("name", PROJECTS.NAME, nullable = false),
      )

  override fun conditionForVisibility(): Condition {
    return PROJECTS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)
  }

  override fun conditionForOrganization(organizationId: OrganizationId): Condition {
    return PROJECTS.ORGANIZATION_ID.eq(organizationId)
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(PROJECTS.ID)
}
