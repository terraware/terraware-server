package com.terraformation.backend.species.db

import com.opencsv.CSVReader
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.GrowthForm
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.SeedStorageBehavior
import com.terraformation.backend.db.UploadId
import com.terraformation.backend.db.UploadNotAwaitingActionException
import com.terraformation.backend.db.UploadNotFoundException
import com.terraformation.backend.db.UploadStatus
import com.terraformation.backend.db.UploadType
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.tables.daos.UploadProblemsDao
import com.terraformation.backend.db.tables.daos.UploadsDao
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.UploadService
import com.terraformation.backend.file.UploadStore
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger
import java.io.InputStream
import java.io.InputStreamReader
import javax.annotation.ManagedBean
import org.apache.commons.lang3.BooleanUtils
import org.jobrunr.scheduling.JobScheduler
import org.jooq.DSLContext
import org.springframework.context.annotation.Lazy

@ManagedBean
class SpeciesImporter(
    private val dslContext: DSLContext,
    private val fileStore: FileStore,
    private val messages: Messages,
    // JobRunr is disabled when generating OpenAPI docs from Gradle
    @Lazy private val scheduler: JobScheduler,
    private val speciesChecker: SpeciesChecker,
    private val speciesStore: SpeciesStore,
    private val uploadProblemsDao: UploadProblemsDao,
    private val uploadsDao: UploadsDao,
    private val uploadService: UploadService,
    private val uploadStore: UploadStore,
    private val userStore: UserStore,
) {
  private val log = perClassLogger()

  fun getCsvTemplate(): String {
    return SPECIES_CSV_HEADERS.joinToString(",") + "\r\n"
  }

  fun receiveCsv(
      inputStream: InputStream,
      fileName: String,
      organizationId: OrganizationId,
  ): UploadId {
    requirePermissions { createSpecies(organizationId) }

    val uploadId =
        uploadService.receive(
            inputStream, fileName, "text/csv", UploadType.SpeciesCSV, organizationId)

    val jobId = scheduler.enqueue<SpeciesImporter> { validateCsv(uploadId, currentUser().userId) }

    log.info("Enqueued job $jobId to process uploaded CSV $uploadId")

    return uploadId
  }

  @Throws(UploadNotAwaitingActionException::class)
  fun cancelProcessing(uploadId: UploadId) {
    uploadStore.requireAwaitingAction(uploadId)
    uploadService.delete(uploadId)
  }

  @Throws(UploadNotAwaitingActionException::class)
  fun resolveWarnings(uploadId: UploadId, overwriteExisting: Boolean) {
    uploadStore.requireAwaitingAction(uploadId)

    dslContext.transaction { _ ->
      scheduler.enqueue<SpeciesImporter> {
        importCsv(uploadId, currentUser().userId, overwriteExisting)
      }
      uploadStore.updateStatus(uploadId, UploadStatus.AwaitingProcessing)
      uploadStore.deleteProblems(uploadId)
    }
  }

  @Suppress("MemberVisibilityCanBePrivate") // Called by JobRunr
  fun validateCsv(uploadId: UploadId, userId: UserId) {
    log.debug("Validating uploaded species list $uploadId")

    val uploadsRow =
        uploadsDao.fetchOneById(uploadId)
            ?: run {
              log.error("Upload $uploadId not found; cannot process it")
              return
            }
    val storageUrl =
        uploadsRow.storageUrl ?: throw IllegalStateException("Storage URL must be non-null")

    val uploadUser = userStore.fetchOneById(userId)

    uploadUser.run {
      val existingScientificNames =
          dslContext
              .select(SPECIES.SCIENTIFIC_NAME)
              .from(SPECIES)
              .where(SPECIES.ORGANIZATION_ID.eq(uploadsRow.organizationId))
              .and(SPECIES.DELETED_TIME.isNull)
              .fetch(SPECIES.SCIENTIFIC_NAME)
              .filterNotNull()
              .toSet()
      val existingRenames =
          dslContext
              .selectDistinct(SPECIES.INITIAL_SCIENTIFIC_NAME, SPECIES.SCIENTIFIC_NAME)
              .on(SPECIES.INITIAL_SCIENTIFIC_NAME)
              .from(SPECIES)
              .where(SPECIES.ORGANIZATION_ID.eq(uploadsRow.organizationId))
              .and(SPECIES.DELETED_TIME.isNull)
              .and(SPECIES.INITIAL_SCIENTIFIC_NAME.ne(SPECIES.SCIENTIFIC_NAME))
              .orderBy(SPECIES.INITIAL_SCIENTIFIC_NAME, SPECIES.SCIENTIFIC_NAME)
              .fetch { (initial, scientific) ->
                if (initial != null && scientific != null) {
                  initial to scientific
                } else {
                  null
                }
              }
              .filterNotNull()
              .toMap()

      val validator =
          SpeciesCsvValidator(uploadId, existingScientificNames, existingRenames, messages)

      fileStore.read(storageUrl).use { inputStream -> validator.validate(inputStream) }

      dslContext.transaction { _ ->
        // If there are errors, don't bother recording any warnings since the user will be unable
        // to resolve them anyway.
        if (validator.errors.isNotEmpty()) {
          log.info("Uploaded species list $uploadId has validation errors")

          uploadProblemsDao.insert(validator.errors)
          uploadStore.updateStatus(uploadId, UploadStatus.Invalid)
        } else if (validator.warnings.isNotEmpty()) {
          log.info("Uploaded species list $uploadId has warnings; awaiting user action")

          uploadProblemsDao.insert(validator.warnings)
          uploadStore.updateStatus(uploadId, UploadStatus.AwaitingUserAction)
        } else {
          log.info("Uploaded species list $uploadId had no problems; importing it")
          scheduler.enqueue<SpeciesImporter> { importCsv(uploadId, userId, true) }
          uploadStore.updateStatus(uploadId, UploadStatus.AwaitingProcessing)
        }
      }
    }
  }

  @Suppress("MemberVisibilityCanBePrivate") // Called by JobRunr
  fun importCsv(uploadId: UploadId, userId: UserId, overwriteExisting: Boolean) {
    log.info("Importing species list $uploadId for organization")

    val uploadUser = userStore.fetchOneById(userId)
    uploadUser.run {
      val uploadsRow = uploadsDao.fetchOneById(uploadId) ?: throw UploadNotFoundException(uploadId)
      val storageUrl =
          uploadsRow.storageUrl ?: throw IllegalStateException("Storage URL must be non-null")
      val organizationId =
          uploadsRow.organizationId
              ?: throw IllegalStateException("Organization ID must be non-null")

      requirePermissions {
        readUpload(uploadId)
        createSpecies(organizationId)
      }

      if (uploadsRow.statusId != UploadStatus.AwaitingProcessing) {
        log.error("Upload $uploadId has status ${uploadsRow.statusId}; unable to process")
        throw IllegalStateException("Upload is not awaiting processing")
      }

      uploadStore.updateStatus(uploadId, UploadStatus.Processing)

      try {
        var totalImported = 0

        dslContext.transaction { _ ->
          fileStore.read(storageUrl).use { inputStream ->
            val csvReader = CSVReader(InputStreamReader(inputStream))

            // Consume header line
            csvReader.readNext()

            csvReader
                .map { rawValues -> rawValues.map { it.trim().ifEmpty { null } } }
                .map { values ->
                  SpeciesRow(
                      scientificName = values[0],
                      commonName = values[1],
                      familyName = values[2],
                      endangered = BooleanUtils.toBooleanObject(values[3]),
                      rare = BooleanUtils.toBooleanObject(values[4]),
                      growthFormId = values[5]?.let { GrowthForm.forDisplayName(it) },
                      seedStorageBehaviorId =
                          values[6]?.let { SeedStorageBehavior.forDisplayName(it) },
                      organizationId = organizationId,
                  )
                }
                .forEach { row ->
                  speciesStore.importRow(row, overwriteExisting)
                  totalImported++
                }
          }

          // Check for misspelled species names or other problems by scanning all the unchecked
          // species in the organization. In theory, this could check species that we didn't just
          // import. But in practice, we try to run checks synchronously in the same transactions
          // that make changes to species data, so the only unchecked species that should be visible
          // here are the ones that have been newly inserted in the current transaction.
          speciesChecker.checkAllUncheckedSpecies(organizationId)

          uploadStore.updateStatus(uploadId, UploadStatus.Completed)
        }

        log.info("Processed $totalImported species from upload $uploadId")
      } catch (e: Exception) {
        log.error("Unable to process species CSV $uploadId", e)
        uploadStore.updateStatus(uploadId, UploadStatus.ProcessingFailed)
      }
    }
  }
}
