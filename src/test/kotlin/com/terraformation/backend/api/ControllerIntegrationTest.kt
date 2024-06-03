package com.terraformation.backend.api

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.DatabaseBackedTest
import com.terraformation.backend.mockUser
import jakarta.ws.rs.core.MediaType
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext

/**
 * Superclass for tests that exercise controllers and require the application to be fully available.
 */
@SpringBootTest
@Transactional
abstract class ControllerIntegrationTest : DatabaseBackedTest(), RunsAsUser {
  final override val user = mockUser()

  @Autowired private lateinit var context: WebApplicationContext

  protected val mockMvc: MockMvc by lazy { makeMockMvc() }

  protected fun makeMockMvc() =
      MockMvcBuilders.webAppContextSetup(context)
          .defaultRequest<DefaultMockMvcBuilder>(
              MockMvcRequestBuilders.get("")
                  .contentType(MediaType.APPLICATION_JSON)
                  .accept(MediaType.APPLICATION_JSON)
                  .apply { with(oidcLogin().idToken { it.subject(currentUser().authId) }) })
          .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
          .build()

  /**
   * Asserts that a controller returns an HTTP 200 response with a JSON body that includes the
   * specified contents.
   *
   * @param strict If true, require the content of the response to exactly match [json]: keys that
   *   are present in the response but not in [json] are treated as assertion failures. By default,
   *   extra keys are ignored. Whitespace is ignored even if this is true.
   */
  protected fun ResultActionsDsl.andExpectJson(
      json: String,
      strict: Boolean = false
  ): ResultActionsDsl {
    return andExpect {
      content { json(json, strict) }
      status { isOk() }
    }
  }
}
