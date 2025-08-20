package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.ask.ChatRequestFailedException
import com.terraformation.backend.ask.ChatService
import com.terraformation.backend.ask.ConditionalOnSpringAi
import com.terraformation.backend.ask.EmbeddingService
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.log.perClassLogger
import java.util.UUID
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@ConditionalOnSpringAi
@Controller
@RequestMapping("/admin/ask")
@RequireGlobalRole(
    [GlobalRole.SuperAdmin, GlobalRole.AcceleratorAdmin, GlobalRole.TFExpert, GlobalRole.ReadOnly]
)
@Validated
class AdminAskController(
    private val chatService: ChatService,
    private val embeddingService: EmbeddingService,
    private val projectStore: ProjectStore,
    private val systemUser: SystemUser,
) {
  private val log = perClassLogger()

  @GetMapping fun askIndexRedirect() = "redirect:/admin/ask/"

  @GetMapping("/")
  fun askIndex(model: Model): String {
    model.addAttribute("projects", projectsSortedByName())

    return "/admin/ask/index"
  }

  @PostMapping("/select")
  fun askProjectRedirect(@RequestParam("projectId") projectId: ProjectId?): String {
    return "redirect:/admin/ask/projects/${projectId}"
  }

  @GetMapping("/projects/{projectId}")
  fun getProjectChat(
      @PathVariable projectId: ProjectId,
      model: Model,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val project = projectStore.fetchOneById(projectId)

      if (!embeddingService.hasEmbeddings(projectId)) {
        throw IllegalStateException("Project $projectId has not been prepared yet")
      }

      model.addAttribute("answer", null)
      model.addAttribute("conversationId", UUID.randomUUID().toString())
      model.addAttribute("project", project)
      model.addAttribute("question", null)
      model.addAttribute("showVariables", false)

      return "/admin/ask/chat"
    } catch (e: Exception) {
      redirectAttributes.failureMessage = e.message
      return askIndexRedirect()
    }
  }

  @PostMapping("/projects/{projectId}")
  fun postAskQuestion(
      @PathVariable projectId: ProjectId,
      query: String,
      conversationId: String,
      showVariables: String?,
      model: Model,
  ): String {
    model.addAttribute("conversationId", conversationId)
    model.addAttribute("projectId", projectId)
    model.addAttribute("query", query)
    model.addAttribute("showVariables", showVariables != null)

    try {
      val answer =
          chatService.askQuestion(projectId, query, conversationId, showVariables != null)
              ?: "No answer received from chat service"
      val markdownAnswer = Parser.builder().build().parse(answer)
      val htmlAnswer = HtmlRenderer.builder().build().render(markdownAnswer)

      model.addAttribute("answer", htmlAnswer)
    } catch (e: ChatRequestFailedException) {
      // Don't spam our error logs if the external service is having an outage.
      log.warn("Request to chat service failed for conversation $conversationId", e)
    } catch (e: Exception) {
      log.error("Failed to generate answer for conversation $conversationId", e)
    }

    return "/admin/ask/exchange"
  }

  @PostMapping("/prepare")
  fun prepareProject(
      @RequestParam projectId: ProjectId,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      systemUser.run { embeddingService.embedProjectData(projectId) }

      redirectAttributes.successMessage = "Project $projectId processed."
      return askProjectRedirect(projectId)
    } catch (e: Exception) {
      log.error("Error while preparing project $projectId", e)
      redirectAttributes.failureMessage = e.message
      return askIndexRedirect()
    }
  }

  private fun projectsSortedByName(): List<ExistingProjectModel> =
      embeddingService.listEmbeddedProjects().sortedBy { "${it.name.trim()} ${it.id}" }
}
