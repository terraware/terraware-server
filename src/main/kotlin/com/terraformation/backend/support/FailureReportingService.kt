package com.terraformation.backend.support

import com.terraformation.backend.accelerator.db.ParticipantStore
import com.terraformation.backend.accelerator.event.DeliverableDocumentUploadFailedEvent
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.db.accelerator.tables.daos.DeliverablesDao
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.support.atlassian.model.SupportRequestType
import jakarta.inject.Named
import org.springframework.context.event.EventListener

@Named
class FailureReportingService(
    private val config: TerrawareServerConfig,
    private val deliverablesDao: DeliverablesDao,
    private val organizationStore: OrganizationStore,
    private val participantStore: ParticipantStore,
    private val projectStore: ProjectStore,
    private val supportService: SupportService,
) {
  private val log = perClassLogger()

  @EventListener
  fun on(event: DeliverableDocumentUploadFailedEvent) {
    try {
      val deliverablesRow = deliverablesDao.fetchOneById(event.deliverableId)
      val project = projectStore.fetchOneById(event.projectId)
      val organization = organizationStore.fetchOneById(project.organizationId)
      val participant = project.participantId?.let { participantStore.fetchOneById(it) }

      val description =
          """
            An error occurred when a user tried to upload a document for a deliverable. This is a system-generated support ticket.
            
            Reason: ${event.reason.description}
            
            Organization: ${organization.name}
            Participant: ${participant?.name ?: "N/A"}
            Project: ${project.name}
            
            Deliverable: ${deliverablesRow?.name ?: "N/A"} (ID ${event.deliverableId})
            
            Service: ${event.documentStore}
            Folder: ${event.documentStoreFolder ?: "Not configured"}
            File: ${event.fileName ?: "N/A"}
            
            System error: ${event.exception}
          """
              .trimIndent()

      if (config.atlassian.enabled) {
        supportService.submitServiceRequest(
            SupportRequestType.BugReport,
            "Document upload failed",
            description,
        )
      } else {
        log.info("Atlassian integration disabled; would file support ticket:\n$description")
      }
    } catch (e: Exception) {
      log.error("Failed to file support ticket for upload failure", e)
    }
  }
}
