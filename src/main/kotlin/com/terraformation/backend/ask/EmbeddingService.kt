package com.terraformation.backend.ask

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.event.DeliverableDocumentUploadedEvent
import com.terraformation.backend.accelerator.event.VariableValueUpdatedEvent
import com.terraformation.backend.accelerator.model.ModuleDeliverableModel
import com.terraformation.backend.accelerator.model.SubmissionDocumentModel
import com.terraformation.backend.accelerator.variables.AcceleratorProjectVariableValuesService
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSION_DOCUMENTS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.VECTOR_STORE
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.model.ExistingSelectValue
import com.terraformation.backend.documentproducer.model.ExistingValue
import com.terraformation.backend.documentproducer.model.SelectVariable
import com.terraformation.backend.documentproducer.model.TableVariable
import com.terraformation.backend.documentproducer.model.Variable
import com.terraformation.backend.file.GoogleDriveWriter
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import org.apache.tika.exception.UnsupportedFormatException
import org.jobrunr.scheduling.JobScheduler
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.ai.document.Document
import org.springframework.ai.reader.tika.TikaDocumentReader
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener
import org.springframework.core.io.InputStreamResource

@ConditionalOnSpringAi
@Named
class EmbeddingService(
    private val acceleratorProjectVariableValuesService: AcceleratorProjectVariableValuesService,
    private val deliverableStore: DeliverableStore,
    private val dslContext: DSLContext,
    private val googleDriveWriter: GoogleDriveWriter,
    @Lazy private val jobScheduler: JobScheduler,
    private val organizationStore: OrganizationStore,
    private val projectAcceleratorDetailsStore: ProjectAcceleratorDetailsStore,
    private val projectStore: ProjectStore,
    private val systemUser: SystemUser,
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
    private val vectorStore: VectorStore,
) {
  /**
   * Matches MIME types of documents for which embeddings should be generated. We don't want to
   * waste cycles feeding photos or files of unknown types to the embedding API. This regex selects
   * the files whose contents are useful to include as context in prompts.
   */
  private val supportedDocumentMimeTypes =
      Regex(
          """
          application/(?:
            pdf
            | vnd\.openxmlformats.*
          )
          | text/.*
          """
              .trimIndent(),
          RegexOption.COMMENTS,
      )

  private val projectIdField = DSL.jsonbGetAttributeAsText(VECTOR_STORE.METADATA, "projectId")

  private val log = perClassLogger()

  /** Returns the list of projects that have had their embeddings generated. */
  fun listEmbeddedProjects(): List<ExistingProjectModel> {
    requirePermissions { readAllAcceleratorDetails() }

    return dslContext
        .select(PROJECTS.asterisk())
        .from(PROJECTS)
        .where(
            PROJECTS.ID.`in`(
                DSL.select(
                        DSL.cast(projectIdField, SQLDataType.BIGINT).convertFrom { ProjectId(it) }
                    )
                    .from(VECTOR_STORE)
                    .where(projectIdField.isNotNull)
            )
        )
        .orderBy(PROJECTS.ID)
        .fetch { ExistingProjectModel.of(it) }
  }

  /** Returns true if a project's embeddings have been generated. */
  fun hasEmbeddings(projectId: ProjectId): Boolean {
    requirePermissions { readAllAcceleratorDetails() }

    return dslContext.fetchExists(
        DSL.selectOne().from(VECTOR_STORE).where(projectIdField.eq("$projectId"))
    )
  }

  /**
   * Generates and stores embeddings for the data of a single project. This overwrites any previous
   * embeddings for the project.
   *
   * Currently, there is no way to do an incremental update of a project's embeddings.
   */
  fun embedProjectData(projectId: ProjectId) {
    val project = projectStore.fetchOneById(projectId)
    val organization = organizationStore.fetchOneById(project.organizationId)
    val valuesByVariableId =
        variableValueStore.listValues(projectId, includeReplacedVariables = false).groupBy {
          it.variableId
        }
    val projectDetails =
        projectAcceleratorDetailsStore.fetchOneById(
            projectId,
            acceleratorProjectVariableValuesService.fetchValues(projectId),
        )

    val baseMetadata = makeMetadata(organization, project)

    val documents =
        valuesByVariableId
            .filterValues { values -> values.any { it.rowValueId == null } }
            .keys
            .map { variableId ->
              val variable = variableStore.fetchOneVariable(variableId)

              Document(
                  renderVariableValue(variable, valuesByVariableId),
                  makeMetadata(organization, project, variable),
              )
            } +
            Document(
                "Here is some accelerator-related information about the project:\n$projectDetails",
                baseMetadata,
            )

    vectorStore.delete(FilterExpressionBuilder().eq("projectId", projectId).build())

    log.info("Embedding ${documents.size} documents for project $projectId ${project.name}")

    vectorStore.add(documents)

    embedGoogleDriveFiles(projectId, baseMetadata)
  }

  @EventListener
  fun on(event: DeliverableDocumentUploadedEvent) {
    try {
      systemUser.run {
        // Only generate embeddings for projects whose other embeddings have already been generated.
        if (hasEmbeddings(event.projectId)) {
          // Do the embedding asynchronously to avoid blocking the document upload operation on an
          // interaction with an external API.
          jobScheduler.enqueue<EmbeddingService> {
            embedDeliverableDocument(event.projectId, event.deliverableId, event.documentId)
          }
        }
      }
    } catch (e: Exception) {
      log.error("Unable to enqueue embedding generation for $event", e)
    }
  }

  @EventListener
  fun on(event: VariableValueUpdatedEvent) {
    try {
      systemUser.run {
        // Only update variable value embeddings for projects whose other embeddings have already
        // been generated.
        if (hasEmbeddings(event.projectId)) {
          // Do the embedding asynchronously to avoid blocking the variable value write operation on
          // an interaction with an external API.
          jobScheduler.enqueue<EmbeddingService> {
            embedVariableValue(event.projectId, event.variableId)
          }
        }
      }
    } catch (e: Exception) {
      log.error(
          "Unable to update embeddings for project ${event.projectId} variable ${event.variableId}",
          e,
      )
    }
  }

  @Suppress("MemberVisibilityCanBePrivate") // Called by JobRunr
  fun embedDeliverableDocument(
      projectId: ProjectId,
      deliverableId: DeliverableId,
      submissionDocumentId: SubmissionDocumentId,
  ) {
    systemUser.run {
      val project = projectStore.fetchOneById(projectId)
      val organization = organizationStore.fetchOneById(project.organizationId)
      val deliverable = deliverableStore.fetchDeliverables(deliverableId).first()
      val submissionDocument =
          deliverableStore
              .fetchDeliverableSubmissions(projectId = projectId, deliverableId = deliverableId)
              .flatMap { it.documents }
              .first { it.id == submissionDocumentId }

      if (submissionDocument.documentStore == DocumentStore.Google) {
        embedGoogleDriveFile(
            projectId,
            deliverable,
            submissionDocument,
            makeMetadata(organization, project),
        )
      }
    }
  }

  @Suppress("MemberVisibilityCanBePrivate") // Called by JobRunr
  fun embedVariableValue(projectId: ProjectId, variableId: VariableId) {
    systemUser.run {
      val variable = variableStore.fetchTopLevelVariable(variableId)
      val project = projectStore.fetchOneById(projectId)
      val organization = organizationStore.fetchOneById(project.organizationId)
      val variableIds = variable.walkTree().map { it.id }.toList()
      val valuesByVariableId =
          variableValueStore
              .listValues(
                  projectId,
                  variableIds = variableIds,
                  includeDeletedValues = false,
                  includeReplacedVariables = false,
              )
              .groupBy { it.variableId }

      vectorStore.delete(
          FilterExpressionBuilder()
              .and(
                  FilterExpressionBuilder().eq("projectId", projectId),
                  FilterExpressionBuilder().eq("variableId", variable.id),
              )
              .build()
      )

      // Only store new embeddings if the variable has a value. If the previous value was deleted,
      // there won't be a value any more and we will have already removed the old embedding.
      if (valuesByVariableId[variable.id] != null) {
        log.debug("Generating new embedding for project $projectId variable ${variable.id}")

        val document =
            Document(
                renderVariableValue(variable, valuesByVariableId),
                makeMetadata(organization, project, variable),
            )

        vectorStore.add(listOf(document))
      } else {
        log.debug("Deleted previous embedding for project $projectId variable ${variable.id}")
      }
    }
  }

  private fun makeMetadata(
      organization: OrganizationModel,
      project: ExistingProjectModel,
      variable: Variable? = null,
  ): Map<String, Any> {
    return listOfNotNull(
            "organizationId" to organization.id,
            "organizationName" to organization.name,
            "projectId" to project.id,
            "projectName" to project.name,
            variable?.id?.let { "variableId" to it },
            (variable?.deliverableQuestion ?: variable?.name)?.let { "question" to it },
            variable?.description?.let { "description" to it },
        )
        .toMap()
  }

  private fun renderVariableValue(
      variable: Variable,
      valuesByVariableId: Map<VariableId, List<ExistingValue>>,
  ): String {
    return if (variable is TableVariable) {
      renderTableAsMarkdown(variable, valuesByVariableId)
    } else {
      valuesByVariableId.getValue(variable.id).joinToString("\n") { variableValue ->
        if (variable is SelectVariable && variableValue is ExistingSelectValue) {
          variableValue.value
              .mapNotNull { optionId -> variable.options.firstOrNull { it.id == optionId }?.name }
              .joinToString("\n")
        } else {
          variableValue.value.toString()
        }
      }
    }
  }

  /**
   * Renders the value of a table variable in Markdown form so that the embedding API can treat it
   * as tabular data and extract relevant information from it.
   */
  private fun renderTableAsMarkdown(
      variable: TableVariable,
      valuesByVariableId: Map<VariableId, List<ExistingValue>>,
  ): String {
    val rows = valuesByVariableId[variable.id] ?: return ""
    val headerLines: List<List<String>> =
        listOf(
            variable.columns.map { it.variable.name },
            variable.columns.map { "---" },
        )

    val dataLines: List<List<String>> =
        rows.map { row ->
          variable.columns.map { column ->
            valuesByVariableId[column.variable.id]
                ?.filter { it.rowValueId == row.id }
                ?.joinToString("; ") { it.value.toString() } ?: "-"
          }
        }

    return (headerLines + dataLines).joinToString("\n") { lineCells ->
      lineCells.joinToString(" | ", "| ", " |")
    }
  }

  private fun embedGoogleDriveFiles(projectId: ProjectId, baseMetadata: Map<String, Any>) {
    dslContext
        .selectDistinct(SUBMISSIONS.DELIVERABLE_ID)
        .from(SUBMISSIONS)
        .join(DELIVERABLES)
        .on(SUBMISSIONS.DELIVERABLE_ID.eq(DELIVERABLES.ID))
        .join(SUBMISSION_DOCUMENTS)
        .on(SUBMISSIONS.ID.eq(SUBMISSION_DOCUMENTS.SUBMISSION_ID))
        .where(SUBMISSIONS.PROJECT_ID.eq(projectId))
        .and(DELIVERABLES.IS_SENSITIVE.isFalse)
        .and(DELIVERABLES.DELIVERABLE_TYPE_ID.eq(DeliverableType.Document))
        .fetch(SUBMISSIONS.DELIVERABLE_ID.asNonNullable())
        .forEach { deliverableId ->
          deliverableStore
              .fetchDeliverableSubmissions(projectId = projectId, deliverableId = deliverableId)
              .forEach { submission ->
                val deliverable =
                    deliverableStore.fetchDeliverables(submission.deliverableId).first()

                submission.documents
                    .filter { it.documentStore == DocumentStore.Google }
                    .forEach { submissionDocument ->
                      embedGoogleDriveFile(projectId, deliverable, submissionDocument, baseMetadata)
                    }
              }
        }
  }

  private fun embedGoogleDriveFile(
      projectId: ProjectId,
      deliverable: ModuleDeliverableModel,
      submissionDocument: SubmissionDocumentModel,
      baseMetadata: Map<String, Any>,
  ) {
    log.info(
        "Embedding project $projectId deliverable ${deliverable.id} file ${submissionDocument.name}"
    )

    val metadata =
        baseMetadata +
            listOfNotNull(
                    "deliverableId" to deliverable.id,
                    "deliverableName" to deliverable.name,
                    deliverable.descriptionHtml?.let { "deliverableDescriptionHtml" to it },
                    "deliverableCategory" to deliverable.category,
                    submissionDocument.description?.let { "documentDescription" to it },
                    "submissionDocumentId" to submissionDocument.id,
                    "submissionDocumentName" to submissionDocument.name,
                )
                .toMap()

    try {
      val mimeType = googleDriveWriter.getFileContentType(submissionDocument.location)
      if (mimeType == null || !supportedDocumentMimeTypes.matches(mimeType)) {
        log.info("Skipping file with unsupported type $mimeType")
        return
      }

      val chunks =
          TikaDocumentReader(
                  InputStreamResource {
                    googleDriveWriter.downloadFile(submissionDocument.location)
                  }
              )
              .read()
              .filter { it.isText }
              .map { document -> document.mutate().metadata(metadata).build() }
              .flatMap { document -> TokenTextSplitter().split(document) }

      vectorStore.add(chunks)
    } catch (e: GoogleJsonResponseException) {
      if (e.details.errors.firstOrNull()?.reason == "notFound") {
        log.error(
            "Project $projectId deliverable ${deliverable.id} submission document " +
                "${submissionDocument.id} ${submissionDocument.name} is referenced in Terraware " +
                "but does not appear to exist in Google Drive."
        )
        // Don't throw the exception; we want the embedding process to continue with other files.
      } else {
        throw e
      }
    } catch (e: Exception) {
      if (e.cause is UnsupportedFormatException) {
        log.info(
            "Unsupported file format for project $projectId deliverable ${deliverable.id} " +
                "submission document ${submissionDocument.id} ${submissionDocument.name}"
        )
      } else {
        throw e
      }
    }
  }
}
