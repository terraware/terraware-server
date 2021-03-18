package com.terraformation.seedbank.jooq

import java.io.File
import java.sql.Connection
import org.jooq.codegen.JavaWriter
import org.jooq.codegen.KotlinGenerator
import org.jooq.meta.SchemaDefinition
import org.jooq.meta.TableDefinition
import org.slf4j.LoggerFactory

/** Generates enums instead of table objects for a select set of reference tables. */
class EnumGenerator : KotlinGenerator() {
  private val enumTables =
      setOf(
          "accession_state",
          "germination_seed_type",
          "germination_substrate",
          "germination_test_type",
          "germination_treatment",
          "notification_type",
          "processing_method",
          "species_endangered_type",
          "species_rare_type",
          "storage_condition",
          "timeseries_type",
          "withdrawal_purpose")

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

    enumTables.forEach { printEnum(out, it, schema.database.connection) }
    closeJavaWriter(out)
  }

  private fun printEnum(out: JavaWriter, tableName: String, connection: Connection) {
    val values = mutableListOf<String>()

    log.info("Generating enum for reference table $tableName")

    connection.prepareStatement("SELECT id, name FROM $tableName ORDER BY id").use { ps ->
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

    // Convert "foo_bar_baz" to "FooBarBaz".
    val enumName = tableName.replace(Regex("_(.)")) { it.groupValues[1].capitalize() }.capitalize()

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
          
          override val tableName get() = "$tableName"

          companion object {
              private val byDisplayName = values().associateBy { it.displayName }
              private val byId = values().associateBy { it.id }
              
              @JsonCreator
              @JvmStatic
              fun forDisplayName(name: String) = byDisplayName[name]
              
              fun forId(id: Int) = byId[id]
          }
      }
      
      class ${enumName}Converter : AbstractConverter<Int, $enumName>(Int::class.java, $enumName::class.java) {
          override fun from(dbValue: Int?) = if (dbValue != null) $enumName.forId(dbValue) else null
          override fun to(enumValue: $enumName?) = enumValue?.id
      }

    """.trimIndent())
  }

  override fun generateTable(schema: SchemaDefinition, table: TableDefinition) {
    if (table.name in enumTables) {
      throw IllegalArgumentException(
          "${table.name} is generated as an enum and must be excluded from the table list")
    }

    super.generateTable(schema, table)
  }
}
