package com.terraformation.backend.search.table

import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.tables.references.DOCUMENT_TEMPLATES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.TableField

class DocumentTemplatesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = DOCUMENT_TEMPLATES.ID

  override val sublists: List<SublistField> = emptyList()

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("id", DOCUMENT_TEMPLATES.ID) { DocumentTemplateId(it) },
          textField("name", DOCUMENT_TEMPLATES.NAME),
      )
}
