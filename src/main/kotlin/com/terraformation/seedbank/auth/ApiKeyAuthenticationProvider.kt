package com.terraformation.seedbank.auth

import com.terraformation.seedbank.db.tables.daos.ApiKeyDao
import com.terraformation.seedbank.services.perClassLogger
import java.security.MessageDigest
import javax.annotation.ManagedBean
import javax.xml.bind.DatatypeConverter
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication

@ManagedBean
class ApiKeyAuthenticationProvider(private val apiKeyDao: ApiKeyDao) : AuthenticationProvider {
  override fun supports(authentication: Class<*>): Boolean {
    val matches = authentication == UsernamePasswordAuthenticationToken::class.java
    if (!matches) {
      perClassLogger().warn("Authentication type ${authentication.javaClass.name} not supported")
    }
    return matches
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
    val keyBytes = key.toByteArray()
    val binaryHash = MessageDigest.getInstance("SHA1").digest(keyBytes)
    return DatatypeConverter.printHexBinary(binaryHash).toLowerCase()
  }
}
