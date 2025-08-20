package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.db.InternalTagStore
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.InternalTagId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationsDao
import com.terraformation.backend.log.perClassLogger
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminInternalTagsController(
    private val internalTagStore: InternalTagStore,
    private val organizationsDao: OrganizationsDao,
) {
  private val log = perClassLogger()

  @GetMapping("/internalTags")
  fun listInternalTags(model: Model, redirectAttributes: RedirectAttributes): String {
    val tags = internalTagStore.findAllTags()
    val allOrganizations = organizationsDao.findAll().sortedBy { it.id }
    val organizationTags = internalTagStore.fetchAllOrganizationTagIds()

    model.addAttribute("allOrganizations", allOrganizations)
    model.addAttribute("organizationTags", organizationTags)
    model.addAttribute("tags", tags)
    model.addAttribute("tagsById", tags.associateBy { it.id!! })

    return "/admin/listInternalTags"
  }

  @GetMapping("/internalTag/{id}")
  fun getInternalTag(
      @PathVariable("id") tagId: InternalTagId,
      model: Model,
      redirectAttributes: RedirectAttributes,
  ): String {
    val tag = internalTagStore.fetchTagById(tagId)
    val organizations = internalTagStore.fetchOrganizationsByTagId(tagId)

    model.addAttribute("organizations", organizations)
    model.addAttribute("tag", tag)

    return "/admin/internalTag"
  }

  @PostMapping("/createInternalTag")
  fun createInternalTag(
      @RequestParam name: String,
      @RequestParam description: String?,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      internalTagStore.createTag(name, description?.ifEmpty { null })
      redirectAttributes.successMessage = "Tag $name created."
    } catch (e: DuplicateKeyException) {
      redirectAttributes.failureMessage = "A tag by that name already exists."
    } catch (e: Exception) {
      log.warn("Tag creation failed", e)
      redirectAttributes.failureMessage = "Tag creation failed: ${e.message}"
    }

    return redirectToInternalTags()
  }

  @PostMapping("/updateInternalTag/{id}")
  fun updateInternalTag(
      @PathVariable("id") id: InternalTagId,
      @RequestParam name: String,
      @RequestParam description: String?,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      internalTagStore.updateTag(id, name, description)
      redirectAttributes.successMessage = "Tag updated."
    } catch (e: Exception) {
      log.warn("Tag update failed", e)
      redirectAttributes.failureMessage = "Tag update failed: ${e.message}"
    }

    return redirectToInternalTag(id)
  }

  @PostMapping("/deleteInternalTag/{id}")
  fun deleteInternalTag(
      @PathVariable("id") id: InternalTagId,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      internalTagStore.deleteTag(id)
      redirectAttributes.successMessage = "Tag deleted."
    } catch (e: Exception) {
      log.warn("Tag deletion failed", e)
      redirectAttributes.failureMessage = "Tag deletion failed: ${e.message}"
    }

    return redirectToInternalTags()
  }

  @PostMapping("/updateOrganizationInternalTags")
  fun updateOrganizationInternalTags(
      @RequestParam organizationId: OrganizationId,
      @RequestParam("tagId", required = false) tagIds: Set<InternalTagId>?,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      internalTagStore.updateOrganizationTags(organizationId, tagIds ?: emptySet())
      redirectAttributes.successMessage = "Tags for organization $organizationId updated."
    } catch (e: Exception) {
      log.warn("Organization tag update failed", e)
      redirectAttributes.failureMessage = "Organization tag update failed: ${e.message}"
    }

    return redirectToInternalTags()
  }

  private fun redirectToInternalTag(tagId: InternalTagId) = "redirect:/admin/internalTag/$tagId"

  private fun redirectToInternalTags() = "redirect:/admin/internalTags"
}
