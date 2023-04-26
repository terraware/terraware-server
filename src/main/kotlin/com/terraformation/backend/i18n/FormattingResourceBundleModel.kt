package com.terraformation.backend.i18n

import freemarker.ext.beans.BeansWrapper
import freemarker.ext.beans.ResourceBundleModel
import java.text.MessageFormat
import java.util.ResourceBundle

/**
 * Variant of [ResourceBundleModel] that always formats strings with [MessageFormat] even if they
 * don't have any placeholders. This gives us consistent handling of single quote characters in
 * localized strings: they always need to be doubled, rather than only needing to be doubled in
 * strings that happen to also have placeholders.
 */
class FormattingResourceBundleModel(bundle: ResourceBundle, wrapper: BeansWrapper) :
    ResourceBundleModel(bundle, wrapper) {
  override fun exec(arguments: List<Any?>): Any? {
    return if (arguments.size == 1) {
      // No arguments supplied. Add a fake one to force the value to be treated as a format string
      // rather than a constant.
      super.exec(listOf(arguments[0], null))
    } else {
      super.exec(arguments)
    }
  }
}
