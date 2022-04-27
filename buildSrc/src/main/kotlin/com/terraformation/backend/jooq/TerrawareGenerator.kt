package com.terraformation.backend.jooq

import java.io.File
import java.sql.Connection
import org.jooq.codegen.JavaWriter
import org.jooq.codegen.KotlinGenerator
import org.jooq.meta.SchemaDefinition
import org.jooq.meta.TableDefinition
import org.jooq.meta.jaxb.ForcedType
import org.slf4j.LoggerFactory

/**
 * Generates custom database classes to supplement or replace the standard jOOQ ones.
 *
 * - Enums instead of table objects for a select set of reference tables.
 * - Type-safe value classes instead of plain Int/Long for single-column primary keys.
 */
class TerrawareGenerator : KotlinGenerator() {
  private val log = LoggerFactory.getLogger(javaClass)

  override fun generateSchema(schema: SchemaDefinition) {
    super.generateSchema(schema)

    log.debug("Generating reference table enums")
    val out = newJavaWriter(File(getFile(schema).parentFile, "ReferenceTableEnums.kt"))

    printPackage(out, schema)
    out.printImports()
    out.println(
        """
      import com.fasterxml.jackson.annotation.JsonCreator
      import com.fasterxml.jackson.annotation.JsonValue
      import org.jooq.impl.AbstractConverter
      
      interface EnumFromReferenceTable<T : Enum<T>> {
        val id: Int
        val displayName: String
        val tableName: String
      }

    """.trimIndent(),
    )

    ENUM_TABLES.forEach { printEnum(out, it, schema.database.connection) }
    ID_WRAPPERS.forEach { it.render(out) }

    closeJavaWriter(out)
  }

  private fun printEnum(out: JavaWriter, table: EnumTable, connection: Connection) {
    val values = mutableListOf<String>()

    log.info("Generating enum for reference table $table")

    val columns = (listOf("id", "name") + table.additionalColumns.map { it.columnName }).joinToString()
    connection.prepareStatement("SELECT $columns FROM $table ORDER BY id").use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val id = rs.getInt(1)
          val name = rs.getString(2)
          if (name != null) {
            val capitalizedName = name.replace(Regex("[-/ ]"), "").capitalize()
            val properties = (listOf("\"$name\"") + table.additionalColumns.mapIndexed { i, it ->
              val obj = rs.getObject(2 + i + 1)
              when (obj) {
                is String -> "\"$obj\""
                else -> if (it.isTableEnum) "${it.columnDataType}.forId($obj)!!" else "$obj"
              }
            }).joinToString()
            values.add("$capitalizedName($id, $properties)")
          }
        }
      }
    }

    val enumName = table.enumName
    val converterName = table.converterName

    // Turn the list of values into a properly indented comma-delimited list. The indentation level
    // here needs to take the trimIndent() call into account.
    val valuesCodeSnippet = values.joinToString(",\n          ")

    // https://youtrack.jetbrains.com/issue/KT-2425
    val dollarSign = '$'

    val additionalProperties = table.additionalColumns.map {
      val propertyName = it.columnName.toCamelCase()
      val propertyType = it.columnDataType
      "val $propertyName: $propertyType"
    }
    val properties = (listOf(
        "override val id: Int",
        "@get:JsonValue override val displayName: String",
    ) + additionalProperties).joinToString(separator = ",\n          ")

    out.println(
        """
      enum class $enumName(
          $properties
      ) : EnumFromReferenceTable<$enumName> {
          $valuesCodeSnippet;
          
          override val tableName get() = "$table"

          companion object {
              private val byDisplayName = values().associateBy { it.displayName }
              private val byId = values().associateBy { it.id }
              
              @JsonCreator
              @JvmStatic
              fun forDisplayName(name: String) = byDisplayName[name]
                  ?: throw IllegalArgumentException("Unrecognized value: ${dollarSign}name")
              
              fun forId(id: Int) = byId[id]
          }
      }
      
      class $converterName : AbstractConverter<Int, $enumName>(Int::class.java, $enumName::class.java) {
          override fun from(dbValue: Int?) = if (dbValue != null) $enumName.forId(dbValue) else null
          override fun to(enumValue: $enumName?) = enumValue?.id
      }

    """.trimIndent(),
    )
  }

  override fun generateTable(schema: SchemaDefinition, table: TableDefinition) {
    if (ENUM_TABLES.any { it.toString() == table.name }) {
      throw IllegalArgumentException(
          "${table.name} is generated as an enum and must be excluded from the table list",
      )
    }

    super.generateTable(schema, table)
  }

  fun forcedTypes(targetPackage: String): List<ForcedType> {
    val types =
        mutableListOf(
            ForcedType()
                .withName("INSTANT")
                .withIncludeTypes("(?i:TIMESTAMP\\ WITH\\ TIME\\ ZONE)"),
            ForcedType()
                .withBinding("com.terraformation.backend.db.GeometryBinding")
                .withIncludeTypes("GEOMETRY")
                .withUserType("net.postgis.jdbc.geometry.Geometry"),
            ForcedType()
                .withIncludeExpression("(?i:.*_ur[li])")
                .withConverter("com.terraformation.backend.db.UriConverter")
                .withUserType("java.net.URI"),
        )

    ENUM_TABLES.forEach { types.add(it.forcedType(targetPackage)) }
    ID_WRAPPERS.forEach { types.add(it.forcedType(targetPackage)) }

    return types
  }

  fun embeddables() = EMBEDDABLES

  fun excludes() = ENUM_TABLES.joinToString("|") { "$it\$" }
}
