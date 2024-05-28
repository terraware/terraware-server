package com.terraformation.backend.accelerator

import com.opencsv.CSVWriter
import com.terraformation.backend.accelerator.db.ParticipantProjectSpeciesStore
import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.accelerator.event.DeliverableStatusUpdatedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedEvent
import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.NewParticipantProjectSpeciesModel
import com.terraformation.backend.api.writeNext
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.daos.DeliverablesDao
import com.terraformation.backend.db.accelerator.tables.daos.SubmissionSnapshotsDao
import com.terraformation.backend.db.accelerator.tables.pojos.SubmissionSnapshotsRow
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.species.event.SpeciesEditedEvent
import jakarta.inject.Named
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType

@Named
class ParticipantProjectSpeciesService(
    private val dslContext: DSLContext,
    private val deliverablesDao: DeliverablesDao,
    private val eventPublisher: ApplicationEventPublisher,
    private val fileService: FileService,
    private val participantProjectSpeciesStore: ParticipantProjectSpeciesStore,
    private val submissionSnapshotsDao: SubmissionSnapshotsDao,
    private val submissionStore: SubmissionStore,
) {
  /** Creates a new participant project species, possibly creating a deliverable submission. */
  fun create(model: NewParticipantProjectSpeciesModel): ExistingParticipantProjectSpeciesModel {
    return dslContext.transactionResult { _ ->
      val existingModel = participantProjectSpeciesStore.create(model)

      // If a submission doesn't exist for the deliverable, create one
      val deliverableSubmission =
          submissionStore.fetchActiveSpeciesDeliverableSubmission(model.projectId)
      if (deliverableSubmission.submissionId == null) {
        submissionStore.createSubmission(deliverableSubmission.deliverableId, model.projectId)
      }

      eventPublisher.publishEvent(
          ParticipantProjectSpeciesAddedEvent(
              deliverableId = deliverableSubmission.deliverableId,
              participantProjectSpecies = existingModel,
          ),
      )

      existingModel
    }
  }

  /**
   * Creates a participant project species for each projectId - speciesId combination and create a
   * species deliverable submission for each project that doesn't have one
   */
  fun create(
      projectIds: Set<ProjectId>,
      speciesIds: Set<SpeciesId>
  ): List<ExistingParticipantProjectSpeciesModel> {
    return dslContext.transactionResult { _ ->
      val existingModels = participantProjectSpeciesStore.create(projectIds, speciesIds)

      // Used to save relatively expensive queries for projects which we know have submissions
      val projectDeliverableIds = mutableMapOf<ProjectId, DeliverableId>()

      existingModels.forEach { participantProjectSpecies ->
        projectDeliverableIds[participantProjectSpecies.projectId]?.let {
          publishAddedEvent(it, participantProjectSpecies)
          return@forEach
        }

        // A submission must exist for every project that is getting a new species assigned
        val deliverableSubmission =
            submissionStore.fetchActiveSpeciesDeliverableSubmission(
                participantProjectSpecies.projectId,
            )
        if (deliverableSubmission.submissionId == null) {
          submissionStore.createSubmission(
              deliverableSubmission.deliverableId,
              participantProjectSpecies.projectId,
          )
        }

        projectDeliverableIds[participantProjectSpecies.projectId] =
            deliverableSubmission.deliverableId
        publishAddedEvent(
            projectDeliverableIds[participantProjectSpecies.projectId]!!,
            participantProjectSpecies,
        )
      }

      existingModels
    }
  }

  /*
   * When a species is updated, if it belongs to an organization with participants and is associated
   * to a participant project, we need to update its status to "in review" across all
   * associated projects. This only applies to users with no accelerator related global roles.
   */
  @EventListener
  fun on(event: SpeciesEditedEvent) {
    if (currentUser().canReadAllAcceleratorDetails()) {
      return
    }

    val projects =
        participantProjectSpeciesStore.fetchParticipantProjectsForSpecies(event.species.id)

    dslContext.transaction { _ ->
      projects.forEach { project ->
        // Set all non "in review" participant project species to "in review" status
        if (project.participantProjectSpeciesSubmissionStatus !== SubmissionStatus.InReview) {
          participantProjectSpeciesStore.update(project.participantProjectSpeciesId) {
            it.copy(submissionStatus = SubmissionStatus.InReview)
          }
        }
      }
    }
  }

  /**
   * When a species deliverable status is updated to "approved", we save a snapshot of the species
   * list and reference it in the deliverable submission
   */
  @EventListener
  fun on(event: DeliverableStatusUpdatedEvent) {
    if (event.newStatus !== SubmissionStatus.Approved) {
      return
    }

    val deliverable = deliverablesDao.fetchOneById(event.deliverableId)
    if (deliverable == null || deliverable.deliverableTypeId != DeliverableType.Species) {
      return
    }

    val speciesData =
        participantProjectSpeciesStore.fetchSpeciesForParticipantProject(event.projectId)
    val rows: List<List<Pair<String, String?>>> =
        speciesData.map {
          listOf(
              "Project ID" to it.project.id.toString(),
              "Species ID" to it.species.id.toString(),
              "Status" to it.participantProjectSpecies.submissionStatus.jsonValue,
              "Rationale" to it.participantProjectSpecies.rationale,
              "Feedback" to it.participantProjectSpecies.feedback,
              "Internal Comment" to it.participantProjectSpecies.internalComment,
              "Native / Non-Native" to
                  it.participantProjectSpecies.speciesNativeCategory?.jsonValue,
              "Species Scientific Name" to it.species.scientificName,
              "Species Common Name" to it.species.commonName,
          )
        }

    val stream = ByteArrayOutputStream()
    val streamWriter = OutputStreamWriter(stream)

    CSVWriter(
            streamWriter,
            CSVWriter.DEFAULT_SEPARATOR,
            CSVWriter.DEFAULT_QUOTE_CHARACTER,
            CSVWriter.DEFAULT_ESCAPE_CHARACTER,
            CSVWriter.RFC4180_LINE_END,
        )
        .use { csvWriter ->
          csvWriter.writeNext(rows.first().map { it.first })
          rows.forEach { row -> csvWriter.writeNext(row.map { it.second }) }
        }

    val inputStream = ByteArrayInputStream(stream.toByteArray())
    val filename = "species-list-snapshot-${event.submissionId}-${Instant.now()}.csv"
    val metadata =
        FileMetadata.of(MediaType.valueOf("text/csv").toString(), filename, stream.size().toLong())

    fileService.storeFile("species-list-deliverable", inputStream, metadata, null) { fileId ->
      submissionSnapshotsDao.insert(
          SubmissionSnapshotsRow(submissionId = event.submissionId, fileId = fileId))
    }
  }

  private fun publishAddedEvent(
      deliverableId: DeliverableId,
      participantProjectSpecies: ExistingParticipantProjectSpeciesModel
  ) {
    eventPublisher.publishEvent(
        ParticipantProjectSpeciesAddedEvent(
            deliverableId = deliverableId,
            participantProjectSpecies = participantProjectSpecies,
        ),
    )
  }
}
