package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.db.default_schema.AssetStatus
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.daos.FilesDao
import com.terraformation.backend.db.default_schema.tables.daos.SplatsDao
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.default_schema.tables.references.SPLATS
import com.terraformation.backend.db.default_schema.tables.references.THUMBNAILS
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.splat.SplatGenerationParams
import com.terraformation.backend.splat.SplatService
import java.net.URI
import java.nio.file.NoSuchFileException
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@ConditionalOnProperty("terraware.splatter.enabled")
@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminSplatsController(
    private val dslContext: DSLContext,
    private val filesDao: FilesDao,
    private val fileStore: FileStore,
    private val organizationStore: OrganizationStore,
    private val splatService: SplatService,
    private val splatsDao: SplatsDao,
    private val thumbnailStore: ThumbnailStore,
) {
  companion object {
    private const val THUMBNAIL_HEIGHT = 80
  }

  @GetMapping("/splats")
  fun splatsHome(model: Model): String {
    if (!model.containsAttribute("stepArgs")) {
      model.addAttribute("stepArgs", emptyMap<String, String>())
    }
    return "/admin/splats"
  }

  @PostMapping("/splats/process")
  fun processSplat(
      @RequestParam observationId: ObservationId?,
      @RequestParam organizationId: OrganizationId?,
      @RequestParam fileId: FileId,
      @RequestParam abortAfter: String?,
      @RequestParam dataFactor: Int?,
      @RequestParam densStrategy: String?,
      @RequestParam featureMatcherSubcommand: String?,
      @RequestParam fps: Int?,
      @RequestParam keepPercent: Double?,
      @RequestParam matchingMode: String?,
      @RequestParam maxSize: Int?,
      @RequestParam maxSteps: Int?,
      @RequestParam restartAt: String?,
      @RequestParam runBirdNet: Boolean?,
      @RequestParam ssimLambda: Double?,
      @RequestParam tailPruning: Boolean?,
      payload: AdminProcessSplatRequestPayload,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val stepArgs =
          listOfNotNull(
                  dataFactor?.let { "gsplat" to listOf("--data_factor", "$dataFactor") },
                  densStrategy?.let { "gsplat" to listOf("--strategy", it) },
                  featureMatcherSubcommand?.let { "feature-matcher" to listOf("--subcommand", it) },
                  fps?.let { "extract" to listOf("--fps", "$fps") },
                  keepPercent?.let {
                    if (keepPercent < 100.0) {
                      "filter-blurry" to listOf("--keep-percent", "${keepPercent / 100.0}")
                    } else {
                      "filter-blurry" to listOf("--no-filter-blurry")
                    }
                  },
                  matchingMode
                      ?.takeIf { it.isNotBlank() }
                      ?.let { "feature-matcher" to listOf("--matching-mode", it) },
                  maxSize?.let { "extract" to listOf("--max-size", "$maxSize") },
                  maxSteps?.let { "gsplat" to listOf("--max_steps", "$maxSteps") },
                  ssimLambda?.let { "gsplat" to listOf("--ssim_lambda", "$ssimLambda") },
                  tailPruning?.let {
                    if (tailPruning) {
                      "prune-tail" to listOf("--prune-tail")
                    } else {
                      "prune-tail" to listOf("--no-prune-tail")
                    }
                  },
              )
              .groupBy { it.first }
              .mapValues { (_, lists) -> lists.flatMap { it.second } }
              .toMutableMap()

      payload.stepArgs.forEach { (stepName, argsString) ->
        if (!argsString.isNullOrBlank()) {
          val splitArgs = argsString.split(' ')
          stepArgs.compute(stepName) { _, args ->
            if (args == null) splitArgs else args + splitArgs
          }
        }
      }

      val params = SplatGenerationParams(abortAfter, restartAt, stepArgs)

      if (organizationId != null) {
        splatService.generateOrganizationMediaSplat(
            organizationId,
            fileId,
            true,
            params,
            runBirdNet ?: false,
        )
      } else if (observationId != null) {
        splatService.generateObservationSplat(
            observationId,
            fileId,
            true,
            params,
            runBirdNet ?: false,
        )
      } else {
        throw IllegalArgumentException("Either observationId or organizationId must be specified")
      }

      val storageUrl = filesDao.fetchOneById(fileId)?.storageUrl
      val modelUrl = splatsDao.fetchOneByFileId(fileId)?.splatStorageUrl
      val jobDirUrl = "$modelUrl${SplatService.JOB_ARCHIVE_SUFFIX}"

      redirectAttributes.successMessage =
          "Sent request to splatter service. Model and archive will not be available until " +
              "processing is finished."
      redirectAttributes.successDetails =
          listOf(
              "Video: $storageUrl",
              "Model: $modelUrl",
              "Archive: $jobDirUrl",
          )
    } catch (e: Exception) {
      redirectAttributes.failureMessage = "Splat generation failed: ${e.message}"
    }

    redirectAttributes.addFlashAttribute("observationId", "$observationId")
    redirectAttributes.addFlashAttribute("organizationId", "$organizationId")
    redirectAttributes.addFlashAttribute("fileId", "$fileId")
    redirectAttributes.addFlashAttribute("abortAfter", abortAfter)
    redirectAttributes.addFlashAttribute("dataFactor", dataFactor)
    redirectAttributes.addFlashAttribute("densStrategy", densStrategy)
    redirectAttributes.addFlashAttribute("featureMatcherSubcommand", featureMatcherSubcommand)
    redirectAttributes.addFlashAttribute("fps", fps)
    redirectAttributes.addFlashAttribute("keepPercent", keepPercent)
    redirectAttributes.addFlashAttribute("matchingMode", matchingMode)
    redirectAttributes.addFlashAttribute("maxSize", maxSize)
    redirectAttributes.addFlashAttribute("maxSteps", maxSteps)
    redirectAttributes.addFlashAttribute("restartAt", restartAt)
    redirectAttributes.addFlashAttribute("runBirdNet", runBirdNet)
    redirectAttributes.addFlashAttribute("ssimLambda", ssimLambda)
    redirectAttributes.addFlashAttribute("tailPruning", tailPruning)
    redirectAttributes.addFlashAttribute("stepArgs", payload.stepArgs)

    return redirectToSplatsHome()
  }

  @GetMapping("/splats/organization/{organizationId}")
  fun listOrganizationSplats(@PathVariable organizationId: OrganizationId, model: Model): String {
    val organization = organizationStore.fetchOneById(organizationId)
    val hasThumbnailField =
        DSL.field(
            DSL.exists(
                DSL.selectOne()
                    .from(THUMBNAILS)
                    .where(THUMBNAILS.FILE_ID.eq(FILES.ID))
                    .and(
                        THUMBNAILS.IS_FULL_SIZE.eq(true).or(THUMBNAILS.HEIGHT.eq(THUMBNAIL_HEIGHT))
                    )
            )
        )

    val splats =
        dslContext
            .select(
                SPLATS.FILE_ID,
                SPLATS.ASSET_STATUS_ID,
                SPLATS.ERROR_MESSAGE,
                SPLATS.NEEDS_ATTENTION,
                FILES.CREATED_TIME,
                FILES.FILE_NAME,
                FILES.STORAGE_URL,
                hasThumbnailField,
            )
            .from(SPLATS)
            .join(FILES)
            .on(SPLATS.FILE_ID.eq(FILES.ID))
            .where(SPLATS.ORGANIZATION_ID.eq(organizationId))
            .orderBy(FILES.CREATED_TIME.desc(), SPLATS.FILE_ID.desc())
            .fetch { record ->
              val status = record[SPLATS.ASSET_STATUS_ID]!!
              AdminSplatRow(
                  fileId = record[SPLATS.FILE_ID]!!,
                  errorMessage = record[SPLATS.ERROR_MESSAGE],
                  filename = record[FILES.FILE_NAME],
                  hasJobArchive = status == AssetStatus.Ready || status == AssetStatus.Errored,
                  hasModel = status == AssetStatus.Ready,
                  hasThumbnail = record[hasThumbnailField] == true,
                  needsAttention = record[SPLATS.NEEDS_ATTENTION] == true,
                  status = status,
                  storageFilename = record[FILES.STORAGE_URL]?.lastPathSegment(),
                  uploadedTime = record[FILES.CREATED_TIME]!!,
              )
            }

    model.addAttribute("organization", organization)
    model.addAttribute("splats", splats)

    return "/admin/organizationSplats"
  }

  /**
   * Returns a thumbnail for a splat's video, but only if one has already been generated. If
   * [fullSize] is true, returns the full-sized still image if there is one, falling back to the
   * small thumbnail if not.
   */
  @GetMapping("/splats/{fileId}/thumbnail")
  @ResponseBody
  fun getSplatThumbnail(
      @PathVariable fileId: FileId,
      @RequestParam fullSize: Boolean?,
  ): ResponseEntity<InputStreamResource> {
    val fullSizeThumbnail =
        if (fullSize == true) thumbnailStore.getExistingThumbnailData(fileId, null, null) else null
    val thumbnail =
        fullSizeThumbnail
            ?: thumbnailStore.getExistingThumbnailData(fileId, null, THUMBNAIL_HEIGHT)
            ?: thumbnailStore.generateThumbnailFromExistingThumbnail(fileId, null, THUMBNAIL_HEIGHT)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    return thumbnail.toResponseEntity()
  }

  @GetMapping("/splats/{fileId}/model")
  @ResponseBody
  fun downloadSplatModel(@PathVariable fileId: FileId): ResponseEntity<InputStreamResource> {
    return downloadFile(fetchSplatStorageUrl(fileId))
  }

  @GetMapping("/splats/{fileId}/job")
  @ResponseBody
  fun downloadSplatJobArchive(@PathVariable fileId: FileId): ResponseEntity<InputStreamResource> {
    return downloadFile(URI("${fetchSplatStorageUrl(fileId)}${SplatService.JOB_ARCHIVE_SUFFIX}"))
  }

  private fun fetchSplatStorageUrl(fileId: FileId): URI =
      splatsDao.fetchOneByFileId(fileId)?.splatStorageUrl
          ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  private fun downloadFile(url: URI): ResponseEntity<InputStreamResource> {
    val stream =
        try {
          fileStore.read(url)
        } catch (_: NoSuchFileException) {
          throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }

    return stream.toResponseEntity {
      contentDisposition = ContentDisposition.attachment().filename(url.lastPathSegment()).build()
    }
  }

  private fun URI.lastPathSegment(): String = path.substringAfterLast('/')

  private fun redirectToSplatsHome() = "redirect:/admin/splats"
}

data class AdminSplatRow(
    val fileId: FileId,
    val errorMessage: String?,
    val filename: String?,
    val hasJobArchive: Boolean,
    val hasModel: Boolean,
    val hasThumbnail: Boolean,
    val needsAttention: Boolean,
    val status: AssetStatus,
    val storageFilename: String?,
    val uploadedTime: Instant,
)

/** "Payload" that accepts form fields with subscripted names such as `stepArgs[extract]`. */
data class AdminProcessSplatRequestPayload(
    val stepArgs: Map<String, String?>,
)
