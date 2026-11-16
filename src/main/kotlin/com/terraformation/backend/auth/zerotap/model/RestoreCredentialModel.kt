package com.terraformation.backend.auth.zerotap.model

import com.terraformation.backend.db.default_schema.UserId

class RestoreCredentialModel(
    /** WebAuthn attested credential data, including the credential's public key. */
    val attestedCredentialData: ByteArray,
    /** The user's Keycloak user ID. */
    val authId: String,
    val signCount: Long,
    val userId: UserId,
)
