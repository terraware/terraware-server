package com.terraformation.backend.i18n

import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.table.SearchTables
import java.time.Clock
import kotlin.reflect.full.memberProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SearchFieldDescriptionsTest {
  private val messages = Messages()

  @Test
  fun `all search fields have English descriptions`() {
    val tables = SearchTables(Clock.systemUTC())

    val missingFields =
        SearchTables::class
            .memberProperties
            .mapNotNull { it.get(tables) as? SearchTable }
            .flatMap { table ->
              table.fields.mapNotNull { field ->
                try {
                  // This will throw an exception if there is no entry in the strings table
                  // for the field.
                  messages.searchFieldDisplayName(table.name, field.fieldName)
                  null
                } catch (e: Exception) {
                  "search.${table.name}.${field.fieldName}="
                }
              }
            }
            .sorted()
            .joinToString("\n")

    assertEquals("", missingFields, "Missing search field descriptions")
  }
}
