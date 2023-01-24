package com.terraformation.backend.i18n

import java.util.Locale
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
