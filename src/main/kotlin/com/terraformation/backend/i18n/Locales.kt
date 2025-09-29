package com.terraformation.backend.i18n

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale
import org.springframework.context.i18n.LocaleContextHolder

object Locales {
  val ENGLISH: Locale = Locale.ENGLISH
  val FRENCH: Locale = Locale.FRENCH
  val GIBBERISH: Locale = Locale.forLanguageTag("gx")
  val SPANISH: Locale = Locale.forLanguageTag("es")
}

/**
 * Returns the currently-active locale. This currently uses Spring's locale context, which gets
 * populated with the current locale based on the Accept-Language header of the incoming request.
 */
fun currentLocale(): Locale = LocaleContextHolder.getLocale()

/** Runs some code with the current locale set to a particular value. */
fun <T> Locale.use(func: () -> T): T {
  val oldLocale = LocaleContextHolder.getLocale()
  try {
    LocaleContextHolder.setLocale(this)
    return func()
  } finally {
    LocaleContextHolder.setLocale(oldLocale)
  }
}

private val whitespace = Regex("\\s")

/**
 * Parses a string representation of a numeric value into a BigDecimal using the number formatting
 * rules for a locale.
 *
 * Some locales such as French use whitespace as the separator character, but they usually use a
 * non-breaking space character such as U+00A0 rather than an ASCII space. Java's number parsers are
 * strict about accepting specific Unicode code points as separator characters. Users, however,
 * might type ASCII spaces into a spreadsheet and expect them to be accepted. So we strip all
 * whitespace characters from the string before parsing it.
 */
fun String.toBigDecimal(locale: Locale): BigDecimal {
  val parser = NumberFormat.getNumberInstance(locale) as DecimalFormat
  parser.isParseBigDecimal = true
  return parser.parse(this.replace(whitespace, "")) as BigDecimal
}
