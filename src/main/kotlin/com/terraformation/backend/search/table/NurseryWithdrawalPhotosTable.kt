package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWAL_PHOTOS
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWAL_SUMMARIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class NurseryWithdrawalPhotosTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = WITHDRAWAL_PHOTOS.FILE_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          nurseryWithdrawals.asSingleValueSublist(
              "withdrawal",
              WITHDRAWAL_PHOTOS.WITHDRAWAL_ID.eq(WITHDRAWAL_SUMMARIES.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("fileId", WITHDRAWAL_PHOTOS.FILE_ID) { FileId(it) },
          geometryField("gpsCoordinate", WITHDRAWAL_PHOTOS.files.GEOLOCATION),
          localTimestampField("capturedLocalTime", WITHDRAWAL_PHOTOS.files.CAPTURED_LOCAL_TIME),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.nurseryWithdrawals

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(WITHDRAWAL_SUMMARIES)
        .on(WITHDRAWAL_SUMMARIES.ID.eq(WITHDRAWAL_PHOTOS.WITHDRAWAL_ID))
  }
}
