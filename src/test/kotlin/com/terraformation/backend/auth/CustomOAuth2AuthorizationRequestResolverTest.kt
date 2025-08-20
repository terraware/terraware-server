package com.terraformation.backend.auth

import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import java.util.stream.Stream
import org.apache.http.HttpHeaders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class CustomOAuth2AuthorizationRequestResolverTest {

  @ParameterizedTest(name = "Test case #{index}: uri={0}, referer={1}, expected={2}")
  @MethodSource("funderLoginTestCases")
  fun `should correctly determine if request is funder login`(
      requestUri: String?,
      referer: String?,
      expected: Boolean,
  ) {
    val request = mockk<HttpServletRequest>()
    val resolver = CustomOAuth2AuthorizationRequestResolver(mockk(), "/oauth2/authorization")

    every { request.requestURI } returns requestUri
    every { request.getHeader(HttpHeaders.REFERER) } returns referer

    val result = resolver.isRequestFunderLogin(request)

    assertEquals(expected, result)
  }

  @ParameterizedTest
  @MethodSource("nullRequestCase")
  fun `should handle null request`(expected: Boolean) {
    val resolver = CustomOAuth2AuthorizationRequestResolver(mockk(), "/oauth2/authorization")

    val result = resolver.isRequestFunderLogin(null)

    assertEquals(expected, result)
  }

  companion object {
    @JvmStatic
    fun funderLoginTestCases(): Stream<Arguments> =
        Stream.of(
            Arguments.of("/funder/login", null, true),
            Arguments.of("/funder", "https://example.com/login", true),
            Arguments.of("/login", "https://example.com/funder/page", true),
            Arguments.of("/login", "https://example.com/login", false),
            Arguments.of("/user", "https://example.com/user", false),
        )

    @JvmStatic fun nullRequestCase(): Stream<Arguments> = Stream.of(Arguments.of(false))
  }
}
