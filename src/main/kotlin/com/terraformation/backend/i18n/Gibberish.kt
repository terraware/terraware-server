package com.terraformation.backend.i18n

import java.text.DecimalFormatSymbols
import java.text.spi.DecimalFormatSymbolsProvider
import java.util.*

/**
 * Converts an English string to gibberish for localization testing:
 * 1. Reverse the order of words
 * 2. Replace each word with the base64 encoding of its UTF-8 representation, minus padding
 *
 * Note that this function does not preserve placeholder tokens.
 */
fun String.toGibberish(): String {
  val encoder = Base64.getEncoder()

  return split(' ').asReversed().joinToString(" ") { word ->
    encoder.encodeToString(word.toByteArray()).trimEnd('=')
  }
}

/**
 * Customizes the number formatting in the gibberish locale to use different separator characters.
 */
class GibberishDecimalFormatSymbolsProvider : DecimalFormatSymbolsProvider() {
  override fun getAvailableLocales(): Array<Locale> {
    return arrayOf(Locales.GIBBERISH)
  }

  override fun getInstance(locale: Locale?): DecimalFormatSymbols {
    return DecimalFormatSymbols(Locale.ENGLISH).apply {
      decimalSeparator = ','
      groupingSeparator = '&'
    }
  }
}
