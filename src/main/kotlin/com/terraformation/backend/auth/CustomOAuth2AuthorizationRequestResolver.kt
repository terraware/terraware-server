package com.terraformation.backend.auth

import jakarta.servlet.http.HttpServletRequest
import org.apache.http.HttpHeaders
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest

class CustomOAuth2AuthorizationRequestResolver(
    repo: ClientRegistrationRepository,
    authorizationRequestBaseUri: String,
) : OAuth2AuthorizationRequestResolver {
  private val delegate =
      DefaultOAuth2AuthorizationRequestResolver(repo, authorizationRequestBaseUri)

  override fun resolve(request: HttpServletRequest?): OAuth2AuthorizationRequest? {
    val authorizationRequest = delegate.resolve(request) ?: return null
    return customizeAuthorizationRequest(authorizationRequest, request)
  }

  override fun resolve(
      request: HttpServletRequest?,
      clientRegistrationId: String?,
  ): OAuth2AuthorizationRequest? {
    val authorizationRequest = delegate.resolve(request, clientRegistrationId) ?: return null
    return customizeAuthorizationRequest(authorizationRequest, request)
  }

  private fun customizeAuthorizationRequest(
      authorizationRequest: OAuth2AuthorizationRequest,
      request: HttpServletRequest?,
  ): OAuth2AuthorizationRequest {
    val modifiedParams = authorizationRequest.additionalParameters.toMutableMap()

    if (isRequestFunderLogin(request)) {
      modifiedParams["funderLogin"] = "true"
    }

    return OAuth2AuthorizationRequest.from(authorizationRequest)
        .additionalParameters(modifiedParams)
        .build()
  }

  fun isRequestFunderLogin(request: HttpServletRequest?): Boolean {
    val referer = request?.getHeader(HttpHeaders.REFERER)
    return request?.requestURI?.contains("/funder") == true ||
        (referer != null && referer.contains("/funder"))
  }
}
