package com.terraformation.backend.jooq

import org.jooq.meta.jaxb.ForcedType

/**
 * column info to be used with enum table additional columns
 * Example:
 * EnumTableColumnInfo("notification_criticality_id", "NotificationCriticality", true)
 * EnumTableColumnInfo("test_id", "Int")
 */
data class EnumTableColumnInfo(
  val columnName: String,
  val columnDataType: String,
  val isTableEnum: Boolean = false,
)

/** Maps reference database tables into enums so that can get strong typing. */
class EnumTable(
    private val tableName: String,
    /**
     * We need to identify all the tables with foreign keys that reference the above tableName.
     * This allows the generated code for those tables to replace their raw ID value with our enum.
     */
    includeExpressions: List<String>,
    val enumName: String = tableName.trimEnd('s').toPascalCase(),
    val additionalColumns: List<EnumTableColumnInfo> = emptyList()
) {
  constructor(
      tableName: String,
      includeExpression: String,
  ) : this(tableName, listOf(includeExpression))

  constructor(
    tableName: String,
    includeExpression: String,
    additionalColumns: List<EnumTableColumnInfo>
  ) : this(tableName, listOf(includeExpression), additionalColumns = additionalColumns)

  val converterName = "${enumName}Converter"
  private val includeExpression = "(?i:" + includeExpressions.joinToString("|") + ")"

  override fun toString() = tableName

  fun forcedType(targetPackage: String): ForcedType {
    return ForcedType()
        .withUserType("$targetPackage.$enumName")
        .withConverter("$targetPackage.$converterName")
        .withIncludeTypes("INTEGER")
        .withIncludeExpression(includeExpression)
  }
}
