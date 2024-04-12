package com.terraformation.backend.support

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.support.atlassian.AtlassianHttpClient
import com.terraformation.backend.support.atlassian.SupportRequestType
import com.terraformation.backend.support.atlassian.resource.AtlassianResourceFactory
import java.net.URI
import org.junit.Assume
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties

@EnableConfigurationProperties(TerrawareServerConfig::class)
class AtlassianHttpClientExternalTest {
  private lateinit var factory: AtlassianResourceFactory
  private lateinit var client: AtlassianHttpClient
  private val createdIssueIds: MutableList<String> = mutableListOf()

  @BeforeEach
  fun setUp() {
    val account = getEnvOrSkipTest("TERRAWARE_ATLASSIAN_ACCOUNT")
    val apiHostname = getEnvOrSkipTest("TERRAWARE_ATLASSIAN_HOST")
    val apiToken = getEnvOrSkipTest("TERRAWARE_ATLASSIAN_TOKEN")
    val bugReportTypeId = getEnvOrSkipTest("TERRAWARE_ATLASSIAN_BUG_REPORT_TYPE_ID").toInt()
    val featureRequestTypeId =
        getEnvOrSkipTest("TERRAWARE_ATLASSIAN_FEATURE_REQUEST_TYPE_ID").toInt()
    val serviceDeskId = getEnvOrSkipTest("TERRAWARE_ATLASSIAN_SERVICE_DESK_ID").toInt()

    val config =
        TerrawareServerConfig(
            webAppUrl = URI("https://terraware.io"),
            atlassian =
                TerrawareServerConfig.AtlassianConfig(
                    account = account,
                    apiHostname = apiHostname,
                    apiToken = apiToken,
                    bugReportTypeId = bugReportTypeId,
                    enabled = true,
                    featureRequestTypeId = featureRequestTypeId,
                    serviceDeskId = serviceDeskId),
            keycloak =
                TerrawareServerConfig.KeycloakConfig(
                    apiClientId = "test",
                    apiClientGroupName = "test",
                    apiClientUsernamePrefix = "test"))

    client = AtlassianHttpClient(config)
    factory = AtlassianResourceFactory(config)
  }

  @AfterEach
  fun deleteCreatedIssues() {
    createdIssueIds.forEach {
      val request = factory.deleteIssue(it)
      client.makeRequest(request)
    }
    createdIssueIds.clear()
  }

  @Test
  fun `create new issue`() {
    val request =
        factory.createServiceDeskRequest(
            description = "Description",
            summary = "Summary",
            reporter = "testuser@example.com",
            requestType = SupportRequestType.FEATURE_REQUEST)

    val response = client.makeRequest(request)

    assertNotNull(response)
    createdIssueIds.addLast(response.issueId)
  }

  private fun getEnvOrSkipTest(name: String): String {
    val value = System.getenv(name)
    Assume.assumeNotNull(value, "$name not set; skipping test")
    return value
  }
}
