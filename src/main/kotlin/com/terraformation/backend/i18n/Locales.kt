package com.terraformation.backend.i18n

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.springframework.context.i18n.LocaleContextHolder

object Locales {
  val GIBBERISH = Locale.forLanguageTag("gx")

  val supported =
      listOf(
          Locale.ENGLISH,
          GIBBERISH,
      )
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

private val bigDecimalParsers = ConcurrentHashMap<Locale, DecimalFormat>()

fun getBigDecimalParser(locale: Locale): DecimalFormat {
  return bigDecimalParsers.getOrPut(locale) {
    (NumberFormat.getNumberInstance(locale) as DecimalFormat).apply { isParseBigDecimal = true }
  }
}

fun String.toBigDecimal(locale: Locale): BigDecimal {
  return getBigDecimalParser(locale).parse(this) as BigDecimal
}
