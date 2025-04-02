package com.terraformation.backend.documentproducer.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

data class StableId @JsonCreator constructor(@get:JsonValue val value: String)
