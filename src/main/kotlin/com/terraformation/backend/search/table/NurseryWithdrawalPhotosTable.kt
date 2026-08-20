package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWAL_PHOTOS
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWAL_SUMMARIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.search.field.column
import org.jooq.Field
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL

class NurseryWithdrawalPhotosTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = WITHDRAWAL_PHOTOS.FILE_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          nurseryWithdrawals.asSingleValueSublist(
              "withdrawal",
              WITHDRAWAL_PHOTOS.WITHDRAWAL_ID,
              WITHDRAWAL_SUMMARIES.ID,
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("fileId", WITHDRAWAL_PHOTOS.FILE_ID) { FileId(it) },
          geometryField("gpsCoordinate") { table -> fileField(table, FILES.GEOLOCATION) },
          localDateTimeField("capturedLocalTime") { table ->
            fileField(table, FILES.CAPTURED_LOCAL_TIME)
          },
      )

  /** Returns a column of the photo's row in the files table. */
  private fun <T> fileField(table: Table<*>, field: Field<T?>): Field<T?> =
      DSL.field(
          DSL.select(field).from(FILES).where(FILES.ID.eq(table.column(WITHDRAWAL_PHOTOS.FILE_ID)))
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.nurseryWithdrawals

  override fun <T : Record> joinForVisibility(
      query: SelectJoinStep<T>,
      table: Table<*>,
  ): SelectJoinStep<T> {
    return query
        .join(WITHDRAWAL_SUMMARIES)
        .on(WITHDRAWAL_SUMMARIES.ID.eq(table.column(WITHDRAWAL_PHOTOS.WITHDRAWAL_ID)))
  }
}
