package com.terraformation.backend.eventlog.api

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
sealed interface EventActionPayload

@JsonTypeName("Created")
class CreatedActionPayload : EventActionPayload {
  override fun equals(other: Any?) = other is CreatedActionPayload

  override fun hashCode() = javaClass.hashCode() xor 1
}

@JsonTypeName("Deleted")
class DeletedActionPayload : EventActionPayload {
  override fun equals(other: Any?) = other is DeletedActionPayload

  override fun hashCode() = javaClass.hashCode() xor 1
}

@JsonTypeName("FieldUpdated")
data class FieldUpdatedActionPayload(
    val fieldName: String,
    val changedFrom: List<String>?,
    val changedTo: List<String>?,
) : EventActionPayload
