package com.terraformation.backend.support.atlassian.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/** Enum representation of support requests that Terraware users can submit. */
enum class SupportRequestType(@get:JsonValue val jsonValue: String) {
  BugReport("Bug Report"),
  FeatureRequest("Feature Request"),
  ContactUs("Contact Us");

  companion object {
    val entries = setOf(BugReport, FeatureRequest, ContactUs)
    private val byJsonValue = this.entries.associateBy { it.jsonValue }

    @JsonCreator
    @JvmStatic
    fun forJsonValue(jsonValue: String): SupportRequestType {
      return byJsonValue[jsonValue]
          ?: throw IllegalArgumentException("Unrecognized value: $jsonValue")
    }
  }
}
