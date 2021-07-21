package com.terraformation.backend.jooq

import org.jooq.meta.jaxb.ForcedType

class EnumTable(
    private val tableName: String,
    includeExpressions: List<String>,
    val enumName: String = tableName.trimEnd('s').toPascalCase()
) {
  constructor(
      tableName: String,
      includeExpression: String
  ) : this(tableName, listOf(includeExpression))

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