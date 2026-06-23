package com.terraformation.backend.seedbank.model

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AccessionModelTest {
  private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

  @Test
  fun `collectedDate is the UTC date of collectedTime`() {
    val model =
        AccessionModel(
            clock = clock,
            collectedTime = LocalDate.of(2022, 3, 4).atStartOfDay(ZoneOffset.UTC).toInstant(),
        )

    assertEquals(LocalDate.of(2022, 3, 4), model.collectedDate)
  }

  @Test
  fun `collectedDate is null when collectedTime is null`() {
    val model = AccessionModel(clock = clock, collectedTime = null)

    assertNull(model.collectedDate)
  }
}
