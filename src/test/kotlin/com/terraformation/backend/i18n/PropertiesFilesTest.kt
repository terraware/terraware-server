package com.terraformation.backend.i18n

import java.util.Locale
import java.util.ResourceBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class PropertiesFilesTest {
  @MethodSource("getMessageBundles")
  @ParameterizedTest
  fun `mixed single quote styles`(bundleName: String) {
    val bundle = ResourceBundle.getBundle(bundleName, Locale.ENGLISH)
    val invalidKeys =
        bundle.keys
            .asSequence()
            .filter { key ->
              val value = bundle.getString(key)
              value.contains('\'') && value.contains('’')
            }
            .toList()

    assertEquals(
        emptyList<String>(),
        invalidKeys,
        "String contains both an ASCII apostrophe and a closing single quote",
    )
  }

  @MethodSource("getMessageBundles")
  @ParameterizedTest
  fun `all strings have translations in supported languages`(bundleName: String) {
    val englishBundle = ResourceBundle.getBundle(bundleName, Locale.ENGLISH)
    val missingTranslations =
        supportedLocales
            .flatMap { locale ->
              val localizedBundle = ResourceBundle.getBundle(bundleName, locale)
              englishBundle.keys
                  .asSequence()
                  .filterNot { localizedBundle.containsKey(it) }
                  .map { locale to it }
            }
            .groupBy { it.first }
            .mapValues { entry -> entry.value.map { it.second } }

    assertEquals(
        emptyMap<Locale, List<String>>(),
        missingTranslations,
        "Strings are missing translations; try running \"yarn translate\"",
    )
  }

  companion object {
    private val supportedLocales =
        listOf(
            Locales.FRENCH,
            Locales.SPANISH,
        )

    @JvmStatic
    fun getMessageBundles() =
        listOf(
            "i18n.Countries",
            "i18n.CountrySubdivisions",
            "i18n.Enums",
            "i18n.Messages",
            "i18n.TimeZoneWithoutCity",
        )
  }
}
