package com.terraformation.backend.i18n

import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.table.SearchTables
import java.time.Clock
import kotlin.reflect.full.memberProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

class SearchFieldDescriptionsTest {
  private val messages = Messages()

  @Test
  fun `all search fields have English descriptions`() {
    val tables = SearchTables(Clock.systemUTC())

    assertAll(
        SearchTables::class
            .memberProperties
            .map { it.get(tables) as SearchTable }
            .flatMap { table ->
              table.fields.map { field ->
                ({
                  // This will throw an exception if there is no entry in the strings table
                  // for the field.
                  messages.searchFieldDisplayName(table.name, field.fieldName)
                })
              }
            })
  }
}
