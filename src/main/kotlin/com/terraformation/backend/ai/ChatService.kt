package com.terraformation.backend.ai

import com.terraformation.backend.db.default_schema.ProjectId
import jakarta.inject.Named
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder

@Named
class ChatService(
    private val chatClientBuilder: ChatClient.Builder,
    private val injectMetadataAdvisor: InjectMetadataAdvisor,
    private val vectorStore: VectorStore,
) {
  private val userTextAdvise: String =
      """
      Context information is below, surrounded by ---------------------

      ---------------------
      {question_answer_context}
      ---------------------

      Given the context and provided history information, reply to the user comment. If the answer
      is not in the context, inform the user that you can't answer the question. You may use general
      knowledge of geography and science to interpret the context. If there are multiple entries in
      the context with similar information, prefer the newer ones if you can determine their dates.

      Include a list of the submission document names and a list of the variable names of any
      documents or variables that support the answer.
			"""
          .trimIndent()

  fun queryAi(projectId: ProjectId?, query: String): String? {
    return chatClientBuilder
        .build()
        .prompt()
        .advisors(
            QuestionAnswerAdvisor(
                vectorStore,
                SearchRequest.builder()
                    .apply {
                      if (projectId != null)
                          filterExpression(
                              FilterExpressionBuilder().eq("projectId", projectId).build())
                    }
                    .build(),
                userTextAdvise),
            injectMetadataAdvisor)
        .user(query)
        .call()
        .content()
  }
}
