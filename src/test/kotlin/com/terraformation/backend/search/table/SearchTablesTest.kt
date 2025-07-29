package com.terraformation.backend.search.table

import com.terraformation.backend.TestClock
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull

class SearchTablesTest {
  private val searchTables = SearchTables(TestClock())

  @Test
  fun `can look up search table by name`() {
    assertSame(searchTables.bags, searchTables["bags"])
  }

  @Test
  fun `lookup returns null if table name is unknown`() {
    assertNull(searchTables["someBogusTableNameThatDoesNotExist"])
  }
}
