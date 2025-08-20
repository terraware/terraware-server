package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.HubSpotService
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.log.perClassLogger
import java.math.BigDecimal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminHubSpotController(
    private val config: TerrawareServerConfig,
    private val hubSpotService: HubSpotService,
) {
  private val log = perClassLogger()

  @GetMapping("/hubSpot")
  fun getHubSpotPage(model: Model): String {
    model.addAttribute("enabled", config.hubSpot.enabled)
    model.addAttribute("hasToken", hubSpotService.isAuthorized())

    return "/admin/hubSpot"
  }

  @PostMapping("/hubSpotReset")
  fun resetHubSpot(redirectAttributes: RedirectAttributes): String {
    hubSpotService.clearCredentials()

    redirectAttributes.successMessage = "HubSpot credentials reset."

    return redirectToHubSpotHome()
  }

  @PostMapping("/hubSpotAuthorize")
  fun hubSpotAuthorize(): String {
    val hubSpotUrl = hubSpotService.getAuthorizationUrl()

    return "redirect:$hubSpotUrl"
  }

  @GetMapping("/hubSpotCallback")
  fun hubSpotCallback(
      @RequestParam code: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      hubSpotService.populateRefreshToken(code)
      redirectAttributes.successMessage = "Successfully authenticated with HubSpot"
    } catch (e: Exception) {
      log.error("Failed to authenticate with HubSpot", e)
      redirectAttributes.failureMessage = "Failed to authenticate with HubSpot: ${e.message}"
    }

    return redirectToHubSpotHome()
  }

  @PostMapping("/hubSpotTestGetAcceleratorPipelineId")
  fun hubSpotTestGetAcceleratorPipelineId(redirectAttributes: RedirectAttributes): String {
    try {
      val pipelineId = hubSpotService.getAcceleratorPipelineId()
      val stageId = hubSpotService.getApplicationStageId()
      redirectAttributes.successMessage = "Got pipeline ID: $pipelineId and stage ID: $stageId"
    } catch (e: Exception) {
      log.error("Failed to get pipeline ID", e)
      redirectAttributes.failureMessage = "Failed: ${e.message}"
    }

    return redirectToHubSpotHome()
  }

  @PostMapping("/hubSpotTestCreateApplication")
  fun hubSpotTestCreateApplication(
      @RequestParam contactEmail: String?,
      @RequestParam contactName: String?,
      @RequestParam countryCode: String?,
      @RequestParam dealName: String,
      @RequestParam orgName: String,
      @RequestParam reforestableLand: BigDecimal?,
      @RequestParam website: String?,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val dealUrl =
          hubSpotService.createApplicationObjects(
              applicationReforestableLand = reforestableLand,
              companyName = orgName,
              contactEmail = contactEmail?.ifBlank { null },
              contactName = contactName,
              countryCode = countryCode?.ifBlank { null },
              dealName = dealName,
              website = website?.ifBlank { null },
          )

      redirectAttributes.successMessage = "Successfully created deal: $dealUrl"
    } catch (e: Exception) {
      log.error("Failed to create deal", e)
      redirectAttributes.failureMessage = "Failed: ${e.message}"
    }

    return redirectToHubSpotHome()
  }

  @PostMapping("/hubSpotTestCreateDeal")
  fun hubSpotTestCreateDeal(
      @RequestParam dealName: String,
      @RequestParam countryName: String,
      @RequestParam reforestableLand: BigDecimal,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val dealId = hubSpotService.createDeal(dealName, countryName, reforestableLand)
      redirectAttributes.successMessage = "Successfully created deal with ID: $dealId"
    } catch (e: Exception) {
      log.error("Failed to create deal", e)
      redirectAttributes.failureMessage = "Failed: ${e.message}"
    }

    return redirectToHubSpotHome()
  }

  @PostMapping("/hubSpotTestCreateContact")
  fun hubSpotTestCreateContact(
      @RequestParam name: String,
      @RequestParam email: String,
      @RequestParam dealId: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val contactId = hubSpotService.createContact(email, name, dealId)
      redirectAttributes.successMessage = "Successfully created contact with ID: $contactId"
    } catch (e: Exception) {
      log.error("Failed to create contact", e)
      redirectAttributes.failureMessage = "Failed: ${e.message}"
    }

    return redirectToHubSpotHome()
  }

  @PostMapping("/hubSpotTestCreateCompany")
  fun hubSpotTestCreateCompany(
      @RequestParam name: String,
      @RequestParam website: String,
      @RequestParam dealId: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val companyId = hubSpotService.createCompany(name, website, dealId)
      redirectAttributes.successMessage = "Successfully created company with ID: $companyId"
    } catch (e: Exception) {
      log.error("Failed to create company", e)
      redirectAttributes.failureMessage = "Failed: ${e.message}"
    }

    return redirectToHubSpotHome()
  }

  private fun redirectToHubSpotHome() = "redirect:/admin/hubSpot"
}
