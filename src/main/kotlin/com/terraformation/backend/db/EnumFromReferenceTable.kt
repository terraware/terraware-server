package com.terraformation.backend.db

import com.terraformation.backend.log.perClassLogger
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

interface EnumFromReferenceTable<T : Enum<T>> {
  val id: Int
  /** Display name in US English. */
  val displayName: String
  val tableName: String

  fun getDisplayName(locale: Locale?): String

  companion object {
    fun <T : EnumFromReferenceTable<T>> loadLocalizedDisplayNames(
        locale: Locale,
        values: Array<T>
    ): Map<T, String> {
      val bundle =
          try {
            ResourceBundle.getBundle("i18n.Enums", locale)
          } catch (e: MissingResourceException) {
            if (locale.language != "en") {
              perClassLogger()
                  .error("No localization bundle for enum names in $locale; defaulting to English")
            }
            return values.associateWith { it.displayName }
          }

      val enumClass = values.first().javaClass
      val enumName = enumClass.simpleName
      val packageName = enumClass.packageName.substringAfterLast('.')
      val prefix = if (packageName == "default_schema") "public" else packageName

      return values.associateWith { enumValue ->
        val key = "$prefix.$enumName.$enumValue"
        if (bundle.containsKey(key)) {
          bundle.getString(key)
        } else {
          perClassLogger().error("No translation for $key in $locale")
          enumValue.displayName
        }
      }
    }
  }
}
