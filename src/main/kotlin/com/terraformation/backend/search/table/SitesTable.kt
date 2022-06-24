package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField

class SitesTable(tables: SearchTables, fuzzySearchOperators: FuzzySearchOperators) :
    SearchTable(fuzzySearchOperators) {
  override val primaryKey: TableField<out Record, out Any?>
    get() = SITES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          facilities.asMultiValueSublist("facilities", SITES.ID.eq(FACILITIES.SITE_ID)),
          projects.asSingleValueSublist("project", SITES.PROJECT_ID.eq(PROJECTS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField("createdTime", "Site created time", SITES.CREATED_TIME, nullable = false),
          textField("description", "Site description", SITES.DESCRIPTION),
          idWrapperField("id", "Site ID", SITES.ID) { SiteId(it) },
          geometryField("location", "Site location", SITES.LOCATION),
          textField("name", "Site name", SITES.NAME, nullable = false),
      )

  override fun conditionForVisibility(): Condition {
    return SITES.PROJECT_ID.`in`(currentUser().projectRoles.keys)
  }
}
