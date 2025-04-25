package com.terraformation.backend.db

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

data class StableId @JsonCreator constructor(@get:JsonValue val value: String) {
  override fun toString() = value
}
