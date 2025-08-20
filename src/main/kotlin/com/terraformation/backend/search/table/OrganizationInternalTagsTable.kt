package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.tables.references.INTERNAL_TAGS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_INTERNAL_TAGS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class OrganizationInternalTagsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_INTERNAL_TAG_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          organizations.asSingleValueSublist(
              "organization",
              ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID),
          ),
          internalTags.asSingleValueSublist(
              "internalTag",
              ORGANIZATION_INTERNAL_TAGS.INTERNAL_TAG_ID.eq(INTERNAL_TAGS.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> by lazy {
    listOf(
        aliasField("name", "internalTag_name"),
    )
  }

  override fun conditionForVisibility(): Condition {
    return if (currentUser().canReadInternalTags()) {
      DSL.trueCondition()
    } else {
      DSL.falseCondition()
    }
  }
}
