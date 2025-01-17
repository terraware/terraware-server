package com.terraformation.backend.tracking.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ZipProportionallyTest {
  @Test
  fun `does simple round-robin when fraction is half`() {
    assertEquals(
        listOf("F", "S", "F", "S", "F", "S"),
        zipProportionally(6, 0.5, { "F" }, { "S" }),
    )
  }

  @Test
  fun `only pulls from first source when fraction is 1`() {
    assertEquals(
        List(1000) { "F" },
        zipProportionally(1000, 1.0, { "F" }, { "S" }),
    )
  }

  @Test
  fun `only pulls from second source when fraction is 0`() {
    assertEquals(
        List(1000) { "S" },
        zipProportionally(1000, 0.0, { "F" }, { "S" }),
    )
  }

  @Test
  fun `interleaves elements from both sources starting with second source when fraction is less than half`() {
    assertEquals(
        listOf("S", "S", "F", "S", "S", "F", "S"),
        zipProportionally(7, 1.0 / 3.0, { "F" }, { "S" }),
    )
  }

  @Test
  fun `interleaves elements from both sources starting with first source when fraction is greater than half`() {
    assertEquals(
        listOf("F", "F", "F", "S", "F", "F", "F", "S", "F"),
        zipProportionally(9, 0.75, { "F" }, { "S" }),
    )
  }

  @Test
  fun `only picks elements from one source if number of elements is too low for fraction`() {
    assertEquals(
        listOf("F", "F", "F", "F", "F"),
        zipProportionally(5, 0.9, { "F" }, { "S" }),
    )
  }

  @Test
  fun `throws exception if fraction is out of bounds`() {
    assertThrows<IllegalArgumentException>("Less than 0") {
      zipProportionally(10, -0.1, { "F" }, { "S" })
    }
    assertThrows<IllegalArgumentException>("Greater than 1") {
      zipProportionally(10, 1.1, { "F" }, { "S" })
    }
  }

  @Test
  fun `throws exception if element count is negative`() {
    assertThrows<IllegalArgumentException> { zipProportionally(-1, 0.1, { "F" }, { "S" }) }
  }
}
