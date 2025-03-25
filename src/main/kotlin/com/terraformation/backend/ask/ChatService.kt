package com.terraformation.backend.ask

import com.terraformation.backend.accelerator.ProjectAcceleratorDetailsService
import com.terraformation.backend.ask.db.TerrawareChatMemory
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.daos.CountriesDao
import jakarta.inject.Named
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder

@ConditionalOnSpringAi
@Named
class ChatService(
    chatClientBuilder: ChatClient.Builder,
    chatMemory: TerrawareChatMemory,
    private val countriesDao: CountriesDao,
    private val injectMetadataAdvisor: InjectMetadataAdvisor,
    private val vectorStore: VectorStore,
    private val projectStore: ProjectStore,
    private val organizationStore: OrganizationStore,
    private val projectAcceleratorDetailsService: ProjectAcceleratorDetailsService,
) {
  /**
   * Number of items to include in context. Items are always sorted in decreasing order of
   * similarity of embedding vectors, i.e., most relevant first.
   */
  private val contextItemsToInclude = 10

  /**
   * Text to append to user's question. The string `{question_answer_context}` will be replaced with
   * chunks of content that look relevant to the conversation based on embedding similarity.
   *
   * This is based on Spring AI's built-in template, but with some added instructions.
   */
  private val basePromptTemplate: String =
      """
      
      Context information is below, surrounded by ---------------------

      ---------------------
      {project_context}
      
      {question_answer_context}
      ---------------------

      Given the context and provided history information, reply to the user comment. If the answer
      is not in the context, inform the user that you can't answer the question. You may use general
      knowledge of geography and science to interpret the context. If there are multiple entries in
      the context with similar information, prefer the newer ones if you can determine their dates.
      """
          .trimIndent()

  private val includeVariablesAndDocumentsPrompt =
      """
      
      Include a list of the submission document names and a list of the variable names of any
      documents or variables that support the answer.
      """
          .trimIndent()

  /**
   * Logs requests and responses so you can see what exact prompts we're sending. To see the log
   * messages, set `logging.level.org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor` to
   * `DEBUG` in the application properties.
   */
  private val loggerAdvisor =
      SimpleLoggerAdvisor(
          { request -> request.toPrompt().contents },
          { response -> response.result.output.text },
          100)

  private val chatClient: ChatClient = chatClientBuilder.build()
  private val chatMemoryAdvisor = MessageChatMemoryAdvisor(chatMemory)

  /**
   * Asks an LLM a question and returns the answer. Automatically includes contextual information in
   * the prompt based on the project ID and the previous questions and answers in the conversation.
   *
   * @param projectId If non-null, only this project's information will be included as context.
   *   Otherwise context may be pulled from all projects.
   * @param conversationId If non-null, include previous messages from the conversation in the
   *   prompt so the LLM knows what any followup questions are referring to. The current question
   *   and answer will also be added to the conversation's history.
   * @param showVariables If true, ask the LLM to say which variables and/or uploaded documents it
   *   referenced when generating the answer.
   */
  fun askQuestion(
      projectId: ProjectId?,
      question: String,
      conversationId: String? = null,
      showVariables: Boolean = false
  ): String? {
    val template =
        listOfNotNull(
                basePromptTemplate,
                if (showVariables) includeVariablesAndDocumentsPrompt else null,
            )
            .joinToString("\n")

    val questionAnswerAdvisor =
        QuestionAnswerAdvisor(
            vectorStore,
            SearchRequest.builder()
                .apply {
                  if (projectId != null) {
                    filterExpression(FilterExpressionBuilder().eq("projectId", projectId).build())
                  }
                }
                .topK(contextItemsToInclude)
                .build(),
            template)

    return chatClient
        .prompt()
        .user(question)
        .user { promptSpec -> promptSpec.param("project_context", renderProjectContext(projectId)) }
        .advisors(
            questionAnswerAdvisor,
            injectMetadataAdvisor,
            chatMemoryAdvisor,
            loggerAdvisor,
        )
        .advisors { a ->
          if (conversationId != null) {
            a.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId)
          }
        }
        .call()
        .content()
  }

  private fun renderProjectContext(projectId: ProjectId?): String {
    if (projectId == null) {
      return "The user comment does not refer to a specific project."
    }

    val project = projectStore.fetchOneById(projectId)
    val organization = organizationStore.fetchOneById(project.organizationId)
    val projectAcceleratorDetails = projectAcceleratorDetailsService.fetchOneById(projectId)
    val countryCode = project.countryCode ?: organization.countryCode
    val countryName = countryCode?.let { countriesDao.fetchOneByCode(it)?.name }
    val projectName =
        if (projectAcceleratorDetails.dealName != null &&
            projectAcceleratorDetails.dealName.length > 3) {
          projectAcceleratorDetails.dealName.substring(3)
        } else {
          project.name
        }

    return listOfNotNull(
            "Project name: $projectName",
            "Name of organization that runs project: ${organization.name}",
            countryName?.let { "Project country: $it" },
            projectAcceleratorDetails.dealName?.let { "Accelerator deal name: ${it.substring(3)}" },
            projectAcceleratorDetails.dealStage?.let { "Deal stage: $it" },
            projectAcceleratorDetails.dealDescription?.let { "Deal description: $it" },
        )
        .joinToString("\n")
  }
}
