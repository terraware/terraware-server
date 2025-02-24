package com.terraformation.backend.admin

import com.terraformation.backend.ai.ChatService
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminAiController(
    private val chatService: ChatService,
    private val projectStore: ProjectStore,
) {
  @GetMapping("/ai")
  fun aiHome(model: Model): String {
    model.addAttribute("answer", null)
    model.addAttribute("projectId", null)

    return "/admin/ai"
  }

  @PostMapping("/ai")
  fun postAiQuery(@RequestParam projectId: ProjectId, query: String, model: Model): String {
    try {
      val project = projectStore.fetchOneById(projectId)

      val answer = chatService.queryAi(projectId, query) ?: "No answer received from AI"

      val markdownAnswer = Parser.builder().build().parse(answer)
      val htmlAnswer = HtmlRenderer.builder().build().render(markdownAnswer)

      model.addAttribute("answer", htmlAnswer)
      model.addAttribute("project", project)
      model.addAttribute("query", query)
    } catch (e: Exception) {
      model.addAttribute("failureMessage", e.message)
    }

    return "/admin/ai"
  }

  // private fun redirectToAiHome() = "redirect:/admin/ai"
}
