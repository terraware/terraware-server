package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.DocumentSavedVersionId
import com.terraformation.backend.db.docprod.tables.references.DOCUMENTS
import com.terraformation.backend.db.docprod.tables.references.DOCUMENT_SAVED_VERSIONS
import com.terraformation.backend.db.docprod.tables.references.DOCUMENT_TEMPLATES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class DocumentsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = DOCUMENTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          projects.asSingleValueSublist("project", PROJECTS.ID.eq(DOCUMENTS.PROJECT_ID)),
          documentTemplates.asSingleValueSublist(
              "documentTemplate",
              DOCUMENT_TEMPLATES.ID.eq(DOCUMENTS.DOCUMENT_TEMPLATE_ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField("createdTime", DOCUMENTS.CREATED_TIME),
          idWrapperField("id", DOCUMENTS.ID) { DocumentId(it) },
          idWrapperField(
              "lastSavedVersionId",
              with(DOCUMENT_SAVED_VERSIONS) {
                DSL.field(
                    DSL.select(ID)
                        .from(DOCUMENT_SAVED_VERSIONS)
                        .where(DOCUMENT_ID.eq(DOCUMENTS.ID))
                        .orderBy(ID.desc())
                        .limit(1)
                )
              },
          ) {
            DocumentSavedVersionId(it)
          },
          timestampField("modifiedTime", DOCUMENTS.MODIFIED_TIME),
          textField("name", DOCUMENTS.NAME),
          idWrapperField("projectId", DOCUMENTS.PROJECT_ID) { ProjectId(it) },
          enumField("status", DOCUMENTS.STATUS_ID),
      )

  override fun conditionForVisibility(): Condition =
      if (currentUser().canManageDocumentProducer()) {
        DSL.trueCondition()
      } else {
        DSL.falseCondition()
      }

  override val defaultOrderFields: List<OrderField<*>> = listOf(DOCUMENTS.NAME)
}
