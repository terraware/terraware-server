package com.terraformation.seedbank.db

import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.services.toInstant
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
class FetcherSupport(
    private val config: TerrawareServerConfig,
    private val dslContext: DSLContext
) {

  fun getOrInsertId(
      name: String?,
      idField: TableField<*, Long?>,
      nameField: TableField<*, String?>,
      siteModuleIdField: TableField<*, Long?>? = null,
      extraSetters: (InsertSetMoreStep<out Record>) -> Unit = {}
  ): Long? {
    if (name == null) {
      return null
    }

    val existingId =
        dslContext
            .select(idField)
            .from(idField.table)
            .where(nameField.eq(name))
            .apply { if (siteModuleIdField != null) and(siteModuleIdField.eq(config.siteModuleId)) }
            .fetchOne(idField)
    if (existingId != null) {
      return existingId
    }

    val table = idField.table!!

    return dslContext
        .insertInto(table)
        .set(nameField, name)
        .apply { if (siteModuleIdField != null) set(siteModuleIdField, config.siteModuleId) }
        .apply { extraSetters(this) }
        .returning(idField)
        .fetchOne()
        ?.get(idField)
        ?: throw DataAccessException("Unable to insert new ${table.name.toLowerCase()} $name")
  }

  fun getId(
      name: String?,
      idField: TableField<*, Long?>,
      nameField: TableField<*, String?>,
      siteModuleIdField: TableField<*, Long?>? = null
  ): Long? {
    if (name == null) {
      return null
    }

    return dslContext
        .select(idField)
        .from(idField.table)
        .where(nameField.eq(name))
        .apply { if (siteModuleIdField != null) and(siteModuleIdField.eq(config.siteModuleId)) }
        .fetchOne(idField)
        ?: throw IllegalArgumentException(
            "Unable to find ${idField.table?.name?.toLowerCase()} $name")
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
