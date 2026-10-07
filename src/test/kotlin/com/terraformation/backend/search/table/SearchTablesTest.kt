package com.terraformation.backend.search.table

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.mockUser
import kotlin.reflect.full.declaredMemberProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull

class SearchTablesTest : RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val searchTables = SearchTables(TestClock())

  @Test
  fun `can look up search table by name`() {
    assertSame(searchTables.bags, searchTables["bags"])
  }

  @Test
  fun `lookup returns null if table name is unknown`() {
    assertNull(searchTables["someBogusTableNameThatDoesNotExist"])
  }

  @Test
  fun `visibility sublists are valid single-value sublists`() {
    val invalidTables =
        SearchTables::class
            .declaredMemberProperties
            .mapNotNull { searchTables[it.name] }
            .filter { table ->
              table.visibilitySublistName != null &&
                  table.getSublistOrNull(table.visibilitySublistName!!)?.isMultiValue != false
            }
            .map { it.name }

    assertEquals(
        emptyList<String>(),
        invalidTables,
        "Tables with invalid or multi-value visibility sublists",
    )
  }
}
