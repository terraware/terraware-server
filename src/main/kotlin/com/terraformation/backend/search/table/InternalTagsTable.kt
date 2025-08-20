package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.InternalTagId
import com.terraformation.backend.db.default_schema.tables.references.INTERNAL_TAGS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_INTERNAL_TAGS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class InternalTagsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = INTERNAL_TAGS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          organizations.asMultiValueSublist(
              "organizationInternalTags",
              INTERNAL_TAGS.ID.eq(ORGANIZATION_INTERNAL_TAGS.INTERNAL_TAG_ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("id", INTERNAL_TAGS.ID) { InternalTagId(it) },
          textField("name", INTERNAL_TAGS.NAME),
      )

  override fun conditionForVisibility(): Condition? {
    return if (currentUser().canReadInternalTags()) {
      DSL.trueCondition()
    } else {
      DSL.falseCondition()
    }
  }
}
