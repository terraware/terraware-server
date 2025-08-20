package com.terraformation.backend.db

import java.util.Locale
import org.springframework.context.support.ResourceBundleMessageSource

interface LocalizableEnum<T : Enum<T>> {
  /** JSON string representation of this enum value. */
  val jsonValue: String

  fun getDisplayName(locale: Locale?): String

  companion object {
    private val messageSource =
        ResourceBundleMessageSource().apply {
          // Make the handling of single quote characters consistent regardless of whether or not
          // strings contain placeholders.
          setAlwaysUseMessageFormat(true)
          setBasename("i18n.Enums")
          setDefaultEncoding("UTF-8")
        }

    fun <T : LocalizableEnum<T>> loadLocalizedDisplayNames(
        locale: Locale,
        values: List<T>,
    ): Map<T, String> {
      val enumClass = values.first().javaClass
      val enumName = enumClass.simpleName
      val packageName = enumClass.packageName.substringAfterLast('.')
      val prefix = if (packageName == "default_schema") "public" else packageName

      return values.associateWith { enumValue ->
        val key = "$prefix.$enumName.$enumValue"
        messageSource.getMessage(key, emptyArray(), locale)
      }
    }
  }
}
