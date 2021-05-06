package com.terraformation.seedbank.jooq

import java.io.File
import java.sql.Connection
import org.jooq.codegen.JavaWriter
import org.jooq.codegen.KotlinGenerator
import org.jooq.meta.SchemaDefinition
import org.jooq.meta.TableDefinition
import org.jooq.meta.jaxb.ForcedType
import org.slf4j.LoggerFactory

class EnumTable(private val tableName: String, includeExpressions: List<String>) {
  // Convert "foo_bar_baz" to "FooBarBaz".
  val enumName = tableName.replace(Regex("_(.)")) { it.groupValues[1].capitalize() }.capitalize()
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

/** Generates enums instead of table objects for a select set of reference tables. */
class EnumGenerator : KotlinGenerator() {
  @Suppress("MemberVisibilityCanBePrivate")  // Referenced by build.gradle.kts
  val enumTables =
      listOf(
          "accession_state" to
              listOf(
                  "accession\\.state_id",
                  ".*\\.accession_state_id",
                  "accession_state_history\\.(old|new)_state_id"),
          "germination_seed_type" to listOf("germination_test\\.seed_type_id"),
          "germination_substrate" to listOf("germination_test\\.substrate_id"),
          "germination_test_type" to
              listOf(
                  "germination_test\\.test_type",
                  "accession_germination_test_type\\.germination_test_type_id"),
          "germination_treatment" to listOf("germination_test\\.treatment_id"),
          "notification_type" to listOf("notification\\.type_id"),
          "processing_method" to listOf("accession\\.processing_method_id"),
          "seed_quantity_units" to listOf(".*\\_units_id"),
          "source_plant_origin" to listOf(".*\\.source_plant_origin_id"),
          "species_endangered_type" to listOf(".*\\.species_endangered_type_id"),
          "species_rare_type" to listOf(".*\\.species_rare_type_id"),
          "storage_condition" to
              listOf("accession\\.target_storage_condition", "storage_location\\.condition_id"),
          "timeseries_type" to listOf("timeseries\\.type_id"),
          "withdrawal_purpose" to listOf("withdrawal\\.purpose_id"))
          .map { EnumTable(it.first, it.second) }

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
    if (enumTables.any { it.toString() == table.name }) {
      throw IllegalArgumentException(
          "${table.name} is generated as an enum and must be excluded from the table list")
    }

    super.generateTable(schema, table)
  }

  fun forcedTypes(targetPackage: String) = enumTables.map { it.forcedType(targetPackage) }
}
