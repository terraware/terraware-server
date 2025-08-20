package com.terraformation.backend.accelerator.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CarbonCertificationTest {
  @Test
  fun `forDisplayName works correctly`() {
    assertEquals(
        CarbonCertification.CcbVerraStandard,
        CarbonCertification.forDisplayName("CCB Standard"),
    )
  }

  @Test
  fun `forDisplayName throws exception when not found`() {
    assertThrows<IllegalArgumentException> { CarbonCertification.forDisplayName("Unknown") }
  }
}
