package com.terraformation.seedbank.auth

import com.terraformation.seedbank.db.tables.daos.KeyDao
import com.terraformation.seedbank.services.publishSingleValue
import io.micronaut.http.HttpRequest
import io.micronaut.security.authentication.AuthenticationException
import io.micronaut.security.authentication.AuthenticationFailed
import io.micronaut.security.authentication.AuthenticationProvider
import io.micronaut.security.authentication.AuthenticationRequest
import io.micronaut.security.authentication.AuthenticationResponse
import io.micronaut.security.authentication.UserDetails
import java.security.MessageDigest
import javax.inject.Singleton
import javax.xml.bind.DatatypeConverter
import org.reactivestreams.Publisher

@Singleton
class ApiKeyAuthenticationProvider(private val keyDao: KeyDao) : AuthenticationProvider {
  /** Hash function to use on API keys. */
  private val apiKeyDigest = MessageDigest.getInstance("SHA1")!!

  override fun authenticate(
      httpRequest: HttpRequest<*>?, authenticationRequest: AuthenticationRequest<*, *>
  ): Publisher<AuthenticationResponse> {
    return publishSingleValue {
      val hashedApiKey = hashApiKey(authenticationRequest.secret.toString())
      val orgId =
        keyDao.fetchOneByHash(hashedApiKey)?.organizationId
          ?: throw AuthenticationException(AuthenticationFailed())

      UserDetails(
        authenticationRequest.identity.toString(),
        listOf(Role.API_CLIENT.name, Role.AUTHENTICATED.name),
        mapOf(ORGANIZATION_ID_ATTR to orgId))
    }
  }

  private fun hashApiKey(key: String): String {
    val binaryHash = apiKeyDigest.digest(key.toByteArray())
    return DatatypeConverter.printHexBinary(binaryHash).toLowerCase()
  }
}
