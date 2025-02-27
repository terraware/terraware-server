package com.terraformation.backend.ai.api

import com.terraformation.backend.ai.ChatService
import com.terraformation.backend.ai.EmbeddingService
import com.terraformation.backend.api.InternalEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.default_schema.ProjectId
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@InternalEndpoint
@RequestMapping("/api/v1/ai")
@RestController
class AiController(
    private val chatService: ChatService,
    private val embeddingService: EmbeddingService,
    private val systemUser: SystemUser,
    private val vectorStore: VectorStore,
) {
  @PostMapping("/embedding")
  fun createEmbedding(@RequestBody payload: CreateEmbeddingRequestPayload): String {
    val document = Document(payload.text, payload.metadata)
    vectorStore.add(listOf(document))
    return "ok"
  }

  @GetMapping("/similar")
  fun getSimilarText(@RequestParam search: String): GetSimilarTextResponsePayload {
    val request = SearchRequest.builder().query(search).build()
    val documents = vectorStore.similaritySearch(request)

    return GetSimilarTextResponsePayload(
        documents?.mapNotNull { SimilarTextPayload(it.text, it.score) } ?: emptyList(),
    )
  }

  @GetMapping
  fun queryAi(@RequestParam projectId: ProjectId?, @RequestParam query: String): String {
    return chatService.queryAi(projectId, query) ?: "No answer"
  }

  @PostMapping("/embedProjects")
  fun embedAllProjectData(): String {
    embeddingService.embedAllProjectData()

    return "ok"
  }

  @PostMapping("/embedProject/{projectId}")
  fun embedProjectData(@PathVariable projectId: ProjectId): String {
    systemUser.run { embeddingService.embedProjectData(projectId) }
    return "ok"
  }

  @GetMapping("/listProjects")
  fun listProjects(): List<ProjectId> {
    return embeddingService.listProjects()
  }
}

data class CreateEmbeddingRequestPayload(
    val text: String,
    val metadata: Map<String, Any>,
)

data class SimilarTextPayload(
    val text: String?,
    val similarity: Double?,
)

data class GetSimilarTextResponsePayload(
    val documents: List<SimilarTextPayload>,
) : SuccessResponsePayload
