package com.terraformation.backend.seedbank.model

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class WithdrawalModelTest {
  @Nested
  inner class V1ToV2Conversion {
    @Test
    fun `staff responsible is added to notes`() {
      fun model(notes: String?, staffResponsible: String?) =
          WithdrawalModel(
              date = LocalDate.EPOCH, notes = notes, staffResponsible = staffResponsible)

      val testCases: List<Pair<WithdrawalModel, String?>> =
          listOf(
              model(null, null) to null,
              model(" ", "  ") to " ",
              model("existing notes", null) to "existing notes",
              model("existing notes", " ") to "existing notes",
              model("existing notes with staff name", "staff name") to
                  "existing notes with staff name",
              model(null, "staff name") to "Staff responsible: staff name",
              model("existing notes", "staff name") to
                  "existing notes\n\nStaff responsible: staff name",
          )

      val actual = testCases.map { it.first to it.first.toV2Compatible().notes }
      assertEquals(testCases, actual)
    }
  }
}
