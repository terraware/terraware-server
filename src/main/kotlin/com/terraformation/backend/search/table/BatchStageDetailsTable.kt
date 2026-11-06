package com.terraformation.backend.search.table

import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_STAGE_DETAILS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.search.field.column
import java.time.Clock
import org.jooq.DatePart
import org.jooq.Field
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

class BatchStageDetailsTable(private val tables: SearchTables, private val clock: Clock) :
    SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = BATCH_STAGE_DETAILS.BATCH_STAGE_DETAIL_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          batches.asSingleValueSublist(
              "batch",
              BATCH_STAGE_DETAILS.BATCH_ID,
              BATCHES.ID,
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          booleanField("cleared", BATCH_STAGE_DETAILS.TIME_IN_STAGE_CLEARED),
          integerField("days", getDatabaseField = ::daysField),
          integerField("quantity", BATCH_STAGE_DETAILS.QUANTITY),
          enumField("stage", BATCH_STAGE_DETAILS.STAGE_ID),
      )

  override val visibilitySublistName: String
    get() = "batch"

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(BATCH_STAGE_DETAILS.BATCH_ID, BATCH_STAGE_DETAILS.STAGE_ID)

  private fun daysField(table: Table<*>): Field<Int?> {
    val countedQuantity = table.column(BATCH_STAGE_DETAILS.COUNTED_QUANTITY)
    val countedSeedlingDays = table.column(BATCH_STAGE_DETAILS.COUNTED_SEEDLING_DAYS)
    val quantity = table.column(BATCH_STAGE_DETAILS.QUANTITY)
    val timeInStageAsOf = table.column(BATCH_STAGE_DETAILS.TIME_IN_STAGE_AS_OF)
    val timeInStageCleared = table.column(BATCH_STAGE_DETAILS.TIME_IN_STAGE_CLEARED)
    val now = clock.instant().epochSecond.toDouble()
    val average =
        countedSeedlingDays
            .plus(
                quantity
                    .cast(SQLDataType.DOUBLE)
                    .times(
                        DSL.value(now)
                            .minus(
                                DSL.extract(timeInStageAsOf, DatePart.EPOCH)
                                    .cast(SQLDataType.DOUBLE)
                            )
                            .div(86_400.0)
                    )
            )
            .div(countedQuantity.cast(SQLDataType.DOUBLE))

    return DSL.case_()
        .`when`(
            timeInStageCleared.isTrue.or(countedQuantity.eq(0)),
            DSL.inline(null, SQLDataType.INTEGER),
        )
        .`when`(average.lt(1.0), DSL.inline(0))
        .otherwise(DSL.floor(average.plus(0.5)).cast(SQLDataType.INTEGER))
  }
}
