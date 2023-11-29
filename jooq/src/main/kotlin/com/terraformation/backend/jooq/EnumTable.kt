package com.terraformation.backend.jooq

import org.jooq.meta.jaxb.ForcedType

/**
 * column info to be used with enum table additional columns Example:
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
     * We need to identify all the tables with foreign keys that reference the above tableName. This
     * allows the generated code for those tables to replace their raw ID value with our enum.
     */
    includeExpressions: List<String>,
    val enumName: String = tableName.trimEnd('s').toPascalCase(),
    val additionalColumns: List<EnumTableColumnInfo> = emptyList(),
    /**
     * Force generated jOOQ classes to use this type instead of the underlying (integer) type. You
     * will almost always want to leave this set to true. But if an enum is only ever referenced by
     * other enums and we add it as a forced type, jOOQ will complain that the forced type isn't
     * used in any of its generated code, in which case we can use this to suppress the forced type.
     */
    val generateForcedType: Boolean = true,
    /** If true, use the ID instead of the name as the enum's JSON representation. */
    val useIdAsJsonValue: Boolean = false,
    /**
     * If true, this enum can be queried by the search API. Searchable enums need to have the
     * display names of their values listed in the `Enums_en.properties` file so they can be
     * translated to other languages.
     */
    val isLocalizable: Boolean = true,
) {
  val converterName = "${enumName}Converter"
  private val includeExpression = "(?i:" + includeExpressions.joinToString("|") + ")"

  override fun toString() = tableName

  fun forcedType(targetPackage: String): ForcedType? {
    return if (generateForcedType) {
      ForcedType()
          .withUserType("$targetPackage.$enumName")
          .withConverter("$targetPackage.$converterName")
          .withIncludeExpression(includeExpression)
    } else {
      null
    }
  }
}
