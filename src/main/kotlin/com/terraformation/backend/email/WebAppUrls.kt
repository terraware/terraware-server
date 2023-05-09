package com.terraformation.backend.email

import com.terraformation.backend.auth.KeycloakInfo
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.pojos.DevicesRow
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.ViabilityTestType
import java.net.URI
import javax.inject.Named
import javax.ws.rs.core.UriBuilder

/**
 * Constructs URLs for specific locations in the web app. These are used in things like notification
 * email messages that need to include direct links to specific areas of the app.
 */
@Named
class WebAppUrls(
    private val config: TerrawareServerConfig,
    private val keycloakInfo: KeycloakInfo
) {
  fun fullOrganizationHome(organizationId: OrganizationId): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/home")
        .queryParam("organizationId", organizationId)
        .build()
  }

  fun organizationHome(organizationId: OrganizationId): URI {
    return UriBuilder.fromPath("/home").queryParam("organizationId", organizationId).build()
  }

  fun terrawareRegistrationUrl(organizationId: OrganizationId): URI {
    val orgHome = fullOrganizationHome(organizationId)
    val realmBaseUrl =
        URI(
            "${keycloakInfo.realmBaseUrl.toString().trimEnd('/')}/protocol/openid-connect/registrations")
    return UriBuilder.fromUri(realmBaseUrl)
        .queryParam("client_id", keycloakInfo.clientId)
        .queryParam("redirect_uri", orgHome)
        .queryParam("response_type", "code")
        .queryParam("scope", "openid")
        .build()
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

  fun fullAccessionViabilityTest(
      accessionId: AccessionId,
      testType: ViabilityTestType,
      organizationId: OrganizationId
  ): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path(accessionViabilityTestPath(accessionId, testType))
        .queryParam("organizationId", organizationId)
        .build()
  }

  fun accessionViabilityTest(accessionId: AccessionId, testType: ViabilityTestType): URI {
    return UriBuilder.fromPath(accessionViabilityTestPath(accessionId, testType)).build()
  }

  fun fullAccessions(
      organizationId: OrganizationId,
      facilityId: FacilityId,
      state: AccessionState
  ): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/accessions")
        .queryParam("stage", state)
        .queryParam("facilityId", facilityId)
        .queryParam("organizationId", organizationId)
        .build()
  }

  fun accessions(facilityId: FacilityId, state: AccessionState): URI {
    return UriBuilder.fromPath("/accessions")
        .queryParam("stage", state)
        .queryParam("facilityId", facilityId)
        .build()
  }

  fun fullBatch(organizationId: OrganizationId, batchNumber: String, speciesId: SpeciesId): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/inventory/${speciesId.value}")
        .queryParam("batch", batchNumber)
        .queryParam("organizationId", organizationId)
        .build()
  }

  fun batch(batchNumber: String, speciesId: SpeciesId): URI {
    return UriBuilder.fromPath("/inventory/${speciesId.value}")
        .queryParam("batch", batchNumber)
        .build()
  }

  fun fullFacilityMonitoring(
      organizationId: OrganizationId,
      facilityId: FacilityId,
      device: DevicesRow? = null,
  ): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/monitoring/${facilityId.value}")
        .queryParam("organizationId", organizationId)
        .let { devicePath(it, device) }
        .build()
  }

  fun facilityMonitoring(facilityId: FacilityId, device: DevicesRow? = null): URI {
    return UriBuilder.fromPath("/monitoring/${facilityId.value}")
        .let { devicePath(it, device) }
        .build()
  }

  fun fullReport(reportId: ReportId, organizationId: OrganizationId): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/reports/$reportId")
        .queryParam("organizationId", organizationId)
        .build()
  }

  fun report(reportId: ReportId): URI {
    return UriBuilder.fromPath("/reports/$reportId").build()
  }

  private fun devicePath(uriBuilder: UriBuilder, device: DevicesRow?): UriBuilder {
    return if (device?.deviceType == "sensor") {
      uriBuilder.queryParam("sensor", device.id)
    } else {
      uriBuilder
    }
  }

  private fun accessionViabilityTestPath(
      accessionId: AccessionId,
      testType: ViabilityTestType
  ): String {
    return "/accessions/${accessionId.value}/" +
        when (testType) {
          ViabilityTestType.Cut,
          ViabilityTestType.Lab -> "lab"
          ViabilityTestType.Nursery -> "nursery"
        }
  }
}
