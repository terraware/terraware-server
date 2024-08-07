package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.tables.references.DOCUMENTS
import com.terraformation.backend.db.docprod.tables.references.DOCUMENT_SAVED_VERSIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

class DocumentsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = DOCUMENTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          projects.asMultiValueSublist(
              "projects", PROJECTS.ID.eq(DOCUMENTS.PROJECT_ID)))
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField("createdTime", DOCUMENTS.CREATED_TIME, nullable = false),
          idWrapperField("documentTemplateId", DOCUMENTS.DOCUMENT_TEMPLATE_ID) { DocumentTemplateId(it) },
          idWrapperField("id", DOCUMENTS.ID) { DocumentId(it) },
          integerField(
              "lastSavedVersionId",
              DSL.field(
                  DSL.select(DSL.max(DOCUMENTS.ID).cast(SQLDataType.INTEGER))
                      .from(DOCUMENT_SAVED_VERSIONS)
                      .where(DOCUMENT_SAVED_VERSIONS.DOCUMENT_ID.eq(DOCUMENTS.ID)))),
          timestampField("modifiedTime", DOCUMENTS.MODIFIED_TIME, nullable = false),
          textField("name", DOCUMENTS.NAME, nullable = false),
          idWrapperField("projectId", DOCUMENTS.PROJECT_ID) { ProjectId(it) },
          enumField("status", DOCUMENTS.STATUS_ID, nullable = false)
      )

  override fun conditionForVisibility(): Condition =
    if (currentUser().canManageDocumentProducer()) {
      DSL.trueCondition()
    } else {
      DSL.falseCondition()
    }

  override val defaultOrderFields: List<OrderField<*>> = listOf(DOCUMENTS.NAME)
}
