package com.terraformation.backend.auth

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter

/**
 * Partial implementation of the Keycloak API's UserRepresentation model. This only includes fields
 * we care about in our application, though other values are preserved so that they can be passed
 * back to Keycloak when editing a user's profile information.
 */
data class UserRepresentation(
    val attributes: Map<String, List<String>>? = null,
    val email: String,
    val emailVerified: Boolean? = null,
    val enabled: Boolean? = null,
    var firstName: String?,
    val groups: List<String>? = null,
    var id: String? = null,
    var lastName: String?,
    val username: String,
    @get:JsonAnyGetter @JsonAnySetter val otherValues: Map<String, Any?> = mutableMapOf(),
)
