package com.terraformation.backend.email

import com.terraformation.backend.auth.KeycloakInfo
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.pojos.DevicesRow
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.tracking.PlantingSiteId
import jakarta.inject.Named
import jakarta.ws.rs.core.UriBuilder
import java.net.URI
import java.net.URLEncoder

/**
 * Constructs URLs for specific locations in the web app. These are used in things like notification
 * email messages that need to include direct links to specific areas of the app.
 */
@Named
class WebAppUrls(
    private val config: TerrawareServerConfig,
    private val keycloakInfo: KeycloakInfo,
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

  fun terrawareRegistrationUrl(organizationId: OrganizationId, email: String): URI {
    return buildRegistrationUrl(fullOrganizationHome(organizationId), email)
  }

  fun funderPortalRegistrationUrl(email: String): URI {
    return buildRegistrationUrl(fullFunderPortalHome(), email, mapOf("funderLogin" to "true"))
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
      organizationId: OrganizationId,
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
      state: AccessionState,
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

  fun acceleratorConsoleActivity(activityId: ActivityId, projectId: ProjectId): URI {
    return UriBuilder.fromPath("/accelerator/activity-log/${projectId}/${activityId}").build()
  }

  fun fullAcceleratorConsoleApplication(applicationId: ApplicationId): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/accelerator/applications/$applicationId")
        .build()
  }

  fun acceleratorConsoleApplication(applicationId: ApplicationId): URI {
    return UriBuilder.fromPath("/accelerator/applications/$applicationId").build()
  }

  fun fullNurseryInventory(organizationId: OrganizationId): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/inventory")
        .queryParam("organizationId", organizationId)
        .build()
  }

  fun nurseryInventory(): URI {
    return URI.create("/inventory")
  }

  fun fullBatch(organizationId: OrganizationId, batchId: BatchId, speciesId: SpeciesId): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/inventory/species/${speciesId.value}/batch/${batchId.value}")
        .queryParam("organizationId", organizationId)
        .build()
  }

  fun batch(batchId: BatchId, speciesId: SpeciesId): URI {
    return UriBuilder.fromPath("/inventory/species/${speciesId.value}/batch/${batchId.value}")
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

  fun moduleEvent(
      moduleId: ModuleId,
      eventId: EventId,
      organizationId: OrganizationId,
      projectId: ProjectId,
  ): URI {
    return UriBuilder.fromPath("/projects/$projectId/modules/$moduleId/sessions/$eventId")
        .queryParam("organizationId", organizationId)
        .build()
  }

  fun fullSeedFundReport(seedFundReportId: SeedFundReportId, organizationId: OrganizationId): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/seed-fund-reports/$seedFundReportId")
        .queryParam("organizationId", organizationId)
        .build()
  }

  fun seedFundReport(seedFundReportId: SeedFundReportId): URI {
    return UriBuilder.fromPath("/seed-fund-reports/$seedFundReportId").build()
  }

  fun fullPlantingSite(organizationId: OrganizationId, plantingSiteId: PlantingSiteId): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/planting-sites/$plantingSiteId")
        .queryParam("organizationId", organizationId)
        .build()
  }

  fun plantingSite(plantingSiteId: PlantingSiteId): URI {
    return URI("/planting-sites/$plantingSiteId")
  }

  fun fullObservations(organizationId: OrganizationId, plantingSiteId: PlantingSiteId): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/observations/$plantingSiteId")
        .queryParam("organizationId", organizationId)
        .build()
  }

  fun observations(organizationId: OrganizationId, plantingSiteId: PlantingSiteId): URI {
    return UriBuilder.fromPath("/observations/$plantingSiteId")
        .queryParam("organizationId", organizationId)
        .build()
  }

  fun fullDeliverable(
      deliverableId: DeliverableId,
      organizationId: OrganizationId,
      projectId: ProjectId,
  ): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/deliverables/${deliverableId.value}/submissions/${projectId.value}")
        .queryParam("organizationId", organizationId)
        .build()
  }

  fun deliverable(deliverableId: DeliverableId, projectId: ProjectId): URI {
    return UriBuilder.fromPath(
            "/deliverables/${deliverableId.value}/submissions/${projectId.value}"
        )
        .build()
  }

  fun fullAcceleratorConsoleDeliverable(
      deliverableId: DeliverableId,
      projectId: ProjectId,
  ): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/accelerator/deliverables/${deliverableId.value}/submissions/${projectId.value}")
        .build()
  }

  fun acceleratorConsoleDeliverable(deliverableId: DeliverableId, projectId: ProjectId): URI {
    return UriBuilder.fromPath(
            "/accelerator/deliverables/${deliverableId.value}/submissions/${projectId.value}"
        )
        .build()
  }

  fun fullAcceleratorConsoleReport(
      reportId: ReportId,
      projectId: ProjectId,
  ): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/accelerator/projects/${projectId.value}/reports/${reportId.value}")
        .build()
  }

  fun acceleratorConsoleReport(
      reportId: ReportId,
      projectId: ProjectId,
  ): URI {
    return UriBuilder.fromPath("/accelerator/projects/${projectId.value}/reports/${reportId.value}")
        .build()
  }

  fun fullAcceleratorReport(
      reportId: ReportId,
      organizationId: OrganizationId,
  ): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/reports/${reportId.value}")
        .queryParam("organizationId", organizationId)
        .build()
  }

  fun acceleratorReport(
      reportId: ReportId,
  ): URI {
    return UriBuilder.fromPath("/reports/${reportId.value}").build()
  }

  fun fullFunderReport(
      reportId: ReportId,
  ): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/funder/home?tab=report&reportId=${reportId.value}")
        .build()
  }

  fun funderReport(
      reportId: ReportId,
  ): URI {
    return UriBuilder.fromPath("/funder/home?tab=report&reportId=${reportId.value}").build()
  }

  // Section variable ID is unused for now but included in anticipation of being able to link
  // directly to a section in a document.
  fun fullDocument(documentId: DocumentId, sectionVariableId: VariableId?): URI {
    return UriBuilder.fromUri(config.webAppUrl).path("/accelerator/documents/$documentId").build()
  }

  fun document(documentId: DocumentId, sectionVariableId: VariableId?): URI {
    return UriBuilder.fromPath("/accelerator/documents/$documentId").build()
  }

  fun fullContactUs(): URI {
    return UriBuilder.fromUri(config.webAppUrl).path("/help-support/contact-us").build()
  }

  /** URL of the mobile app's page in the App Store. */
  val appStore = URI("https://apps.apple.com/us/app/terraware/id1568369900")

  /** URL of the mobile app's page in the Google Play Store. */
  val googlePlay =
      URI("https://play.google.com/store/apps/details?id=com.terraformation.seedcollector")

  private fun devicePath(uriBuilder: UriBuilder, device: DevicesRow?): UriBuilder {
    return if (device?.deviceType == "sensor") {
      uriBuilder.queryParam("sensor", device.id)
    } else {
      uriBuilder
    }
  }

  private fun accessionViabilityTestPath(
      accessionId: AccessionId,
      testType: ViabilityTestType,
  ): String {
    return "/accessions/${accessionId.value}/" +
        when (testType) {
          ViabilityTestType.Cut,
          ViabilityTestType.Lab -> "lab"
          ViabilityTestType.Nursery -> "nursery"
        }
  }

  private fun buildRegistrationUrl(
      redirectUri: URI,
      email: String,
      additionalParams: Map<String, String> = emptyMap(),
  ): URI {
    val builder =
        UriBuilder.fromUri(keycloakInfo.issuerUri)
            .path("/protocol")
            .path("openid-connect")
            .path("registrations")
            .queryParam("client_id", keycloakInfo.clientId)
            .queryParam("email", URLEncoder.encode(email, "UTF-8"))
            .queryParam("redirect_uri", redirectUri)
            .queryParam("response_type", "code")
            .queryParam("scope", "openid")

    additionalParams.forEach { (key, value) -> builder.queryParam(key, value) }

    return builder.build()
  }

  private fun fullFunderPortalHome(): URI {
    return UriBuilder.fromUri(config.webAppUrl).path("/funder/home").build()
  }
}
