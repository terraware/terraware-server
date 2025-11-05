package com.terraformation.backend.i18n

import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.LocalizableEnum
import java.util.Locale
import java.util.ResourceBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter

class EnumsTest : DatabaseTest() {
  @Suppress("UNCHECKED_CAST")
  @Test
  fun `all localizable enum values have English display names`() {
    val scanner = ClassPathScanningCandidateComponentProvider(false)
    scanner.addIncludeFilter(AssignableTypeFilter(LocalizableEnum::class.java))

    val keys =
        scanner
            .findCandidateComponents("com.terraformation.backend")
            .map { Class.forName(it.beanClassName) as Class<LocalizableEnum<*>> }
            .flatMap { enumClass ->
              val enumName = enumClass.simpleName
              val packageName = enumClass.packageName.substringAfterLast('.')
              val prefix = if (packageName == "default_schema") "public" else packageName

              enumClass.enumConstants.map { "$prefix.$enumName.$it" to it.jsonValue }
            }
            .toMap()

    assertNotEquals(0, keys.size, "Scanner should have found enums")
    assertBundleContains("i18n.Enums", keys)
  }

  @Test
  fun `all valid country codes have English display names`() {
    assertBundleContains(
        "i18n.Countries",
        countriesDao.findAll().associate { it.code!! to it.name },
    )
  }

  @Test
  fun `all valid country subdivision codes have English display names`() {
    assertBundleContains(
        "i18n.CountrySubdivisions",
        countrySubdivisionsDao.findAll().associate { it.code!! to it.name },
    )
  }

  private fun assertBundleContains(bundleName: String, enums: Map<String, String?>) {
    val bundle = ResourceBundle.getBundle(bundleName, Locale.ENGLISH)
    val keysInBundle = bundle.keys.asSequence().filter { bundle.getString(it).isNotBlank() }.toSet()
    val expectedKeys = enums.keys.toSet()
    val missingKeys = expectedKeys - keysInBundle

    // Render the missing keys one per line with leading and trailing newlines suitable for
    // copy-pasting into Enums_en.properties.
    val missingProperties =
        missingKeys.sorted().joinToString("\n", "\n", "\n") { "$it=${enums[it] ?: ""}" }
    assertEquals("\n\n", missingProperties, "Bundle $bundleName is missing values")
  }
}
