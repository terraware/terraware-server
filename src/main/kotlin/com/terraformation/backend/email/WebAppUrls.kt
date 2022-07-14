package com.terraformation.backend.email

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ViabilityTestType
import com.terraformation.backend.db.tables.pojos.DevicesRow
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
          ViabilityTestType.Lab -> "lab"
          ViabilityTestType.Nursery -> "nursery"
        }
  }
}
