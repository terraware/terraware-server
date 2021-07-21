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

    """.trimIndent())

    ENUM_TABLES.forEach { printEnum(out, it, schema.database.connection) }
    ID_WRAPPERS.forEach { it.render(out) }

    closeJavaWriter(out)
  }

  private fun printEnum(out: JavaWriter, table: EnumTable, connection: Connection) {
    val values = mutableListOf<String>()

    log.info("Generating enum for reference table $table")

    connection.prepareStatement("SELECT id, name FROM $table ORDER BY id").use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val id = rs.getInt(1)
          val name = rs.getString(2)
          if (name != null) {
            val capitalizedName = name.replace(" ", "").capitalize()
            values.add("$capitalizedName($id, \"$name\")")
          }
        }
      }
    }

    val enumName = table.enumName
    val converterName = table.converterName

    // Turn the list of values into a properly indented comma-delimited list. The indentation level
    // here needs to take the trimIndent() call into account.
    val valuesCodeSnippet = values.joinToString(",\n          ")

    out.println(
        """
      enum class $enumName(
          override val id: Int,
          @get:JsonValue override val displayName: String
      ) : EnumFromReferenceTable<$enumName> {
          $valuesCodeSnippet;
          
          override val tableName get() = "$table"

          companion object {
              private val byDisplayName = values().associateBy { it.displayName }
              private val byId = values().associateBy { it.id }
              
              @JsonCreator
              @JvmStatic
              fun forDisplayName(name: String) = byDisplayName[name]
              
              fun forId(id: Int) = byId[id]
          }
      }
      
      class $converterName : AbstractConverter<Int, $enumName>(Int::class.java, $enumName::class.java) {
          override fun from(dbValue: Int?) = if (dbValue != null) $enumName.forId(dbValue) else null
          override fun to(enumValue: $enumName?) = enumValue?.id
      }

    """.trimIndent())
  }

  override fun generateTable(schema: SchemaDefinition, table: TableDefinition) {
    if (ENUM_TABLES.any { it.toString() == table.name }) {
      throw IllegalArgumentException(
          "${table.name} is generated as an enum and must be excluded from the table list")
    }

    super.generateTable(schema, table)
  }

  fun forcedTypes(targetPackage: String): List<ForcedType> {
    val types =
        mutableListOf(
            ForcedType()
                .withName("INSTANT")
                .withIncludeTypes("(?i:TIMESTAMP\\ WITH\\ TIME\\ ZONE)"))

    ENUM_TABLES.forEach { types.add(it.forcedType(targetPackage)) }
    ID_WRAPPERS.forEach { types.add(it.forcedType(targetPackage)) }

    return types
  }

  fun excludes() = ENUM_TABLES.joinToString("|") { "$it\$" }
}
