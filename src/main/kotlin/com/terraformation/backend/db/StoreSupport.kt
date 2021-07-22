package com.terraformation.backend.db

import com.terraformation.backend.time.toInstant
import java.time.Instant
import java.time.temporal.TemporalAccessor
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.InsertSetMoreStep
import org.jooq.Record
import org.jooq.TableField
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL

@ManagedBean
class StoreSupport(private val dslContext: DSLContext) {

  fun <T : Any> getOrInsertId(
      name: String?,
      idField: TableField<*, T?>,
      nameField: TableField<*, String?>,
      facilityIdField: TableField<*, FacilityId?>? = null,
      facilityId: FacilityId? = null,
      extraSetters: (InsertSetMoreStep<out Record>) -> Unit = {}
  ): T? {
    if (name == null) {
      return null
    }

    val existingId =
        dslContext
            .select(idField)
            .from(idField.table)
            .where(nameField.eq(name))
            .apply { if (facilityIdField != null) and(facilityIdField.eq(facilityId)) }
            .fetchOne(idField)
    if (existingId != null) {
      return existingId
    }

    val table = idField.table!!

    return dslContext
        .insertInto(table)
        .set(nameField, name)
        .apply { if (facilityIdField != null) set(facilityIdField, facilityId) }
        .apply { extraSetters(this) }
        .returning(idField)
        .fetchOne()
        ?.get(idField)
        ?: throw DataAccessException("Unable to insert new ${table.name.lowercase()} $name")
  }

  fun <T> getId(
      name: String?,
      idField: TableField<*, T?>,
      nameField: TableField<*, String?>,
      facilityIdField: TableField<*, FacilityId?>? = null,
      facilityId: FacilityId? = null,
  ): T? {
    if (name == null) {
      return null
    }

    return dslContext
        .select(idField)
        .from(idField.table)
        .where(nameField.eq(name))
        .apply { if (facilityIdField != null) and(facilityIdField.eq(facilityId)) }
        .fetchOne(idField)
        ?: throw IllegalArgumentException(
            "Unable to find ${idField.table?.name?.lowercase()} $name")
  }

  fun countEarlierThan(until: TemporalAccessor, timeField: TableField<*, Instant?>): Int {
    return dslContext
        .select(DSL.count())
        .from(timeField.table)
        .where(timeField.le(until.toInstant()))
        .fetchOne()
        ?.value1()
        ?: 0
  }
}
