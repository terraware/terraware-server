package com.terraformation.backend.species.db

import com.opencsv.CSVReader
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.GrowthForm
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.SeedStorageBehavior
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.UploadId
import com.terraformation.backend.db.UploadNotAwaitingActionException
import com.terraformation.backend.db.UploadNotFoundException
import com.terraformation.backend.db.UploadStatus
import com.terraformation.backend.db.UploadType
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.tables.daos.UploadProblemsDao
import com.terraformation.backend.db.tables.daos.UploadsDao
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.db.tables.references.UPLOADS
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.UploadService
import com.terraformation.backend.file.UploadStore
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Clock
import javax.annotation.ManagedBean
import org.apache.commons.lang3.BooleanUtils
import org.jobrunr.scheduling.JobScheduler
import org.jooq.DSLContext
import org.springframework.context.annotation.Lazy

@ManagedBean
class SpeciesImporter(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val fileStore: FileStore,
    private val messages: Messages,
    // JobRunr is disabled when generating OpenAPI docs from Gradle
    @Lazy private val scheduler: JobScheduler,
    private val speciesChecker: SpeciesChecker,
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
    requireAwaitingAction(uploadId)
    uploadService.delete(uploadId)
  }

  @Throws(UploadNotAwaitingActionException::class)
  fun resolveWarnings(uploadId: UploadId, overwriteExisting: Boolean) {
    requireAwaitingAction(uploadId)

    dslContext.transaction { _ ->
      scheduler.enqueue<SpeciesImporter> {
        importCsv(uploadId, currentUser().userId, overwriteExisting)
      }
      updateStatus(uploadId, UploadStatus.AwaitingProcessing)
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

    val uploadUser = userStore.fetchById(userId) ?: throw UserNotFoundException(userId)

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
          updateStatus(uploadId, UploadStatus.Invalid)
        } else if (validator.warnings.isNotEmpty()) {
          log.info("Uploaded species list $uploadId has warnings; awaiting user action")

          uploadProblemsDao.insert(validator.warnings)
          updateStatus(uploadId, UploadStatus.AwaitingUserAction)
        } else {
          log.info("Uploaded species list $uploadId had no problems; importing it")
          scheduler.enqueue<SpeciesImporter> { importCsv(uploadId, userId, true) }
          updateStatus(uploadId, UploadStatus.AwaitingProcessing)
        }
      }
    }
  }

  @Suppress("MemberVisibilityCanBePrivate") // Called by JobRunr
  fun importCsv(uploadId: UploadId, userId: UserId, overwriteExisting: Boolean) {
    log.info("Importing species list $uploadId for organization")

    val uploadUser = userStore.fetchById(userId) ?: throw UserNotFoundException(userId)
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

      updateStatus(uploadId, UploadStatus.Processing)

      try {
        var totalImported = 0
        var totalIgnored = 0

        dslContext.transaction { _ ->
          fileStore.read(storageUrl).use { inputStream ->
            val csvReader = CSVReader(InputStreamReader(inputStream))

            // Consume header line
            csvReader.readNext()

            csvReader.forEach { rawValues ->
              if (importRow(rawValues, organizationId, userId, overwriteExisting)) {
                totalImported++
              } else {
                totalIgnored++
              }
            }
          }

          // Check for misspelled species names or other problems by scanning all the unchecked
          // species in the organization. In theory, this could check species that we didn't just
          // import. But in practice, we try to run checks synchronously in the same transactions
          // that make changes to species data, so the only unchecked species that should be visible
          // here are the ones that have been newly inserted in the current transaction.
          speciesChecker.checkAllUncheckedSpecies(organizationId)

          updateStatus(uploadId, UploadStatus.Completed)
        }

        log.info("Imported $totalImported and ignored $totalIgnored species from upload $uploadId")
      } catch (e: Exception) {
        log.error("Unable to process species CSV $uploadId", e)
        updateStatus(uploadId, UploadStatus.ProcessingFailed)
      }
    }
  }

  /**
   * Inserts or updates a single species based on a row from the CSV.
   *
   * Species are matched based on their scientific names, but this is a little tricky because we
   * track renamed species and want to match on the original name. The use case is someone uploading
   * a CSV, accepting a suggested name change, then uploading the CSV again; we want to remember
   * that species X in the CSV is really species Y in our database because the user renamed it.
   *
   * In addition, this needs to behave differently if there is an existing species but it is marked
   * as deleted; sometimes we want to undelete the existing row and sometimes we want to ignore it.
   * And it also needs to act differently depending on whether the user wants to overwrite existing
   * data or only import new entries.
   *
   * Exhaustive list of the possible cases:
   *
   * * Current = the scientific name from the CSV is the same as the scientific name of an existing
   * species (regardless of whether the existing species is deleted or not)
   * * Initial = the scientific name from the CSV is the same as the initial scientific name of an
   * existing species (regardless of whether the existing species is deleted or not)
   * * Deleted = the existing species, if any, is marked as deleted
   * * Overwrite = the [overwriteExisting] parameter is true, meaning the user wants to update
   * existing species rather than ignore them
   *
   * ```
   * | Current | Initial | Deleted | Overwrite | Action |
   * | ------- | ------- | ------- | --------- | ------ |
   * | No      | No      | No      | No        | Insert |
   * | No      | No      | No      | Yes       | Insert |
   * | No      | No      | Yes     | No        | (impossible) |
   * | No      | No      | Yes     | Yes       | (impossible) |
   * | No      | Yes     | No      | No        | No-op |
   * | No      | Yes     | No      | Yes       | Update but use current name instead of CSV's |
   * | No      | Yes     | Yes     | No        | Insert |
   * | No      | Yes     | Yes     | Yes       | Insert |
   * | Yes     | No      | No      | No        | No-op |
   * | Yes     | No      | No      | Yes       | Update |
   * | Yes     | No      | Yes     | No        | Update and undelete |
   * | Yes     | No      | Yes     | Yes       | Update and undelete |
   * | Yes     | Yes     | No      | No        | No-op |
   * | Yes     | Yes     | No      | Yes       | Update species with same current name |
   * | Yes     | Yes     | Yes     | No        | Update species with same current name; undelete |
   * | Yes     | Yes     | Yes     | Yes       | Update species with same current name; undelete |
   * ```
   */
  private fun importRow(
      rawValues: Array<out String>,
      organizationId: OrganizationId,
      userId: UserId,
      overwriteExisting: Boolean
  ): Boolean {
    val values = rawValues.map { it.trim().ifEmpty { null } }

    val scientificName = values[0]
    val commonName = values[1]
    val familyName = values[2]
    val endangered = BooleanUtils.toBooleanObject(values[3])
    val rare = BooleanUtils.toBooleanObject(values[4])
    val growthForm = values[5]?.let { GrowthForm.forDisplayName(it) }
    val seedStorageBehavior = values[6]?.let { SeedStorageBehavior.forDisplayName(it) }

    return with(SPECIES) {
      /**
       * Updates the editable values of an existing species and marks it as not deleted. Leaves the
       * initial scientific name as is.
       */
      fun updateExisting(speciesId: SpeciesId): Boolean {
        val rowsUpdated =
            dslContext
                .update(SPECIES)
                .set(COMMON_NAME, commonName)
                .set(FAMILY_NAME, familyName)
                .set(ENDANGERED, endangered)
                .set(RARE, rare)
                .set(GROWTH_FORM_ID, growthForm)
                .set(SEED_STORAGE_BEHAVIOR_ID, seedStorageBehavior)
                .setNull(DELETED_TIME)
                .setNull(DELETED_BY)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, clock.instant())
                .where(ID.eq(speciesId))
                .execute()
        return if (rowsUpdated == 1) {
          true
        } else {
          log.error("Expected to update 1 row for species $speciesId but got $rowsUpdated")
          false
        }
      }

      val existingByCurrentName =
          dslContext
              .select(SPECIES.ID, SPECIES.DELETED_TIME)
              .from(SPECIES)
              .where(ORGANIZATION_ID.eq(organizationId))
              .and(SCIENTIFIC_NAME.eq(scientificName))
              .fetchOne()
      val existingIdByCurrentName = existingByCurrentName?.get(ID)

      if (existingIdByCurrentName != null) {
        if (overwriteExisting || existingByCurrentName[DELETED_TIME] != null) {
          updateExisting(existingIdByCurrentName)
        } else {
          false
        }
      } else {
        val existingIdByInitialName =
            dslContext
                .select(SPECIES.ID)
                .from(SPECIES)
                .where(ORGANIZATION_ID.eq(organizationId))
                .and(INITIAL_SCIENTIFIC_NAME.eq(scientificName))
                .and(DELETED_TIME.isNull)
                .fetchOne(SPECIES.ID)

        if (existingIdByInitialName == null) {
          dslContext
              .insertInto(SPECIES)
              .set(SCIENTIFIC_NAME, scientificName)
              .set(INITIAL_SCIENTIFIC_NAME, scientificName)
              .set(COMMON_NAME, commonName)
              .set(FAMILY_NAME, familyName)
              .set(ENDANGERED, endangered)
              .set(RARE, rare)
              .set(GROWTH_FORM_ID, growthForm)
              .set(SEED_STORAGE_BEHAVIOR_ID, seedStorageBehavior)
              .set(CREATED_BY, userId)
              .set(CREATED_TIME, clock.instant())
              .set(MODIFIED_BY, userId)
              .set(MODIFIED_TIME, clock.instant())
              .set(ORGANIZATION_ID, organizationId)
              .execute()
          true
        } else if (overwriteExisting) {
          updateExisting(existingIdByInitialName)
        } else {
          false
        }
      }
    }
  }

  private fun updateStatus(uploadId: UploadId, status: UploadStatus) {
    dslContext
        .update(UPLOADS)
        .set(UPLOADS.STATUS_ID, status)
        .where(UPLOADS.ID.eq(uploadId))
        .execute()
  }

  private fun requireAwaitingAction(uploadId: UploadId) {
    val uploadsRow = uploadsDao.fetchOneById(uploadId) ?: throw UploadNotFoundException(uploadId)
    if (uploadsRow.statusId != UploadStatus.AwaitingUserAction) {
      throw UploadNotAwaitingActionException(uploadId)
    }
  }
}
