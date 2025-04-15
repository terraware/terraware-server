package com.terraformation.backend.ask

import jakarta.inject.Named
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor
import org.springframework.ai.document.Document

@Named
class InjectMetadataAdvisor(private val order: Int = 1) : CallAroundAdvisor {
  private val ignoredKeys =
      setOf(
          "distance",
          "organizationName",
          "projectName",
      )

  override fun getOrder(): Int = order

  override fun getName(): String = javaClass.simpleName

  override fun aroundCall(
      advisedRequest: AdvisedRequest,
      chain: CallAroundAdvisorChain
  ): AdvisedResponse {
    val documents =
        advisedRequest.adviseContext[QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS] as? List<*>
            ?: emptyList<Document>()
    val userParams = advisedRequest.userParams.toMutableMap()

    val documentContext =
        documents.filterIsInstance<Document>().joinToString("\n\n---\n") { document ->
          val metadataText =
              document.metadata
                  .filterKeys { !it.endsWith("Id") && it !in ignoredKeys }
                  .entries
                  .joinToString("\n") { (key, value) -> "$key: $value" }

          metadataText + "\n\n" + document.text
        }

    userParams["question_answer_context"] = documentContext

    val request = AdvisedRequest.from(advisedRequest).userParams(userParams).build()
    return chain.nextAroundCall(request)
  }
}
