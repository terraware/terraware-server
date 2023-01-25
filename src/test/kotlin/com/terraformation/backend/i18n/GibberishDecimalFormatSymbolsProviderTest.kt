package com.terraformation.backend.i18n

import java.text.NumberFormat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GibberishDecimalFormatSymbolsProviderTest {
  @Test
  fun `uses correct punctuation in number formatting`() {
    val formatter = NumberFormat.getNumberInstance(Locales.GIBBERISH)

    val expected = "123&456,789"
    val actual = formatter.format(123456.789)

    assertEquals(expected, actual)
  }
}
