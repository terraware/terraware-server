package com.terraformation.backend.auth

/**
 * Partial implementation of the Keycloak API's CredentialRepresentation model. This only includes
 * fields we care about in our application.
 */
data class CredentialRepresentation(
    val id: String? = null,
    val temporary: Boolean,
    val type: String,
    val value: String,
)
