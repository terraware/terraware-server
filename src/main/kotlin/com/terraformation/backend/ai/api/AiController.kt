package com.terraformation.backend.ai.api

import com.terraformation.backend.ai.EmbeddingService
import com.terraformation.backend.ai.InjectMetadataAdvisor
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.log.perClassLogger
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor
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

@RequestMapping("/api/v1/ai")
@RestController
class AiController(
    private val chatClientBuilder: ChatClient.Builder,
    private val embeddingService: EmbeddingService,
    private val injectMetadataAdvisor: InjectMetadataAdvisor,
    private val vectorStore: VectorStore,
) {
  private val log = perClassLogger()

  val userTextAdvise: String = """

			Context information is below, surrounded by ---------------------

			---------------------
			{question_answer_context}
			---------------------

			Given the context and provided history information,
			reply to the user comment. If the answer is not in the context, inform
			the user that you can't answer the question. You may use general knowledge
      of geography to interpret the context.
			
			""".trimIndent()

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
  fun queryAi(@RequestParam query: String): String {
    val response =
        chatClientBuilder
            .build()
            .prompt()
            .advisors(QuestionAnswerAdvisor(vectorStore, SearchRequest.builder().build(), userTextAdvise), injectMetadataAdvisor)
            .user(query)
            .call()
            .content()

    return response ?: "No answer"
  }

  @PostMapping("/embedProjects")
  fun embedAllProjectData(): String {
    embeddingService.embedAllProjectData()

    return "ok"
  }

  @PostMapping("/embedProject/{projectId}")
  fun embedProjectData(@PathVariable projectId: ProjectId): String {
    embeddingService.embedProjectData(projectId)

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
