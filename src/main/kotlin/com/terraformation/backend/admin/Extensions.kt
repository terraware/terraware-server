package com.terraformation.backend.admin

import org.springframework.web.servlet.mvc.support.RedirectAttributes

var RedirectAttributes.failureMessage: String?
  get() = flashAttributes["failureMessage"]?.toString()
  set(value) {
    addFlashAttribute("failureMessage", value)
  }

var RedirectAttributes.failureDetails: List<String>?
  get() {
    val attribute = flashAttributes["failureDetails"]
    return if (attribute is List<*>) {
      attribute.map { "$it" }
    } else {
      null
    }
  }
  set(value) {
    addFlashAttribute("failureDetails", value)
  }

var RedirectAttributes.successMessage: String?
  get() = flashAttributes["successMessage"]?.toString()
  set(value) {
    addFlashAttribute("successMessage", value)
  }
