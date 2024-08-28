package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.db.CohortStore
import com.terraformation.backend.accelerator.db.DeliverablesImporter
import com.terraformation.backend.accelerator.db.ModuleEventStore
import com.terraformation.backend.accelerator.db.ModuleNotFoundException
import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.db.ModulesImporter
import com.terraformation.backend.accelerator.db.ParticipantStore
import com.terraformation.backend.accelerator.model.CohortDepth
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.importer.CsvImportFailedException
import com.terraformation.backend.log.perClassLogger
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.jooq.DSLContext
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
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
    private val eventStore: ModuleEventStore,
    private val participantStore: ParticipantStore,
    private val projectStore: ProjectStore,
    private val modulesImporter: ModulesImporter,
    private val moduleStore: ModuleStore,
) {
  private val log = perClassLogger()
  private val dateFormat = "yyyy-MM-dd'T'HH:mm"

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
  fun moduleView(
      model: Model,
      @PathVariable moduleId: ModuleId,
      redirectAttributes: RedirectAttributes
  ): String {
    val module =
        try {
          moduleStore.fetchOneById(moduleId)
        } catch (e: ModuleNotFoundException) {
          log.warn("Module not found")
          redirectAttributes.failureMessage = "Module not found: ${e.message}"
          return redirectToModulesHome()
        }

    val cohorts = cohortStore.findByModule(moduleId, CohortDepth.Participant)
    val participants =
        cohorts.flatMap { it.participantIds }.map { participantStore.fetchOneById(it) }
    val moduleProjects = participants.flatMap { it.projectIds }

    val cohortNames = cohorts.associate { it.id to it.name }

    val projects = moduleProjects.associateWith { projectStore.fetchOneById(it) }
    val projectNames = projects.mapValues { it.value.name }

    // cohort name - project name
    val cohortProjectNames =
        projects.mapValues {
          cohortStore
              .fetchOneById(participantStore.fetchOneById(it.value.participantId!!).cohortId!!)
              .name + " - " + it.value.name
        }

    val eventSessions = eventStore.fetchById(moduleId = moduleId).associateBy { it.eventType }

    model.addAttribute("canManageModules", currentUser().canManageModules())
    model.addAttribute("cohortNames", cohortNames)
    model.addAttribute("cohortProjectNames", cohortProjectNames)
    model.addAttribute("dateFormat", dateFormat)
    model.addAttribute("eventSessions", eventSessions)
    model.addAttribute("module", module)
    model.addAttribute("moduleProjects", moduleProjects)
    model.addAttribute("projectNames", projectNames)

    return "/admin/moduleView"
  }

  @PostMapping("/modules/{moduleId}/createEvent")
  fun createModuleEvent(
      model: Model,
      @PathVariable moduleId: String,
      @RequestParam startTime: String,
      @RequestParam endTime: String?,
      @RequestParam eventTypeId: Int,
      @RequestParam meetingUrl: URI?,
      @RequestParam recordingUrl: URI?,
      @RequestParam slidesUrl: URI?,
      @RequestParam toAdd: List<ProjectId>?,
      redirectAttributes: RedirectAttributes
  ): String {
    try {
      val eventArgs = EventArgs(eventTypeId, startTime, endTime)

      val event =
          eventStore.create(
              ModuleId(moduleId),
              eventArgs.eventType,
              eventArgs.startTime,
              eventArgs.endTime,
              meetingUrl,
              recordingUrl,
              slidesUrl,
              toAdd?.toSet() ?: emptySet())

      redirectAttributes.successMessage = "Event created. id=${event.id}"
    } catch (e: Exception) {
      log.warn("Create event failed", e)
      redirectAttributes.failureMessage = "Create event failed: ${e.message}"
    }
    return redirectToModule(ModuleId(moduleId))
  }

  @PostMapping("/modules/{moduleId}/updateEvent")
  fun updateModuleEvent(
      model: Model,
      @PathVariable moduleId: String,
      @RequestParam id: EventId,
      @RequestParam startTime: String,
      @RequestParam endTime: String?,
      @RequestParam meetingUrl: URI?,
      @RequestParam recordingUrl: URI?,
      @RequestParam slidesUrl: URI?,
      @RequestParam toAdd: List<ProjectId>?,
      @RequestParam toRemove: List<ProjectId>?,
      redirectAttributes: RedirectAttributes
  ): String {
    val event = eventStore.fetchOneById(id)
    val eventArgs = EventArgs(event.eventType, startTime, endTime)

    val setToAdd = toAdd?.toSet() ?: emptySet()
    val setToRemove = toRemove?.toSet() ?: emptySet()

    val projects = event.projects.plus(setToAdd).minus(setToRemove)

    try {
      eventStore.updateEvent(id) {
        event.copy(
            startTime = eventArgs.startTime,
            endTime = eventArgs.endTime,
            meetingUrl = meetingUrl,
            recordingUrl = recordingUrl,
            slidesUrl = slidesUrl,
            projects = projects)
      }
      redirectAttributes.successMessage = "Event updated."
    } catch (e: Exception) {
      log.warn("Update event failed", e)
      redirectAttributes.failureMessage = "Update event failed: ${e.message}"
    }
    return redirectToModule(ModuleId(moduleId))
  }

  @PostMapping("/modules/{moduleId}/deleteEvent")
  fun deleteModuleEvent(
      model: Model,
      @PathVariable moduleId: String,
      @RequestParam id: EventId,
      redirectAttributes: RedirectAttributes
  ): String {
    try {
      eventStore.delete(id)
      redirectAttributes.successMessage = "Event deleted."
    } catch (e: Exception) {
      log.warn("Delete event failed", e)
      redirectAttributes.failureMessage = "Delete event failed: ${e.message}"
    }
    return redirectToModule(ModuleId(moduleId))
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

  private fun dateStringToInstant(dateString: String): Instant {
    val formatter = DateTimeFormatter.ofPattern(dateFormat)
    return LocalDateTime.parse(dateString, formatter).atZone(ZoneId.of("UTC")).toInstant()
  }

  private fun redirectToModulesHome() = "redirect:/admin/modules"

  private fun redirectToModule(moduleId: ModuleId) = "redirect:/admin/modules/$moduleId"

  /** Parsing logic for event arguments whose interpretation can vary based on event type. */
  private inner class EventArgs(
      val eventType: EventType,
      startTimeString: String,
      endTimeString: String?
  ) {
    val startTime: Instant = dateStringToInstant(startTimeString)
    val endTime: Instant =
        when {
          eventType == EventType.RecordedSession -> startTime
          endTimeString != null -> dateStringToInstant(endTimeString)
          else -> throw IllegalArgumentException("No end time specified")
        }

    constructor(
        eventTypeId: Int,
        startTimeString: String,
        endTimeString: String?
    ) : this(
        EventType.forId(eventTypeId) ?: throw IllegalArgumentException("Event type not recognized"),
        startTimeString,
        endTimeString)
  }
}
