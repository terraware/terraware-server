package com.terraformation.backend.ai

import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.variables.AcceleratorProjectVariableValuesService
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.VECTOR_STORE
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_VALUES
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder

@Named
class EmbeddingService(
    private val acceleratorProjectVariableValuesService: AcceleratorProjectVariableValuesService,
    private val dslContext: DSLContext,
    private val organizationStore: OrganizationStore,
    private val projectAcceleratorDetailsStore: ProjectAcceleratorDetailsStore,
    private val projectStore: ProjectStore,
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
    private val vectorStore: VectorStore,
) {
  private val log = perClassLogger()

  fun embedProjectData(projectId: ProjectId) {
    val project = projectStore.fetchOneById(projectId)
    val organization = organizationStore.fetchOneById(project.organizationId)
    val values = variableValueStore.listValues(projectId)
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
        values.map { variableValue ->
          val variable = variableStore.fetchOneVariable(variableValue.variableId)
          val metadata =
              baseMetadata +
                  listOfNotNull(
                          "variableId" to variableValue.variableId,
                          "variableName" to variable.name,
                          variable.deliverableQuestion?.let { "question" to it },
                      )
                      .toMap()

          Document(variableValue.value.toString(), metadata)
        } +
            Document(
                "Here is some accelerator-related information about the project:\n$projectDetails",
                baseMetadata)

    vectorStore.delete(FilterExpressionBuilder().eq("projectId", projectId).build())

    log.info("Embedding ${documents.size} documents for project $projectId")

    vectorStore.add(documents)
  }

  fun embedAllProjectData() {
    val unembeddedProjects =
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

    unembeddedProjects.forEach { embedProjectData(it) }
  }

  fun listProjects(): List<ProjectId> {
    return dslContext
        .selectDistinct(DSL.jsonbGetAttributeAsText(VECTOR_STORE.METADATA, "projectId"))
        .from(VECTOR_STORE)
        .fetch { ProjectId(it.value1()) }
  }
}
