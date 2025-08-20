package com.terraformation.backend.support.atlassian

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.dummyTerrawareServerConfig
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.getEnvOrSkipTest
import com.terraformation.backend.mockUser
import com.terraformation.backend.support.atlassian.model.SupportRequestType
import io.ktor.client.plugins.ClientRequestException
import io.mockk.every
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

@EnableConfigurationProperties(TerrawareServerConfig::class)
class AtlassianHttpClientExternalTest : RunsAsUser {
  override val user: TerrawareUser = mockUser()
  private lateinit var client: AtlassianHttpClient
  private val createdIssueIds: MutableList<String> = mutableListOf()
  private val sixPixelPng: ByteArray by lazy {
    javaClass.getResourceAsStream("/file/sixPixels.png")!!.use { it.readAllBytes() }
  }

  @BeforeEach
  fun setUp() {
    every { user.canDeleteSupportIssue() } returns true

    val account = getEnvOrSkipTest("TERRAWARE_ATLASSIAN_ACCOUNT")
    val apiHostname = getEnvOrSkipTest("TERRAWARE_ATLASSIAN_APIHOST")
    val apiToken = getEnvOrSkipTest("TERRAWARE_ATLASSIAN_APITOKEN")
    val serviceDeskKey = getEnvOrSkipTest("TERRAWARE_ATLASSIAN_SERVICEDESKKEY")

    val config =
        dummyTerrawareServerConfig(
            atlassian =
                TerrawareServerConfig.AtlassianConfig(
                    account = account,
                    apiHost = apiHostname,
                    apiToken = apiToken,
                    enabled = true,
                    serviceDeskKey = serviceDeskKey,
                )
        )

    client = AtlassianHttpClient(config)
    assertTrue(client.requestTypeIds.isNotEmpty())
  }

  @AfterEach
  fun deleteCreatedIssues() {
    createdIssueIds.forEach { client.deleteIssue(it) }
    createdIssueIds.clear()
  }

  @Test
  fun `create new issue with attachments`() {
    val createResponse =
        client.createServiceDeskRequest(
            description = "Description",
            summary = "Summary",
            requestType = SupportRequestType.ContactUs,
            reporter = "testuser@example.com",
        )

    assertNotNull(createResponse)
    val issueId = createResponse.issueId
    createdIssueIds.addLast(issueId)

    val filename = "file.png"

    val sizedInputStream =
        SizedInputStream(sixPixelPng.inputStream(), sixPixelPng.size.toLong(), MediaType.IMAGE_PNG)
    val attachTempFilesResponse = client.attachTemporaryFile(filename, sizedInputStream)

    assertNotNull(attachTempFilesResponse)
    val attachmentIds =
        attachTempFilesResponse.temporaryAttachments.map { it.temporaryAttachmentId }

    client.createAttachments(issueId, attachmentIds, "Test attachment uploads.")
  }

  @Test
  fun `throws exception if attachment no longer exists`() {
    val createResponse =
        client.createServiceDeskRequest(
            description = "Description",
            summary = "Summary",
            requestType = SupportRequestType.ContactUs,
            reporter = "testuser@example.com",
        )

    val issueId = createResponse.issueId
    createdIssueIds.addLast(issueId)

    val sizedInputStream =
        SizedInputStream(sixPixelPng.inputStream(), sixPixelPng.size.toLong(), MediaType.IMAGE_PNG)
    val attachTempFilesResponse = client.attachTemporaryFile("test.png", sizedInputStream)
    val attachmentIds =
        attachTempFilesResponse.temporaryAttachments.map { it.temporaryAttachmentId }
    client.createAttachments(issueId, attachmentIds, "Initial use of attachment")

    assertThrows<ClientRequestException>("Should throw exception if attachment ID is reused") {
      client.createAttachments(issueId, attachmentIds, "Reusing attachment ID")
    }
  }

  @Test
  fun `throws exception if no permission to delete issue`() {
    every { user.canDeleteSupportIssue() } returns false
    assertThrows<AccessDeniedException> { client.deleteIssue("issue") }
  }
}
