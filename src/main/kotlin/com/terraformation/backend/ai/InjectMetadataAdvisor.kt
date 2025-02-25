package com.terraformation.backend.ai

import jakarta.inject.Named
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain
import org.springframework.ai.document.Document

@Named
class InjectMetadataAdvisor(private val order: Int = 1) : CallAroundAdvisor {
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
        documents.filterIsInstance<Document>().joinToString("\n") { document ->
          val metadataText =
              document.metadata
                  .filterKeys { !it.endsWith("Id") }
                  .entries
                  .joinToString("\n") { (key, value) -> "$key: $value" }

          metadataText + "\n" + document.text
        }

    userParams["question_answer_context"] = documentContext

    val request = AdvisedRequest.from(advisedRequest).userParams(userParams).build()
    return chain.nextAroundCall(request)
  }
}
