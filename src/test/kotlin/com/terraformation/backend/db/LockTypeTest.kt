package com.terraformation.backend.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class LockTypeTest {
  @Test
  fun `each lock type has a unique numeric ID`() {
    val duplicates = LockType.values().groupBy { it.key }.filter { it.value.size > 1 }
    assertEquals(emptyMap<Long, List<LockType>>(), duplicates, "Lock types with duplicate IDs")
  }
}
