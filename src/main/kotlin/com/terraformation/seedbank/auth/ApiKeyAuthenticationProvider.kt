package com.terraformation.seedbank.auth

import com.terraformation.seedbank.db.tables.daos.ApiKeyDao
import java.security.MessageDigest
import javax.annotation.ManagedBean
import javax.xml.bind.DatatypeConverter
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication

@ManagedBean
class ApiKeyAuthenticationProvider(private val apiKeyDao: ApiKeyDao) : AuthenticationProvider {
  /** Hash function to use on API keys. */
  private val apiKeyDigest = MessageDigest.getInstance("SHA1")!!

  override fun supports(authentication: Class<*>): Boolean {
    return authentication == UsernamePasswordAuthenticationToken::class.java
  }

  override fun authenticate(authentication: Authentication): Authentication? {
    if (authentication is UsernamePasswordAuthenticationToken) {
      val hashedApiKey = hashApiKey(authentication.credentials.toString())
      val orgId = apiKeyDao.fetchOneByHash(hashedApiKey)?.organizationId ?: return null
      val controller = ControllerClientIdentity(orgId)

      return UsernamePasswordAuthenticationToken(
          controller,
          authentication.credentials,
          controller.authorities,
      )
    }
    return null
  }

  private fun hashApiKey(key: String): String {
    val binaryHash = apiKeyDigest.digest(key.toByteArray())
    return DatatypeConverter.printHexBinary(binaryHash).toLowerCase()
  }
}
