package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.db.ModulesImporter
import com.terraformation.backend.accelerator.model.ModuleDeliverableModel
import com.terraformation.backend.accelerator.model.ModuleModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ResponsePayload
import com.terraformation.backend.api.SuccessOrError
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.importer.CsvImportFailedException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Encoding
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/modules")
@RestController
class ModulesController(
    private val deliverableStore: DeliverableStore,
    private val moduleStore: ModuleStore,
    private val modulesImporter: ModulesImporter,
) {
  @ApiResponse200
  @ApiResponse404
  @GetMapping
  @Operation(summary = "List modules.")
  fun listModules(): ListModulesResponsePayload {
    val modules = moduleStore.fetchAllModules()
    return ListModulesResponsePayload(
        modules.map { module ->
          val deliverables = deliverableStore.fetchDeliverables(moduleId = module.id)
          ModulePayload(module, deliverables.map { ModuleDeliverablePayload(it) })
        }
    )
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{moduleId}")
  @Operation(summary = "Gets one module.")
  fun getModule(
      @PathVariable moduleId: ModuleId,
  ): GetModuleResponsePayload {
    val model = moduleStore.fetchOneById(moduleId)
    val deliverables = deliverableStore.fetchDeliverables(moduleId = moduleId)
    return GetModuleResponsePayload(
        ModulePayload(model, deliverables.map { ModuleDeliverablePayload(it) })
    )
  }

  @ApiResponse200
  @Operation(summary = "Import a list of modules. ")
  @PostMapping("/import", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content = [Content(encoding = [Encoding(name = "file", contentType = MediaType.ALL_VALUE)])]
  )
  fun importModules(
      @RequestPart(required = true) file: MultipartFile
  ): ImportModuleResponsePayload {
    try {
      file.inputStream.use { inputStream -> modulesImporter.importModules(inputStream) }
    } catch (e: CsvImportFailedException) {
      return ImportModuleResponsePayload(
          SuccessOrError.Error,
          e.errors.map { ImportModuleProblemElement(it.rowNumber, it.message) },
          e.message,
      )
    }
    return ImportModuleResponsePayload(SuccessOrError.Ok)
  }
}

data class ModulePayload(
    val id: ModuleId,
    val name: String,
    val additionalResources: String?,
    val overview: String?,
    val preparationMaterials: String?,
    val eventDescriptions: Map<EventType, String>,
    val deliverables: List<ModuleDeliverablePayload>,
) {
  constructor(
      model: ModuleModel,
      deliverables: List<ModuleDeliverablePayload>,
  ) : this(
      id = model.id,
      name = model.name,
      additionalResources = model.additionalResources,
      overview = model.overview,
      preparationMaterials = model.preparationMaterials,
      eventDescriptions = model.eventDescriptions,
      deliverables = deliverables,
  )
}

data class ModuleDeliverablePayload(
    val category: DeliverableCategory,
    @Schema(description = "Optional description of the deliverable in HTML form.")
    val descriptionHtml: String?,
    val id: DeliverableId,
    val name: String,
    val required: Boolean,
    val position: Int,
    val type: DeliverableType,
    val sensitive: Boolean,
) {
  constructor(
      model: ModuleDeliverableModel
  ) : this(
      model.category,
      model.descriptionHtml,
      model.id,
      model.name,
      model.required,
      model.position,
      model.type,
      model.sensitive,
  )
}

data class ImportModuleProblemElement(
    val row: Int,
    val problem: String,
)

data class ImportModuleResponsePayload(
    override val status: SuccessOrError,
    val problems: List<ImportModuleProblemElement> = emptyList(),
    val message: String? = null,
) : ResponsePayload

data class GetModuleResponsePayload(
    val module: ModulePayload,
) : SuccessResponsePayload

data class ListModulesResponsePayload(
    val modules: List<ModulePayload>,
) : SuccessResponsePayload
