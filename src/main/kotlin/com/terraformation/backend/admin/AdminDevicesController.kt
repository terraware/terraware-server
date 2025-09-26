package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.BalenaDeviceId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.DeviceTemplateCategory
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.tables.daos.DeviceTemplatesDao
import com.terraformation.backend.db.default_schema.tables.pojos.DeviceManagersRow
import com.terraformation.backend.db.default_schema.tables.pojos.DeviceTemplatesRow
import com.terraformation.backend.db.default_schema.tables.pojos.DevicesRow
import com.terraformation.backend.device.DeviceManagerService
import com.terraformation.backend.device.DeviceService
import com.terraformation.backend.device.db.DeviceManagerStore
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.time.DatabaseBackedClock
import java.util.UUID
import kotlin.random.Random
import org.jooq.JSONB
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminDevicesController(
    private val clock: DatabaseBackedClock,
    private val deviceManagerService: DeviceManagerService,
    private val deviceManagerStore: DeviceManagerStore,
    private val deviceService: DeviceService,
    private val deviceTemplatesDao: DeviceTemplatesDao,
    private val facilityStore: FacilityStore,
) {
  private val log = perClassLogger()

  @GetMapping("/deviceTemplates")
  fun getDeviceTemplates(model: Model): String {
    val templates = deviceTemplatesDao.findAll().sortedBy { it.id }

    model.addAttribute("canUpdateDeviceTemplates", currentUser().canUpdateDeviceTemplates())
    model.addAttribute("categories", DeviceTemplateCategory.entries)
    model.addAttribute("templates", templates)

    return "/admin/deviceTemplates"
  }

  @GetMapping("/deviceManagers")
  fun listDeviceManagers(model: Model): String {
    val managers = deviceManagerStore.findAll()

    model.addAttribute("canCreateDeviceManager", currentUser().canCreateDeviceManager())
    model.addAttribute(
        "canRegenerateAllDeviceManagerTokens",
        currentUser().canRegenerateAllDeviceManagerTokens(),
    )
    model.addAttribute("managers", managers)

    return "/admin/listDeviceManagers"
  }

  @GetMapping("/deviceManagers/{deviceManagerId}")
  fun getDeviceManager(
      @PathVariable("deviceManagerId") deviceManagerId: DeviceManagerId,
      model: Model,
  ): String {
    val manager = deviceManagerStore.fetchOneById(deviceManagerId)
    val facility = manager.facilityId?.let { facilityStore.fetchOneById(it) }

    model.addAttribute(
        "canUpdateDeviceManager",
        currentUser().canUpdateDeviceManager(deviceManagerId),
    )
    model.addAttribute("facility", facility)
    model.addAttribute("manager", manager)

    return "/admin/deviceManager"
  }

  @PostMapping("/createDeviceTemplate")
  fun createDeviceTemplate(
      redirectAttributes: RedirectAttributes,
      @ModelAttribute templatesRow: DeviceTemplatesRow,
      @RequestParam settings: String?,
  ): String {
    requirePermissions { updateDeviceTemplates() }

    templatesRow.settings = settings?.ifEmpty { null }?.let { JSONB.jsonb(it) }

    try {
      deviceTemplatesDao.insert(templatesRow)
      redirectAttributes.successMessage = "Device template created."
    } catch (e: Exception) {
      log.error("Failed to create device template", e)
      redirectAttributes.failureMessage = "Creation failed: ${e.message}"
    }

    return redirectToDeviceTemplates()
  }

  @PostMapping("/deviceManagers")
  fun createDeviceManager(
      redirectAttributes: RedirectAttributes,
      @RequestParam sensorKitId: String,
  ): String {
    val row =
        DeviceManagersRow(
            balenaId = BalenaDeviceId(Random.nextLong()),
            balenaUuid = UUID.randomUUID().toString(),
            balenaModifiedTime = clock.instant(),
            createdTime = clock.instant(),
            deviceName = "Test Device $sensorKitId",
            isOnline = true,
            refreshedTime = clock.instant(),
            sensorKitId = sensorKitId,
        )

    return try {
      deviceManagerStore.insert(row)
      redirectAttributes.successMessage = "Device manager created."

      redirectToDeviceManager(row.id!!)
    } catch (e: Exception) {
      log.error("Failed to create device manager", e)
      redirectAttributes.failureMessage = "Creation failed: ${e.message}"

      redirectToDeviceManagers()
    }
  }

  @PostMapping("/deviceManagers/{deviceManagerId}")
  fun updateDeviceManager(
      redirectAttributes: RedirectAttributes,
      @PathVariable("deviceManagerId") deviceManagerId: DeviceManagerId,
      @ModelAttribute row: DeviceManagersRow,
  ): String {
    row.id = deviceManagerId

    if (row.facilityId != null && row.userId == null) {
      row.userId = currentUser().userId
    }

    try {
      val original = deviceManagerStore.fetchOneById(deviceManagerId)

      val originalFacilityId = original.facilityId
      val newFacilityId = row.facilityId

      when {
        originalFacilityId != null && newFacilityId == null -> {
          redirectAttributes.failureMessage = "Cannot disconnect a device manager."
          return redirectToDeviceManager(deviceManagerId)
        }
        originalFacilityId == null && newFacilityId != null -> {
          facilityStore.updateConnectionState(
              newFacilityId,
              FacilityConnectionState.NotConnected,
              FacilityConnectionState.Connected,
          )
        }
        originalFacilityId != newFacilityId -> {
          redirectAttributes.failureMessage =
              "Cannot reassign a device manager to a different facility."
          return redirectToDeviceManager(deviceManagerId)
        }
      }

      deviceManagerStore.update(row)

      redirectAttributes.successMessage = "Device manager updated."
    } catch (e: Exception) {
      log.error("Failed to update device manager", e)
      redirectAttributes.failureMessage = "Update failed: ${e.message}"
    }

    return redirectToDeviceManager(deviceManagerId)
  }

  @PostMapping("/deviceManagers/{deviceManagerId}/generateToken")
  fun generateToken(
      redirectAttributes: RedirectAttributes,
      @PathVariable("deviceManagerId") deviceManagerId: DeviceManagerId,
  ): String {
    try {
      val row = deviceManagerStore.fetchOneById(deviceManagerId)
      val balenaId = row.balenaId ?: throw IllegalStateException("Device manager has no balena ID")
      val facilityId =
          row.facilityId
              ?: throw IllegalStateException("Device manager is not connected to a facility")
      val userId =
          row.userId ?: throw IllegalStateException("Device manager does not have a user ID")

      deviceManagerService.generateOfflineToken(userId, balenaId, facilityId, overwrite = true)

      redirectAttributes.successMessage = "Token generated."
    } catch (e: Exception) {
      log.error("Failed to generate refresh token", e)
      redirectAttributes.failureMessage = "Generation failed: ${e.message}"
    }

    return redirectToDeviceManager(deviceManagerId)
  }

  @PostMapping("/deviceManagers/regenerateAllTokens")
  fun regenerateAllTokens(
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      deviceManagerService.regenerateAllOfflineTokens()

      redirectAttributes.successMessage = "Tokens regenerated."
    } catch (e: Exception) {
      log.error("Failed to regenerate refresh tokens", e)
      redirectAttributes.failureMessage = "Generation failed: ${e.message}"
    }

    return redirectToDeviceManagers()
  }

  @PostMapping("/deviceTemplates")
  fun updateTemplate(
      redirectAttributes: RedirectAttributes,
      @ModelAttribute templatesRow: DeviceTemplatesRow,
      @RequestParam settings: String?,
      @RequestParam delete: String?,
  ): String {
    requirePermissions { updateDeviceTemplates() }

    templatesRow.settings = settings?.ifEmpty { null }?.let { JSONB.jsonb(it) }

    try {
      if (delete != null) {
        deviceTemplatesDao.deleteById(templatesRow.id)
        redirectAttributes.successMessage = "Device template deleted."
      } else {
        deviceTemplatesDao.update(templatesRow)
        redirectAttributes.successMessage = "Device template updated."
      }
    } catch (e: Exception) {
      log.error("Failed to update device template", e)
      redirectAttributes.failureMessage = "Update failed: ${e.message}"
    }

    return redirectToDeviceTemplates()
  }

  @PostMapping("/createDevices")
  fun createDevices(
      redirectAttributes: RedirectAttributes,
      @RequestParam address: String?,
      @RequestParam count: Int,
      @RequestParam facilityId: FacilityId,
      @RequestParam make: String,
      @RequestParam model: String,
      @RequestParam name: String?,
      @RequestParam parentId: DeviceId?,
      @RequestParam protocol: String?,
      @RequestParam type: String,
  ): String {
    try {
      repeat(count) {
        val calculatedName = name ?: Random.nextBytes(4).toHexString(HexFormat.UpperCase)
        val calculatedAddress = address ?: calculatedName

        val devicesRow =
            DevicesRow(
                address = calculatedAddress,
                deviceType = type,
                facilityId = facilityId,
                make = make,
                model = model,
                name = calculatedName,
                parentId = parentId,
                protocol = protocol,
            )

        deviceService.create(devicesRow)
      }

      redirectAttributes.successMessage =
          if (count == 1) "Device created." else "$count devices created."
    } catch (e: Exception) {
      log.error("Failed to create devices", e)
      redirectAttributes.failureMessage = "Creation failed: ${e.message}"
    }

    return redirectToFacility(facilityId)
  }

  private fun redirectToDeviceManager(deviceManagerId: DeviceManagerId) =
      "redirect:/admin/deviceManagers/$deviceManagerId"

  private fun redirectToDeviceTemplates() = "redirect:/admin/deviceTemplates"

  private fun redirectToDeviceManagers() = "redirect:/admin/deviceManagers"

  private fun redirectToFacility(facilityId: FacilityId) = "redirect:/admin/facility/$facilityId"
}
