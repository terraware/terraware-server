package com.terraformation.backend.accelerator

import com.opencsv.CSVWriter
import com.terraformation.backend.accelerator.db.ParticipantProjectSpeciesStore
import com.terraformation.backend.accelerator.db.SubmissionForProjectDeliverableNotFoundException
import com.terraformation.backend.accelerator.db.SubmissionSnapshotNotFoundException
import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.accelerator.event.DeliverableStatusUpdatedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedEvent
import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.NewParticipantProjectSpeciesModel
import com.terraformation.backend.api.writeNext
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.daos.DeliverablesDao
import com.terraformation.backend.db.accelerator.tables.daos.SubmissionSnapshotsDao
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSION_SNAPSHOTS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.species.event.SpeciesEditedEvent
import jakarta.inject.Named
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType

@Named
class ParticipantProjectSpeciesService(
    private val clock: Clock,
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

      val deliverableId = ensureSubmission(existingModel)
      publishAddedEvent(deliverableId, existingModel)

      existingModel
    }
  }

  /**
   * Creates a participant project species for each projectId - speciesId combination and create a
   * species deliverable submission for each project that doesn't have one
   */
  fun create(
      projectIds: Set<ProjectId>,
      speciesIds: Set<SpeciesId>,
  ): List<ExistingParticipantProjectSpeciesModel> {
    return dslContext.transactionResult { _ ->
      val existingModels = participantProjectSpeciesStore.create(projectIds, speciesIds)

      // Used to save relatively expensive queries for projects which we know have submissions
      val projectDeliverableIds = mutableMapOf<ProjectId, DeliverableId?>()
      val checkedProjects = mutableListOf<ProjectId>()

      existingModels.forEach { existingModel ->
        projectDeliverableIds[existingModel.projectId]?.let { publishAddedEvent(it, existingModel) }

        if (checkedProjects.contains(existingModel.projectId)) {
          return@forEach
        }

        val deliverableId = ensureSubmission(existingModel)
        publishAddedEvent(deliverableId, existingModel)

        projectDeliverableIds[existingModel.projectId] = deliverableId
        checkedProjects.add(existingModel.projectId)
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
   * When a species deliverable status is updated to "Approved", we save a snapshot of the species
   * list and reference it in the deliverable submission. Since a deliverable can be "reset", and
   * therefore can move to the "Approved" status more than once, if a submission snapshot already
   * exists for the submission, it will be deleted and a new one will be created.
   */
  @EventListener
  fun on(event: DeliverableStatusUpdatedEvent) {
    if (event.newStatus != SubmissionStatus.Approved) {
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
    val submissionId = event.submissionId
    val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(ZonedDateTime.now(clock))
    val filename = "species-list-snapshot-$submissionId-$timestamp.csv"
    val metadata =
        FileMetadata.of(MediaType.valueOf("text/csv").toString(), filename, stream.size().toLong())

    dslContext.transaction { _ ->
      deleteSubmissionSnapshotFile(submissionId)
      createSubmissionSnapshotFile(inputStream, metadata, submissionId)
    }
  }

  fun readSubmissionSnapshotFile(
      projectId: ProjectId,
      deliverableId: DeliverableId,
  ): SizedInputStream {
    val submissionRecord =
        with(SUBMISSIONS) {
          dslContext.fetchOne(this, PROJECT_ID.eq(projectId).and(DELIVERABLE_ID.eq(deliverableId)))
              ?: throw SubmissionForProjectDeliverableNotFoundException(deliverableId, projectId)
        }

    val submissionId = submissionRecord.id!!

    requirePermissions { readSubmission(submissionId) }

    // Get the snapshot that belongs to the submission
    val snapshotRow =
        submissionSnapshotsDao.fetchOne(SUBMISSION_SNAPSHOTS.SUBMISSION_ID, submissionId)
            ?: throw SubmissionSnapshotNotFoundException(submissionId)

    return fileService.readFile(snapshotRow.fileId!!)
  }

  private fun createSubmissionSnapshotFile(
      inputStream: ByteArrayInputStream,
      metadata: NewFileMetadata,
      submissionId: SubmissionId,
  ) =
      fileService.storeFile("species-list-deliverable", inputStream, metadata) { fileId ->
        with(SUBMISSION_SNAPSHOTS) {
          dslContext
              .insertInto(SUBMISSION_SNAPSHOTS)
              .set(SUBMISSION_ID, submissionId)
              .set(FILE_ID, fileId)
              .execute()
        }
      }

  /** Delete a submission snapshot file, if it exists */
  private fun deleteSubmissionSnapshotFile(submissionId: SubmissionId) =
      with(SUBMISSION_SNAPSHOTS) {
        val fileId =
            dslContext
                .select(FILE_ID)
                .from(SUBMISSION_SNAPSHOTS)
                .where(SUBMISSION_ID.eq(submissionId))
                .fetchOne(FILE_ID)

        if (fileId != null) {
          dslContext.deleteFrom(SUBMISSION_SNAPSHOTS).where(FILE_ID.eq(fileId)).execute()

          eventPublisher.publishEvent(FileReferenceDeletedEvent(fileId))
        }
      }

  /**
   * If a Participant Project Species is created, and there is either an active or recent
   * deliverable, a submission must be created for the deliverable.
   */
  private fun ensureSubmission(
      existingModel: ExistingParticipantProjectSpeciesModel
  ): DeliverableId? {
    val deliverableSubmission =
        submissionStore.fetchMostRecentSpeciesDeliverableSubmission(existingModel.projectId)

    // If a deliverable exists, but not a submission, create one
    if (deliverableSubmission != null && deliverableSubmission.submissionId == null) {
      submissionStore.createSubmission(deliverableSubmission.deliverableId, existingModel.projectId)
    }

    return deliverableSubmission?.deliverableId
  }

  /**
   * Publish an event that a participant project species was added to a deliverable, if it exists
   */
  private fun publishAddedEvent(
      deliverableId: DeliverableId?,
      participantProjectSpecies: ExistingParticipantProjectSpeciesModel,
  ) {
    if (deliverableId != null) {
      eventPublisher.publishEvent(
          ParticipantProjectSpeciesAddedEvent(
              deliverableId = deliverableId,
              participantProjectSpecies = participantProjectSpecies,
          )
      )
    }
  }
}
