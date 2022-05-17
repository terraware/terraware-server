package com.terraformation.backend.email

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.GerminationTestType
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import java.net.URI
import javax.annotation.ManagedBean
import javax.ws.rs.core.UriBuilder

/**
 * Constructs URLs for specific locations in the web app. These are used in things like notification
 * email messages that need to include direct links to specific areas of the app.
 */
@ManagedBean
class WebAppUrls(private val config: TerrawareServerConfig) {
  fun fullOrganizationHome(organizationId: OrganizationId): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/home")
        .queryParam("organizationId", organizationId)
        .build()
  }

  /** Generates a relative path of organization home within the web app */
  fun organizationHome(organizationId: OrganizationId): URI {
    return UriBuilder.fromPath("/home").queryParam("organizationId", organizationId).build()
  }

  fun fullOrganizationProject(projectId: ProjectId, organizationId: OrganizationId): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/projects/${projectId.value}")
        .queryParam("organizationId", organizationId)
        .build()
  }

  fun organizationProject(projectId: ProjectId): URI {
    return UriBuilder.fromPath("/projects/" + projectId.value).build()
  }

  fun fullAccession(accessionId: AccessionId, organizationId: OrganizationId): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/accessions/${accessionId.value}")
        .queryParam("organizationId", organizationId)
        .build()
  }

  fun accession(accessionId: AccessionId): URI {
    return UriBuilder.fromPath("/accessions/${accessionId.value}").build()
  }

  fun fullAccessionGerminationTest(
      accessionId: AccessionId,
      testType: GerminationTestType,
      organizationId: OrganizationId
  ): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path(accessionGerminationTestPath(accessionId, testType))
        .queryParam("organizationId", organizationId)
        .build()
  }

  fun accessionGerminationTest(accessionId: AccessionId, testType: GerminationTestType): URI {
    return UriBuilder.fromPath(accessionGerminationTestPath(accessionId, testType)).build()
  }

  fun fullAccessions(organizationId: OrganizationId, state: AccessionState): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/accessions")
        .queryParam("stage", state)
        .queryParam("organizationId", organizationId)
        .build()
  }

  fun accessions(state: AccessionState): URI {
    return UriBuilder.fromPath("/accessions").queryParam("stage", state).build()
  }

  private fun accessionGerminationTestPath(
      accessionId: AccessionId,
      testType: GerminationTestType
  ): String {
    return "/accessions/${accessionId.value}/" +
        when (testType) {
          GerminationTestType.Lab -> "lab"
          GerminationTestType.Nursery -> "nursery"
        }
  }
}
