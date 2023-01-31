package com.terraformation.backend.jooq

import java.io.File
import java.sql.Connection
import java.util.Base64
import org.jooq.codegen.JavaWriter
import org.jooq.codegen.KotlinGenerator
import org.jooq.meta.SchemaDefinition
import org.jooq.meta.TableDefinition
import org.jooq.meta.jaxb.ForcedType
import org.slf4j.LoggerFactory

/**
 * Generates custom database classes to supplement or replace the standard jOOQ ones.
 * - Enums instead of table objects for a select set of reference tables.
 * - Type-safe value classes instead of plain Int/Long for single-column primary keys.
 */
class TerrawareGenerator : KotlinGenerator() {
  private val log = LoggerFactory.getLogger(javaClass)

  override fun generateSchema(schema: SchemaDefinition) {
    super.generateSchema(schema)

    log.debug("Generating reference table enums")
    val out = newJavaWriter(File(getFile(schema).parentFile, "ReferenceTableEnums.kt"))

    val english = SortedPropertiesFile(getFile(schema), "Enums")
    val gibberish = SortedPropertiesFile(getFile(schema), "Enums_gx")

    printPackage(out, schema)
    out.printImports()
    out.println(
        """
      import com.fasterxml.jackson.annotation.JsonCreator
      import com.fasterxml.jackson.annotation.JsonValue
      import com.terraformation.backend.db.EnumFromReferenceTable
      import com.terraformation.backend.i18n.currentLocale
      import java.util.Locale
      import java.util.concurrent.ConcurrentHashMap
      import org.jooq.impl.AbstractConverter

    """
            .trimIndent())

    ENUM_TABLES[schema.name]?.forEach {
      printEnum(out, schema.name, it, schema.database.connection, english, gibberish)
    }
    ID_WRAPPERS[schema.name]?.forEach { it.render(out) }

    closeJavaWriter(out)
    english.save()
    gibberish.save()

    generateCountryProperties(schema, "countries", "Countries")
    generateCountryProperties(schema, "country_subdivisions", "CountrySubdivisions")
  }

  private fun generateCountryProperties(
      schema: SchemaDefinition,
      tableName: String,
      baseName: String
  ) {
    if (!schema.isDefaultSchema) {
      return
    }

    val english = SortedPropertiesFile(getFile(schema), baseName)
    val gibberish = SortedPropertiesFile(getFile(schema), "${baseName}_gx")

    schema.database.connection.prepareStatement("SELECT code, name FROM $tableName").use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val code = rs.getString(1)
          val englishName = rs.getString(2)

          english[code] = englishName
          gibberish[code] = englishName.toGibberish()
        }
      }
    }

    english.save()
    gibberish.save()
  }

  private fun printEnum(
      out: JavaWriter,
      schemaName: String,
      table: EnumTable,
      connection: Connection,
      english: SortedPropertiesFile,
      gibberish: SortedPropertiesFile,
  ) {
    val enumName = table.enumName
    val propertyPrefix = "$schemaName.$enumName."
    val values = mutableListOf<String>()

    log.info("Generating enum for reference table $table")

    english.deleteAllWithPrefix(propertyPrefix)
    gibberish.deleteAllWithPrefix(propertyPrefix)

    val columns =
        (listOf("id", "name") + table.additionalColumns.map { it.columnName }).joinToString()
    connection.prepareStatement("SELECT $columns FROM $schemaName.$table ORDER BY id").use { ps ->
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          val id = rs.getInt(1)
          val name = rs.getString(2)
          if (name != null) {
            // Capitalize each word of multi-word values and concatenate them without spaces or
            // punctuation.
            val capitalizedName =
                name.split(Regex("[-,/ ]")).joinToString("") { word -> word.capitalize() }
            val properties =
                (listOf("\"$name\"") +
                        table.additionalColumns.mapIndexed { i, columnInfo ->
                          val obj = rs.getObject(2 + i + 1)
                          when {
                            obj is String -> "\"$obj\""
                            columnInfo.isTableEnum -> "${columnInfo.columnDataType}.forId($obj)!!"
                            else -> "$obj"
                          }
                        })
                    .joinToString()
            values.add("$capitalizedName($id, $properties)")

            val propertyName = "$propertyPrefix$capitalizedName"
            english[propertyName] = name
            gibberish[propertyName] = name.toGibberish()
          }
        }
      }
    }

    val converterName = table.converterName

    // Turn the list of values into a properly indented comma-delimited list. The indentation level
    // here needs to take the trimIndent() call into account.
    val valuesCodeSnippet = values.joinToString(",\n          ")

    // https://youtrack.jetbrains.com/issue/KT-2425
    val dollarSign = '$'

    val additionalProperties =
        table.additionalColumns.map {
          val propertyName = it.columnName.toCamelCase()
          val propertyType = it.columnDataType
          "val $propertyName: $propertyType"
        }
    val properties =
        (listOf(
                "override val id: Int",
                "@get:JsonValue override val displayName: String",
            ) + additionalProperties)
            .joinToString(separator = ",\n          ")

    out.println(
        """
      enum class $enumName(
          $properties
      ) : EnumFromReferenceTable<$enumName> {
          $valuesCodeSnippet;
          
          override val tableName get() = "$table"
          
          override fun getDisplayName(locale: Locale?): String {
            val effectiveLocale = locale ?: Locale.ENGLISH
            val namesForLocale = displayNames.getOrPut(effectiveLocale) {
              EnumFromReferenceTable.loadLocalizedDisplayNames(effectiveLocale, $enumName.values())
            }
            return namesForLocale[this]
                ?: throw IllegalStateException("No display name for $enumName.${dollarSign}this in ${dollarSign}effectiveLocale")
          }
          
          companion object {
              private val displayNames = ConcurrentHashMap<Locale, Map<$enumName, String>>()
              private val byLocalizedName = ConcurrentHashMap<Locale, Map<String, $enumName>>()
              private val byId = values().associateBy { it.id }
              
              fun forDisplayName(name: String, locale: Locale): $enumName {
                val valuesForLocale = byLocalizedName.getOrPut(locale) {
                  $enumName.values().associateBy { it.getDisplayName(locale) }
                }
              
                return valuesForLocale[name]
                    ?: throw IllegalArgumentException("Unrecognized value: ${dollarSign}name")
              }
              
              @JsonCreator
              @JvmStatic
              fun forDisplayName(name: String) = forDisplayName(name, Locale.ENGLISH)
              
              fun forId(id: Int) = byId[id]
          }
      }
      
      class $converterName : AbstractConverter<Int, $enumName>(Int::class.java, $enumName::class.java) {
          override fun from(dbValue: Int?) = if (dbValue != null) $enumName.forId(dbValue) else null
          override fun to(enumValue: $enumName?) = enumValue?.id
      }

    """
            .trimIndent())
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
                .withIncludeTypes("(?i:TIMESTAMP\\ WITH\\ TIME\\ ZONE)"),
            ForcedType()
                .withBinding("com.terraformation.backend.db.GeometryBinding")
                .withIncludeTypes("GEOMETRY")
                .withUserType("org.locationtech.jts.geom.Geometry"),
            ForcedType()
                .withIncludeExpression("(?i:.*_ur[li])")
                .withConverter("com.terraformation.backend.db.UriConverter")
                .withUserType("java.net.URI"),
            ForcedType()
                .withIncludeExpression("time_zone")
                .withConverter("com.terraformation.backend.db.TimeZoneConverter")
                .withUserType("java.time.ZoneId"),
            ForcedType()
                .withIncludeExpression("locale")
                .withConverter("com.terraformation.backend.db.LocaleConverter")
                .withUserType("java.util.Locale"),
        )

    ENUM_TABLES.forEach { (schemaName, tables) ->
      tables
          .mapNotNull { it.forcedType(schemaPackage(targetPackage, schemaName)) }
          .forEach { types.add(it) }
    }
    ID_WRAPPERS.forEach { (schemaName, wrappers) ->
      wrappers
          .map { it.forcedType(schemaPackage(targetPackage, schemaName)) }
          .forEach { types.add(it) }
    }

    return types
  }

  fun embeddables() = EMBEDDABLES

  fun excludes() =
      ENUM_TABLES.entries.joinToString("|") { (schemaName, tables) ->
        tables.joinToString { "$schemaName\\.$it\$" }
      }

  private fun schemaPackage(targetPackage: String, schemaName: String): String {
    return if (schemaName == "public") {
      "$targetPackage.default_schema"
    } else {
      "$targetPackage.$schemaName"
    }
  }

  private fun String.toGibberish(): String {
    return split(' ').asReversed().joinToString(" ") { word ->
      if (word.startsWith('{')) {
        word
      } else {
        val bytes = word.toByteArray()
        Base64.getEncoder().encodeToString(bytes).trimEnd('=')
      }
    }
  }
}
