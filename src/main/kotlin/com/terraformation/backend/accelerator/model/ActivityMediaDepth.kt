package com.terraformation.backend.accelerator.model

import com.fasterxml.jackson.annotation.JsonValue

enum class ActivityMediaDepth(@get:JsonValue val jsonValue: String) {
  None("None"),
  CoverPhotos("Cover Photos"),
  All("All"),
}
