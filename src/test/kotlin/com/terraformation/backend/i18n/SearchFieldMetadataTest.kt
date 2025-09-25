package com.terraformation.backend.i18n

import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.search.table.SearchTables
import java.time.Clock
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SearchFieldMetadataTest {
  private val messages = Messages()

  @Test
  fun `all search fields have English descriptions`() {
    // Generate a string with one line per missing field, suitable for copy-pasting into
    // Messages_en.properties.
    val missingFields =
        mapAllSearchFields { table, field ->
              try {
                // This will throw an exception if there is no entry in the strings table
                // for the field.
                messages.searchFieldDisplayName(table.name, field.fieldName)
                null
              } catch (e: Exception) {
                "search.${table.name}.${field.fieldName}="
              }
            }
            .sorted()
            .joinToString("\n", "\n", "\n")

    assertEquals("\n\n", missingFields, "Missing search field descriptions")
  }

  @Test
  fun `search field names are valid`() {
    val invalidFields = mapAllSearchFields { table, field ->
      val fieldName = field.fieldName
      val tableName = table.name
      if ('.' in fieldName || '_' in fieldName) {
        "$fieldName in $tableName will be interpreted as a sublist reference; use camelCase instead of . or _"
      } else if (fieldName[0].isUpperCase()) {
        "$fieldName in $tableName should not be capitalized; use camelCase"
      } else if (fieldName.isBlank()) {
        "$tableName has a field with a blank name"
      } else {
        null
      }
    }

    assertEquals(emptyList<String>(), invalidFields, "Found invalid field names")
  }

  @Test
  fun `search field names are unique`() {
    val duplicateFields =
        mapAllSearchFields { table, field -> "${field.fieldName} in ${table.name}" }
            .groupBy { it }
            .mapValues { it.value.size }
            .filterValues { it > 1 }
            .keys

    assertSetEquals(emptySet<String>(), duplicateFields, "Found duplicate field names")
  }

  private fun <T> mapAllSearchFields(func: (SearchTable, SearchField) -> T?): List<T> {
    val tables = SearchTables(Clock.systemUTC())

    return SearchTables::class
        .memberProperties
        .filter { it.visibility == KVisibility.PUBLIC }
        .mapNotNull { it.get(tables) as? SearchTable }
        .flatMap { table -> table.fields.mapNotNull { func(table, it) } }
  }
}
