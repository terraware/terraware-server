package com.terraformation.backend.i18n

import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.EnumFromReferenceTable
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
  fun `all reference table enum values have English display names`() {
    val scanner = ClassPathScanningCandidateComponentProvider(false)
    scanner.addIncludeFilter(AssignableTypeFilter(EnumFromReferenceTable::class.java))

    val keys =
        scanner
            .findCandidateComponents("com.terraformation.backend.db")
            .map { Class.forName(it.beanClassName) as Class<EnumFromReferenceTable<*>> }
            .flatMap { enumClass ->
              val enumName = enumClass.simpleName
              val packageName = enumClass.packageName.substringAfterLast('.')
              val prefix = if (packageName == "default_schema") "public" else packageName

              enumClass.enumConstants.map { "$prefix.$enumName.$it" }
            }

    assertNotEquals(0, keys.size, "Scanner should have found enums")
    assertBundleContains("i18n.Enums", keys)
  }

  @Test
  fun `all valid country codes have English display names`() {
    assertBundleContains("i18n.Countries", countriesDao.findAll().mapNotNull { it.code })
  }

  @Test
  fun `all valid country subdivision codes have English display names`() {
    assertBundleContains(
        "i18n.CountrySubdivisions", countrySubdivisionsDao.findAll().mapNotNull { it.code })
  }

  private fun assertBundleContains(bundleName: String, keys: Collection<String>) {
    val bundle = ResourceBundle.getBundle(bundleName, Locale.ENGLISH)
    val keysInBundle = bundle.keys.asSequence().filter { bundle.getString(it).isNotBlank() }.toSet()
    val expectedKeys = keys.toSet()
    val missingKeys = expectedKeys - keysInBundle

    assertEquals(emptySet<String>(), missingKeys, "Bundle $bundleName is missing values")
  }
}
