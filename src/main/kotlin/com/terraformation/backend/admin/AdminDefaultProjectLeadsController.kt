package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.tables.references.DEFAULT_PROJECT_LEADS
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.Region
import com.terraformation.backend.log.perClassLogger
import jakarta.servlet.http.HttpServletRequest
import org.jooq.DSLContext
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin])
@Validated
class AdminDefaultProjectLeadsController(
    private val dslContext: DSLContext,
) {
  private val log = perClassLogger()

  @GetMapping("/defaultProjectLeads")
  fun getDefaultProjectLeads(
      model: Model,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { manageDefaultProjectLeads() }

    val defaultLeadsByRegionName: Map<String, String?> =
        with(DEFAULT_PROJECT_LEADS) {
          dslContext.selectFrom(DEFAULT_PROJECT_LEADS).fetch().associate {
            it[REGION_ID]!!.name to it[PROJECT_LEAD]
          }
        }

    model.addAttribute("regions", Region.entries.sortedBy { it.jsonValue })
    model.addAttribute("defaultLeads", defaultLeadsByRegionName)

    return "/admin/defaultProjectLeads"
  }

  @PostMapping("/defaultProjectLeads")
  fun updateDefaultProjectLeads(
      request: HttpServletRequest,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { manageDefaultProjectLeads() }

    try {
      Region.entries.forEach { region ->
        val lead = request.getParameter(region.name)?.ifBlank { null }

        with(DEFAULT_PROJECT_LEADS) {
          if (lead != null) {
            dslContext
                .insertInto(DEFAULT_PROJECT_LEADS)
                .set(REGION_ID, region)
                .set(PROJECT_LEAD, lead)
                .onConflict()
                .doUpdate()
                .set(PROJECT_LEAD, lead)
                .execute()
          } else {
            dslContext.deleteFrom(DEFAULT_PROJECT_LEADS).where(REGION_ID.eq(region)).execute()
          }
        }
      }

      redirectAttributes.successMessage = "Saved default project leads."
    } catch (e: Exception) {
      log.error("Unable to save default project leads", e)
      redirectAttributes.failureMessage = "Unable to save default project leads: ${e.message}"
    }

    return redirectToDefaultProjectLeads()
  }

  private fun redirectToDefaultProjectLeads() = "redirect:/admin/defaultProjectLeads"
}
