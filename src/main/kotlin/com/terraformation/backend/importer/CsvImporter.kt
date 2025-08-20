package com.terraformation.backend.importer

import com.opencsv.CSVReader
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.UploadNotAwaitingActionException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadStatus
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.tables.daos.UploadProblemsDao
import com.terraformation.backend.db.default_schema.tables.daos.UploadsDao
import com.terraformation.backend.db.default_schema.tables.pojos.UploadsRow
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.UploadService
import com.terraformation.backend.file.UploadStore
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.i18n.use
import com.terraformation.backend.log.perClassLogger
import java.io.InputStream
import java.io.InputStreamReader
import org.jobrunr.jobs.JobId
import org.jooq.DSLContext

abstract class CsvImporter(
    protected val dslContext: DSLContext,
    private val fileStore: FileStore,
    private val uploadProblemsDao: UploadProblemsDao,
    private val uploadsDao: UploadsDao,
    private val uploadService: UploadService,
    private val uploadStore: UploadStore,
    private val userStore: UserStore,
) {
  protected val log = perClassLogger()

  /**
   * Imports the contents of a CSV file into the system. When this is called, the CSV file has
   * already passed validation and, if needed, the user has already decided whether or not to
   * overwrite conflicting data.
   */
  abstract fun doImportCsv(uploadsRow: UploadsRow, csvReader: CSVReader, overwriteExisting: Boolean)

  /**
   * Enqueues a JobRunr job to validate a CSV file. This needs to be implemented separately in each
   * subclass so that JobRunr detects which importer class to use when it runs the job.
   */
  protected abstract fun enqueueValidateCsv(uploadId: UploadId): JobId

  /**
   * Enqueues a JobRunr job to import a CSV file. This needs to be implemented separately in each
   * subclass so that JobRunr detects which importer class to use when it runs the job.
   */
  protected abstract fun enqueueImportCsv(uploadId: UploadId, overwriteExisting: Boolean): JobId

  /**
   * Returns a [CsvValidator] with appropriate validation logic for the kind of CSV file this
   * importer accepts.
   */
  abstract fun getValidator(uploadsRow: UploadsRow): CsvValidator

  /**
   * Returns the path to the template file for this importer's type of CSV file. Template files live
   * under `src/main/resources`. This path must start with a forward slash.
   *
   * Localized versions of the template have an underscore and the locale code before the `.csv`
   * extension, e.g., a template `/foo/bar/baz.csv` would have a localized version
   * `/foo/bar/baz_gx.csv`. This property should point to the base (English) version.
   */
  abstract val templatePath: String

  fun getCsvTemplate(): ByteArray {
    val locale = currentLocale()
    val suffixes =
        listOf(
            // Full locale tag, possibly including extension properties
            "_$locale.csv",
            "_${locale.language}_${locale.country}.csv",
            "_${locale.language}.csv",
            ".csv",
        )

    return suffixes.firstNotNullOfOrNull { suffix ->
      val basePath = templatePath.substringBeforeLast(".csv")
      javaClass.getResourceAsStream("$basePath$suffix")?.use { it.readAllBytes() }
    } ?: throw IllegalStateException("BUG! Can't load CSV template.")
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
      enqueueImportCsv(uploadId, overwriteExisting)
      uploadStore.updateStatus(uploadId, UploadStatus.AwaitingProcessing)
      uploadStore.deleteProblems(uploadId)
    }
  }

  @Suppress("MemberVisibilityCanBePrivate") // Called by JobRunr
  fun validateCsv(uploadId: UploadId) {
    withUpload(uploadId) { uploadsRow ->
      log.debug("Validating uploaded ${uploadsRow.typeId} $uploadId")

      val validator = getValidator(uploadsRow)

      fileStore.read(uploadsRow.storageUrl!!).use { inputStream -> validator.validate(inputStream) }

      dslContext.transaction { _ ->
        // If there are errors, don't bother recording any warnings since the user will be unable
        // to resolve them anyway.
        if (validator.errors.isNotEmpty()) {
          log.info("Uploaded ${uploadsRow.typeId} $uploadId has validation errors")

          uploadProblemsDao.insert(validator.errors)
          uploadStore.updateStatus(uploadId, UploadStatus.Invalid)
        } else if (validator.warnings.isNotEmpty()) {
          log.info("Uploaded ${uploadsRow.typeId} $uploadId has warnings; awaiting user action")

          uploadProblemsDao.insert(validator.warnings)
          uploadStore.updateStatus(uploadId, UploadStatus.AwaitingUserAction)
        } else {
          log.info("Uploaded ${uploadsRow.typeId} $uploadId has no problems; importing it")

          enqueueImportCsv(uploadId, true)
          uploadStore.updateStatus(uploadId, UploadStatus.AwaitingProcessing)
        }
      }
    }
  }

  @Suppress("MemberVisibilityCanBePrivate") // Called by JobRunr
  fun importCsv(uploadId: UploadId, overwriteExisting: Boolean) {
    withUpload(uploadId) { uploadsRow ->
      requirePermissions { readUpload(uploadId) }

      if (uploadsRow.statusId != UploadStatus.AwaitingProcessing) {
        log.error("Upload $uploadId has status ${uploadsRow.statusId}; unable to process")
        throw IllegalStateException("Upload is not awaiting processing")
      }

      val storageUrl = uploadsRow.storageUrl!!

      log.info(
          "Importing ${uploadsRow.typeId} $uploadId for organization " +
              "${uploadsRow.organizationId} facility ${uploadsRow.facilityId} " +
              "overwrite $overwriteExisting"
      )

      uploadStore.updateStatus(uploadId, UploadStatus.Processing)

      try {
        fileStore.read(storageUrl).use { inputStream ->
          val csvReader = CSVReader(InputStreamReader(inputStream))

          doImportCsv(uploadsRow, csvReader, overwriteExisting)

          uploadStore.updateStatus(uploadId, UploadStatus.Completed)
        }
      } catch (e: Exception) {
        log.error("Unable to process ${uploadsRow.typeId} $uploadId", e)
        uploadStore.updateStatus(uploadId, UploadStatus.ProcessingFailed)
      }
    }
  }

  /**
   * Saves a CSV file to the file store and enqueues an asynchronous job to validate it. Subclasses
   * should call this after doing whatever sanity checks (permissions, etc.) need to happen before
   * the import process begins.
   */
  protected fun doReceiveCsv(
      inputStream: InputStream,
      fileName: String,
      type: UploadType,
      organizationId: OrganizationId?,
      facilityId: FacilityId? = null,
  ): UploadId {
    val uploadId =
        uploadService.receive(inputStream, fileName, "text/csv", type, organizationId, facilityId)

    val jobId = enqueueValidateCsv(uploadId)

    log.info("Enqueued job $jobId to process uploaded CSV $uploadId")

    return uploadId
  }

  /**
   * Runs a function as the user who owns a particular upload, and in the locale that was specified
   * in the upload request.
   */
  private fun withUpload(uploadId: UploadId, func: (UploadsRow) -> Unit) {
    val uploadsRow =
        uploadsDao.fetchOneById(uploadId)
            ?: run {
              log.error("Upload $uploadId not found; cannot process it")
              return
            }

    val uploadUser = userStore.fetchOneById(uploadsRow.createdBy!!)
    uploadUser.run { uploadsRow.locale!!.use { func(uploadsRow) } }
  }
}
