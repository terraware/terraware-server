package com.terraformation.backend.customer.model

import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

data class FunderUser(
    override val createdTime: Instant,
    override val userId: UserId,
    override val authId: String? = null,
    override val email: String,
    override val emailNotificationsEnabled: Boolean = true,
    override val firstName: String? = null,
    override val lastName: String? = null,
    override val countryCode: String? = null,
    override val cookiesConsented: Boolean? = null,
    override val cookiesConsentedTime: Instant? = null,
    override val locale: Locale? = null,
    override val timeZone: ZoneId? = null,
    override val parentStore: ParentStore,
    override val permissionStore: PermissionStore,
) :
    OrdinaryUser(
        createdTime,
        userId,
        authId,
        email,
        emailNotificationsEnabled,
        firstName,
        lastName,
        countryCode,
        cookiesConsented,
        cookiesConsentedTime,
        locale,
        timeZone,
        UserType.Funder,
        parentStore,
        permissionStore)
