package com.terraformation.backend.ask

import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.model.ModuleDeliverableModel
import com.terraformation.backend.accelerator.model.SubmissionDocumentModel
import com.terraformation.backend.accelerator.variables.AcceleratorProjectVariableValuesService
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSION_DOCUMENTS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.VECTOR_STORE
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_VALUES
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.model.ExistingSelectValue
import com.terraformation.backend.documentproducer.model.ExistingValue
import com.terraformation.backend.documentproducer.model.SelectVariable
import com.terraformation.backend.documentproducer.model.TableVariable
import com.terraformation.backend.file.GoogleDriveWriter
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import org.apache.tika.exception.UnsupportedFormatException
import org.jooq.DSLContext
import org.jooq.conf.ParamType
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.ai.document.Document
import org.springframework.ai.reader.tika.TikaDocumentReader
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder
import org.springframework.core.io.InputStreamResource

@ConditionalOnSpringAi
@Named
class EmbeddingService(
    private val acceleratorProjectVariableValuesService: AcceleratorProjectVariableValuesService,
    private val deliverableStore: DeliverableStore,
    private val dslContext: DSLContext,
    private val googleDriveWriter: GoogleDriveWriter,
    private val organizationStore: OrganizationStore,
    private val projectAcceleratorDetailsStore: ProjectAcceleratorDetailsStore,
    private val projectStore: ProjectStore,
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
          RegexOption.COMMENTS)

  private val log = perClassLogger()

  /** Returns the list of projects that have had their embeddings generated. */
  fun listEmbeddedProjects(): List<ExistingProjectModel> {
    requirePermissions { readAllAcceleratorDetails() }

    val projectIdField = DSL.jsonbGetAttributeAsText(VECTOR_STORE.METADATA, "projectId")

    return dslContext
        .select(PROJECTS.asterisk())
        .from(PROJECTS)
        .where(
            PROJECTS.ID.`in`(
                DSL.select(
                        DSL.cast(projectIdField, SQLDataType.BIGINT).convertFrom { ProjectId(it) })
                    .from(VECTOR_STORE)
                    .where(projectIdField.isNotNull)))
        .orderBy(PROJECTS.ID)
        .also { log.info("Query: ${it.getSQL(ParamType.INLINED)}") }
        .fetch { ExistingProjectModel.of(it) }
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
            projectId, acceleratorProjectVariableValuesService.fetchValues(projectId))

    val baseMetadata =
        mapOf(
            "organizationId" to organization.id,
            "organizationName" to organization.name,
            "projectId" to projectId,
            "projectName" to project.name,
        )

    val documents =
        valuesByVariableId
            .filterValues { values -> values.any { it.rowValueId == null } }
            .map { (variableId, values) ->
              val variable = variableStore.fetchOneVariable(variableId)
              val metadata =
                  baseMetadata +
                      listOfNotNull(
                              "variableId" to variableId,
                              "question" to (variable.deliverableQuestion ?: variable.name),
                              variable.description?.let { "description" to it },
                          )
                          .toMap()
              val text =
                  if (variable is TableVariable) {
                    renderTableAsMarkdown(variable, valuesByVariableId)
                  } else {
                    values.joinToString("\n") { variableValue ->
                      if (variable is SelectVariable && variableValue is ExistingSelectValue) {
                        variableValue.value
                            .mapNotNull { optionId ->
                              variable.options.firstOrNull { it.id == optionId }?.name
                            }
                            .joinToString("\n")
                      } else {
                        variableValue.value.toString()
                      }
                    }
                  }

              Document(text, metadata)
            } +
            Document(
                "Here is some accelerator-related information about the project:\n$projectDetails",
                baseMetadata)

    vectorStore.delete(FilterExpressionBuilder().eq("projectId", projectId).build())

    log.info("Embedding ${documents.size} documents for project $projectId ${project.name}")

    vectorStore.add(documents)

    embedGoogleDriveFiles(projectId, baseMetadata)
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

  private fun embedGoogleDriveFiles(projectId: ProjectId, baseMetadata: Map<String, Any?>) {
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
      baseMetadata: Map<String, Any?>,
  ) {
    log.info(
        "Embedding project $projectId deliverable ${deliverable.id} file ${submissionDocument.name}")

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
                  })
              .read()
              .filter { it.isText }
              .map { document -> document.mutate().metadata(metadata).build() }
              .flatMap { document -> TokenTextSplitter().split(document) }

      vectorStore.add(chunks)
    } catch (e: Exception) {
      if (e.cause is UnsupportedFormatException) {
        log.info(
            "Unsupported file format for project $projectId deliverable ${deliverable.id} " +
                "submission document ${submissionDocument.id} ${submissionDocument.name}")
      } else {
        throw e
      }
    }
  }

  fun embedAllProjectData() {
    val projectsWithoutEmbeddings =
        dslContext
            .selectDistinct(VARIABLE_VALUES.PROJECT_ID)
            .from(VARIABLE_VALUES)
            .whereNotExists(
                DSL.selectOne()
                    .from(VECTOR_STORE)
                    .where(
                        DSL.jsonbGetAttributeAsText(VECTOR_STORE.METADATA, "projectId")
                            .eq(VARIABLE_VALUES.PROJECT_ID.cast(SQLDataType.VARCHAR))))
            .orderBy(VARIABLE_VALUES.PROJECT_ID)
            .fetch(VARIABLE_VALUES.PROJECT_ID.asNonNullable())

    projectsWithoutEmbeddings.forEach { embedProjectData(it) }
  }
}
