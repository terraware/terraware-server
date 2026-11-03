package com.terraformation.backend.support.atlassian.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/** Enum representation of support requests that Terraware can submit. */
enum class SupportRequestType(
    @get:JsonValue val jsonValue: String,
    val isInternal: Boolean = false,
) {
  BugReport("Bug Report"),
  FeatureRequest("Feature Request"),
  ContactUs("Contact Us"),
  MissingContact("Missing Contact", isInternal = true);

  companion object {
    val entries = setOf(BugReport, FeatureRequest, ContactUs, MissingContact)
    private val byJsonValue = this.entries.associateBy { it.jsonValue }

    @JsonCreator
    @JvmStatic
    fun forJsonValue(jsonValue: String): SupportRequestType? {
      return byJsonValue[jsonValue]
    }
  }
}
