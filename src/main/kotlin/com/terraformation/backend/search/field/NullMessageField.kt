package com.terraformation.backend.search.field

import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.FieldNode
import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap
import org.jooq.Condition
import org.jooq.Record

/**
 * Search field wrapper that returns a localized string instead of a null value if the original
 * field's value is null.
 */
class NullMessageField(
    private val original: SearchField,
    /** Resource bundle key of the property with the text to use for null values. */
    private val nullKey: String,
    private val resourceBundleName: String,
) : SearchField by original {
  private val nullTextByLocale = ConcurrentHashMap<Locale, String>()

  override fun getConditions(fieldNode: FieldNode): List<Condition> {
    val nullText = getNullText()
    val fieldNodeWithNull =
        fieldNode.copy(
            values =
                fieldNode.values.map { searchValue ->
                  if (searchValue == nullText) {
                    null
                  } else {
                    searchValue
                  }
                }
        )

    return original.getConditions(fieldNodeWithNull)
  }

  override fun computeValue(record: Record): String {
    return original.computeValue(record) ?: getNullText()
  }

  override fun raw(): SearchField? = original.raw()

  private fun getNullText(): String {
    val locale = currentLocale()
    return nullTextByLocale.getOrPut(locale) {
      val bundle = ResourceBundle.getBundle(resourceBundleName, locale)
      bundle.getString(nullKey)
    }
  }
}
