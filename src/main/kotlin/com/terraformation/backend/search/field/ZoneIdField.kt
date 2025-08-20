package com.terraformation.backend.search.field

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.time.ZoneId
import java.util.EnumSet
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

/** Search field for columns that hold time zone identifiers. */
class ZoneIdField(
    override val fieldName: String,
    override val databaseField: TableField<*, ZoneId?>,
    override val table: SearchTable,
) : SingleColumnSearchField<ZoneId>() {
  private val validZoneNames = ZoneId.getAvailableZoneIds()

  override val localize: Boolean
    get() = false

  override val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.of(SearchFilterType.Exact)

  override val possibleValues = validZoneNames.toList()

  override fun getCondition(fieldNode: FieldNode): Condition {
    if (fieldNode.type != SearchFilterType.Exact) {
      throw IllegalArgumentException("$fieldName only supports exact searches")
    }

    val zoneIds =
        fieldNode.values.filter { it != null && it in validZoneNames }.map { ZoneId.of(it) }

    return DSL.or(
        listOfNotNull(
            if (zoneIds.isNotEmpty()) databaseField.`in`(zoneIds) else null,
            if (fieldNode.values.any { it == null }) databaseField.isNull else null,
        )
    )
  }

  override fun computeValue(record: Record) = record[databaseField]?.id

  // Zone IDs are always machine-readable.
  override fun raw(): SearchField? = null
}
