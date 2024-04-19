package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.db.CohortStore
import com.terraformation.backend.accelerator.db.DeliverablesImporter
import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.db.ModulesImporter
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.importer.CsvImportFailedException
import com.terraformation.backend.log.perClassLogger
import org.jooq.DSLContext
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin, GlobalRole.AcceleratorAdmin])
@Validated
class AdminModulesController(
    private val cohortStore: CohortStore,
    private val deliverablesImporter: DeliverablesImporter,
    private val dslContext: DSLContext,
    private val modulesImporter: ModulesImporter,
    private val moduleStore: ModuleStore,
) {
  private val log = perClassLogger()

  @GetMapping("/modules")
  fun modulesHome(model: Model): String {
    val hasModules = dslContext.fetchExists(MODULES)
    val cohortNames = cohortStore.findAll().associateBy { it.id }.mapValues { it.value.name }
    val modules = moduleStore.fetchAllModules()

    model.addAttribute("cohortNames", cohortNames)
    model.addAttribute("canManageDeliverables", currentUser().canManageDeliverables())
    model.addAttribute("canManageModules", currentUser().canManageModules())
    model.addAttribute("hasModules", hasModules)
    model.addAttribute("modules", modules)

    return "/admin/modules"
  }

  @GetMapping("/modules/{moduleId}")
  fun moduleView(model: Model, @PathVariable moduleId: String): String {
    val module = moduleStore.fetchAllModules().firstOrNull { it.id == ModuleId(moduleId) }
    val hasModule = module != null

    model.addAttribute("hasModule", hasModule)
    model.addAttribute("canManageModules", currentUser().canManageModules())
    model.addAttribute("module", module)
    model.addAttribute("workshopInfo", module?.eventDescriptions?.get(EventType.Workshop))
    model.addAttribute("oneOnOneInfo", module?.eventDescriptions?.get(EventType.OneOnOneSession))
    model.addAttribute("liveSessionInfo", module?.eventDescriptions?.get(EventType.LiveSession))

    return "/admin/moduleView"
  }

  @PostMapping("/uploadDeliverables")
  fun uploadDeliverables(
      @RequestPart file: MultipartFile,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      file.inputStream.use { inputStream -> deliverablesImporter.importDeliverables(inputStream) }

      redirectAttributes.successMessage = "Deliverables imported successfully."
    } catch (e: CsvImportFailedException) {
      redirectAttributes.failureMessage = e.message
      redirectAttributes.failureDetails = e.errors.map { "Row ${it.rowNumber}: ${it.message}" }
    } catch (e: Exception) {
      log.warn("Deliverables import failed", e)
      redirectAttributes.failureMessage = "Import failed: ${e.message}"
    }

    return redirectToModulesHome()
  }

  @PostMapping("/uploadModules")
  fun uploadModules(
      @RequestPart file: MultipartFile,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      file.inputStream.use { inputStream -> modulesImporter.importModules(inputStream) }

      redirectAttributes.successMessage = "Modules imported successfully."
    } catch (e: CsvImportFailedException) {
      redirectAttributes.failureMessage = e.message
      redirectAttributes.failureDetails = e.errors.map { "Row ${it.rowNumber}: ${it.message}" }
    } catch (e: Exception) {
      log.warn("Modules import failed", e)
      redirectAttributes.failureMessage = "Import failed: ${e.message}"
    }

    return redirectToModulesHome()
  }

  private fun redirectToModulesHome() = "redirect:/admin/modules"
}
