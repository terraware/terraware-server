package com.terraformation.backend.customer.model

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
) : TerrawareUser {

  override val userType: UserType
    get() = UserType.Funder

  override fun getName(): String = authId ?: throw IllegalStateException("User is unregistered")

  override fun getUsername(): String = authId ?: throw IllegalStateException("User is unregistered")

  override val defaultPermission: Boolean
    get() = false
}
