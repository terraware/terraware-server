package com.terraformation.backend.i18n

import java.text.DecimalFormatSymbols
import java.text.spi.DecimalFormatSymbolsProvider
import java.util.Locale
import kotlin.io.encoding.Base64

/**
 * Converts an English string to gibberish for localization testing:
 * 1. Reverse the order of words
 * 2. Replace each word with the base64 encoding of its UTF-8 representation, minus padding
 *
 * Note that this function does not preserve placeholder tokens.
 */
fun String.toGibberish(): String {
  return split('\n').joinToString("\n") { line ->
    line.split(' ').asReversed().joinToString(" ") { word ->
      Base64.encode(word.toByteArray()).trimEnd('=')
    }
  }
}

/**
 * Customizes the number formatting in the gibberish locale to use French-style separator
 * characters.
 */
class GibberishDecimalFormatSymbolsProvider : DecimalFormatSymbolsProvider() {
  override fun getAvailableLocales(): Array<Locale> {
    return arrayOf(Locales.GIBBERISH)
  }

  override fun getInstance(locale: Locale?) = DecimalFormatSymbols(Locale.FRENCH)
}
