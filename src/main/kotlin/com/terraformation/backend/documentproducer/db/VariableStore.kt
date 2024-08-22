package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.tables.daos.VariableNumbersDao
import com.terraformation.backend.db.docprod.tables.daos.VariableSectionDefaultValuesDao
import com.terraformation.backend.db.docprod.tables.daos.VariableSectionRecommendationsDao
import com.terraformation.backend.db.docprod.tables.daos.VariableSectionsDao
import com.terraformation.backend.db.docprod.tables.daos.VariableSelectOptionsDao
import com.terraformation.backend.db.docprod.tables.daos.VariableSelectsDao
import com.terraformation.backend.db.docprod.tables.daos.VariableTableColumnsDao
import com.terraformation.backend.db.docprod.tables.daos.VariableTablesDao
import com.terraformation.backend.db.docprod.tables.daos.VariableTextsDao
import com.terraformation.backend.db.docprod.tables.daos.VariablesDao
import com.terraformation.backend.db.docprod.tables.pojos.VariableNumbersRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSectionDefaultValuesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSectionRecommendationsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSectionsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSelectOptionsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSelectsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableTableColumnsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableTablesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableTextsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariablesRow
import com.terraformation.backend.db.docprod.tables.records.VariableManifestEntriesRecord
import com.terraformation.backend.db.docprod.tables.references.DOCUMENTS
import com.terraformation.backend.db.docprod.tables.references.VARIABLES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_MANIFESTS
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_MANIFEST_ENTRIES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_SECTIONS
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_SECTION_RECOMMENDATIONS
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_SECTION_VALUES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_SELECT_OPTIONS
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_TABLE_COLUMNS
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_VALUES
import com.terraformation.backend.documentproducer.model.BaseVariableProperties
import com.terraformation.backend.documentproducer.model.DateVariable
import com.terraformation.backend.documentproducer.model.ImageVariable
import com.terraformation.backend.documentproducer.model.LinkVariable
import com.terraformation.backend.documentproducer.model.NumberVariable
import com.terraformation.backend.documentproducer.model.SectionVariable
import com.terraformation.backend.documentproducer.model.SelectOption
import com.terraformation.backend.documentproducer.model.SelectVariable
import com.terraformation.backend.documentproducer.model.TableColumn
import com.terraformation.backend.documentproducer.model.TableVariable
import com.terraformation.backend.documentproducer.model.TextVariable
import com.terraformation.backend.documentproducer.model.Variable
import jakarta.inject.Named
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class VariableStore(
    private val dslContext: DSLContext,
    private val variableNumbersDao: VariableNumbersDao,
    private val variablesDao: VariablesDao,
    private val variableSectionDefaultValuesDao: VariableSectionDefaultValuesDao,
    private val variableSectionRecommendationsDao: VariableSectionRecommendationsDao,
    private val variableSectionsDao: VariableSectionsDao,
    private val variableSelectsDao: VariableSelectsDao,
    private val variableSelectOptionsDao: VariableSelectOptionsDao,
    private val variableTablesDao: VariableTablesDao,
    private val variableTableColumnsDao: VariableTableColumnsDao,
    private val variableTextsDao: VariableTextsDao,
) {
  /**
   * Cache of information about variables. This just holds variable definitions, not values! There
   * is currently no mechanism to limit the size of this cache; the assumption for now is that all
   * the variables in the system will easily fit in memory.
   *
   * The manifest ID in the key must be non-null for section variables and null for all other
   * variable types.
   */
  private val variables = ConcurrentHashMap<Pair<VariableManifestId?, VariableId>, Variable>()

  /**
   * Cache of information about which variables were replaced by which other variables. If a
   * variable isn't a replacement for any other variables, its value in this map will be an empty
   * list.
   */
  private val replacements = ConcurrentHashMap<VariableId, List<VariableId>>()

  /**
   * Returns information about a variable. Eagerly fetches other variables that are related to the
   * requested variable, e.g., table columns.
   *
   * @throws VariableNotFoundException The variable didn't exist.
   * @throws VariableIncompleteException The variable didn't have all the information required for
   *   its type.
   * @throws CircularReferenceException There is a cycle in the graph of the variable and its
   *   children.
   */
  fun fetchVariable(variableId: VariableId, manifestId: VariableManifestId? = null): Variable {
    return variables[manifestId to variableId] ?: FetchContext(manifestId).fetchVariable(variableId)
  }

  fun fetchByStableId(stableId: String): Variable? =
      with(VARIABLES) {
        dslContext
            .select(ID)
            .from(VARIABLES)
            .where(STABLE_ID.eq(stableId))
            .orderBy(ID.desc())
            .limit(1)
            .fetchOne(VARIABLES.ID)
            ?.let { fetchVariable(it) }
      }

  fun fetchDeliverableVariables(deliverableId: DeliverableId): List<Variable> =
      with(VARIABLES) {
        dslContext
            .select(DSL.max(ID))
            .from(VARIABLES)
            .where(DELIVERABLE_ID.eq(deliverableId))
            .groupBy(STABLE_ID)
            .fetch()
            .map { fetchVariable(it[DSL.max(ID)]!!) }
            .sortedBy { it.deliverablePosition }
      }

  fun fetchManifestVariables(documentId: DocumentId): List<Variable> {
    return with(VARIABLE_MANIFEST_ENTRIES) {
      dslContext
          .select(VARIABLE_ID, VARIABLE_MANIFEST_ID)
          .from(VARIABLE_MANIFEST_ENTRIES)
          .join(DOCUMENTS)
          .on(VARIABLE_MANIFEST_ENTRIES.VARIABLE_MANIFEST_ID.eq(DOCUMENTS.VARIABLE_MANIFEST_ID))
          .where(DOCUMENTS.ID.eq(documentId))
          .andNotExists(
              DSL.selectOne()
                  .from(VARIABLE_TABLE_COLUMNS)
                  .where(VARIABLE_ID.eq(VARIABLE_TABLE_COLUMNS.VARIABLE_ID)))
          .andNotExists(
              DSL.selectOne()
                  .from(VARIABLE_SECTIONS)
                  .where(VARIABLE_ID.eq(VARIABLE_SECTIONS.VARIABLE_ID))
                  .and(VARIABLE_SECTIONS.PARENT_VARIABLE_ID.isNotNull))
          .orderBy(POSITION)
          .fetch()
          .map { fetchVariable(it[VARIABLE_ID]!!, it[VARIABLE_MANIFEST_ID]!!) }
    }
  }

  /**
   * Returns a list of the top-level variables in a manifest in position order. Child sections and
   * table columns are not included in the top-level list, but rather in the containing variables'
   * lists of children.
   */
  fun fetchManifestVariables(manifestId: VariableManifestId): List<Variable> {
    return with(VARIABLE_MANIFEST_ENTRIES) {
      dslContext
          .select(VARIABLE_ID)
          .from(VARIABLE_MANIFEST_ENTRIES)
          .where(VARIABLE_MANIFEST_ID.eq(manifestId))
          .andNotExists(
              DSL.selectOne()
                  .from(VARIABLE_TABLE_COLUMNS)
                  .where(VARIABLE_ID.eq(VARIABLE_TABLE_COLUMNS.VARIABLE_ID)))
          .andNotExists(
              DSL.selectOne()
                  .from(VARIABLE_SECTIONS)
                  .where(VARIABLE_ID.eq(VARIABLE_SECTIONS.VARIABLE_ID))
                  .and(VARIABLE_SECTIONS.PARENT_VARIABLE_ID.isNotNull))
          .orderBy(POSITION)
          .fetch(VARIABLE_ID.asNonNullable())
          .map { fetchVariable(it, manifestId) }
    }
  }

  /**
   * Returns a list of the variables in a manifest in position order. Table columns are excluded.
   * Section children are included here. They are redundantly included in the children of sections.
   * This is left in to ensure that, when fetching, cycles may be detected.
   */
  fun fetchManifestVariablesWithSubSectionsAndTableColumns(
      manifestId: VariableManifestId
  ): List<Variable> {
    return with(VARIABLE_MANIFEST_ENTRIES) {
      dslContext
          .select(VARIABLE_ID)
          .from(VARIABLE_MANIFEST_ENTRIES)
          .where(VARIABLE_MANIFEST_ID.eq(manifestId))
          .andNotExists(
              DSL.selectOne()
                  .from(VARIABLE_TABLE_COLUMNS)
                  .where(VARIABLE_ID.eq(VARIABLE_TABLE_COLUMNS.VARIABLE_ID)))
          .orderBy(POSITION)
          .fetch(VARIABLE_ID.asNonNullable())
          .map { fetchVariable(it, manifestId) }
    }
  }

  /**
   * Fetches the list of non-section variables, using the latest version of each based on their
   * stable ID. Table columns are not included in the top-level list, but rather in the containing
   * variables' lists of children.
   */
  fun fetchAllNonSectionVariables(): List<Variable> {
    return with(VARIABLES) {
      dslContext
          .select(DSL.max(ID), STABLE_ID)
          .from(this)
          .where(VARIABLE_TYPE_ID.ne(VariableType.Section))
          .andNotExists(
              DSL.selectOne()
                  .from(VARIABLE_TABLE_COLUMNS)
                  .where(ID.eq(VARIABLE_TABLE_COLUMNS.VARIABLE_ID)))
          .groupBy(STABLE_ID)
          .fetch()
          .map { record -> fetchVariable(record[DSL.max(ID)]!!) }
    }
  }

  fun fetchUsedVariables(documentId: DocumentId): List<Variable> {
    return dslContext
        .select(
            VARIABLE_VALUES.VARIABLE_ID,
            VARIABLE_VALUES.LIST_POSITION,
            VARIABLE_SECTION_VALUES.USED_VARIABLE_ID)
        .distinctOn(VARIABLE_VALUES.VARIABLE_ID, VARIABLE_VALUES.LIST_POSITION)
        .from(VARIABLE_VALUES)
        .join(VARIABLE_SECTION_VALUES)
        .on(VARIABLE_VALUES.ID.eq(VARIABLE_SECTION_VALUES.VARIABLE_VALUE_ID))
        .join(DOCUMENTS)
        .on(VARIABLE_VALUES.PROJECT_ID.eq(DOCUMENTS.PROJECT_ID))
        .join(VARIABLE_MANIFESTS)
        .on(DOCUMENTS.VARIABLE_MANIFEST_ID.eq(VARIABLE_MANIFESTS.ID))
        .join(VARIABLE_MANIFEST_ENTRIES)
        .on(VARIABLE_MANIFESTS.ID.eq(VARIABLE_MANIFEST_ENTRIES.VARIABLE_MANIFEST_ID))
        .and(VARIABLE_SECTION_VALUES.VARIABLE_ID.eq(VARIABLE_MANIFEST_ENTRIES.VARIABLE_ID))
        .where(VARIABLE_SECTION_VALUES.USED_VARIABLE_ID.isNotNull)
        .and(DOCUMENTS.ID.eq(documentId))
        .orderBy(
            VARIABLE_VALUES.VARIABLE_ID, VARIABLE_VALUES.LIST_POSITION, VARIABLE_VALUES.ID.desc())
        .fetchSet(VARIABLE_SECTION_VALUES.USED_VARIABLE_ID.asNonNullable())
        .map { fetchVariable(it) }
  }

  fun importVariable(variable: VariablesRow): VariableId {
    variablesDao.insert(variable)
    return variable.id!!
  }

  fun importTableVariable(tableRow: VariableTablesRow) {
    variableTablesDao.insert(tableRow)
  }

  fun importTableColumnVariable(columnRow: VariableTableColumnsRow) {
    variableTableColumnsDao.insert(columnRow)
  }

  fun importSectionDefaultValues(defaultValueRows: List<VariableSectionDefaultValuesRow>) {
    variableSectionDefaultValuesDao.insert(defaultValueRows)
  }

  fun importSectionVariable(sectionRow: VariableSectionsRow) {
    variableSectionsDao.insert(sectionRow)
  }

  fun importSelectVariable(
      selectRow: VariableSelectsRow,
      optionsRows: List<VariableSelectOptionsRow>
  ) {
    variableSelectsDao.insert(selectRow)
    variableSelectOptionsDao.insert(optionsRows)
  }

  fun importNumberVariable(numbersRow: VariableNumbersRow) {
    variableNumbersDao.insert(numbersRow)
  }

  fun insertSectionRecommendation(row: VariableSectionRecommendationsRow) {
    variableSectionRecommendationsDao.insert(row)
  }

  fun importTextVariable(textsRow: VariableTextsRow) {
    variableTextsDao.insert(textsRow)
  }

  /**
   * Returns the chain of variable IDs that were replaced by a given variable. A variable can be
   * replaced repeatedly if the manifest is updated multiple times.
   */
  fun fetchReplacedVariables(variableId: VariableId): List<VariableId> {
    return replacements.getOrPut(variableId) {
      val replacesVariableId =
          with(VARIABLES) {
            dslContext
                .select(REPLACES_VARIABLE_ID)
                .from(VARIABLES)
                .where(ID.eq(variableId))
                .fetchOne(REPLACES_VARIABLE_ID)
          }

      if (replacesVariableId != null) {
        fetchReplacedVariables(replacesVariableId) + replacesVariableId
      } else {
        emptyList()
      }
    }
  }

  /** Clears the variable cache. Used in tests. */
  fun clearCache() {
    replacements.clear()
    variables.clear()
  }

  /**
   * Logic for recursively fetching a variable and the variables it's related to. Variable fetching
   * is stateful because we want to detect cycles.
   */
  private inner class FetchContext(private val manifestId: VariableManifestId?) {
    /** Stack of variables that are being fetched in this context. Used to detect cycles. */
    val fetchesInProgress = ArrayDeque<VariableId>()

    fun fetchVariable(variableId: VariableId): Variable {
      if (variableId in fetchesInProgress) {
        fetchesInProgress.addLast(variableId)
        throw CircularReferenceException(fetchesInProgress)
      }

      return variables.getOrPut(manifestId to variableId) {
        fetchesInProgress.addLast(variableId)

        val variablesRow =
            variablesDao.fetchOneById(variableId) ?: throw VariableNotFoundException(variableId)
        val manifestRecord = fetchManifestRecord(variableId)
        val recommendedBy = fetchRecommendedBy(variableId)

        val base =
            BaseVariableProperties(
                deliverableId = variablesRow.deliverableId,
                deliverablePosition = variablesRow.deliverablePosition,
                deliverableQuestion = variablesRow.deliverableQuestion,
                dependencyCondition = variablesRow.dependencyConditionId,
                dependencyValue = variablesRow.dependencyValue,
                dependencyVariableStableId = variablesRow.dependencyVariableStableId,
                description = variablesRow.description,
                id = variableId,
                isList = variablesRow.isList!!,
                isRequired = variablesRow.isRequired == true,
                manifestId = manifestId,
                name = variablesRow.name!!,
                // TODO: Figure out how to order non-manifest variables
                position = manifestRecord?.position ?: 0,
                recommendedBy = recommendedBy,
                replacesVariableId = variablesRow.replacesVariableId,
                stableId = variablesRow.stableId!!,
            )

        val variable =
            when (variablesRow.variableTypeId!!) {
              VariableType.Date -> DateVariable(base)
              VariableType.Number -> fetchNumber(base)
              VariableType.Text -> fetchText(base)
              VariableType.Image -> ImageVariable(base)
              VariableType.Select -> fetchSelect(base)
              VariableType.Table -> fetchTable(base)
              VariableType.Link -> LinkVariable(base)
              VariableType.Section -> fetchSection(base)
            }

        fetchesInProgress.removeLast()

        variable
      }
    }

    private fun fetchManifestRecord(variableId: VariableId): VariableManifestEntriesRecord? {
      return if (manifestId != null) {
        with(VARIABLE_MANIFEST_ENTRIES) {
          dslContext
              .selectFrom(VARIABLE_MANIFEST_ENTRIES)
              .where(VARIABLE_MANIFEST_ID.eq(manifestId))
              .and(VARIABLE_ID.eq(variableId))
              .fetchOne() ?: throw VariableNotFoundException(variableId)
        }
      } else {
        null
      }
    }

    /** Returns a list of the IDs of section variables that recommend a given variable. */
    private fun fetchRecommendedBy(variableId: VariableId): Collection<VariableId> {
      return with(VARIABLE_SECTION_RECOMMENDATIONS) {
        dslContext
            .select(SECTION_VARIABLE_ID)
            .from(VARIABLE_SECTION_RECOMMENDATIONS)
            .where(RECOMMENDED_VARIABLE_ID.eq(variableId))
            .and(VARIABLE_MANIFEST_ID.eq(manifestId))
            .fetch(SECTION_VARIABLE_ID.asNonNullable())
      }
    }

    private fun fetchNumber(base: BaseVariableProperties): NumberVariable {
      val numbersRow =
          variableNumbersDao.fetchOneByVariableId(base.id)
              ?: throw VariableIncompleteException(base.id)

      return NumberVariable(
          base,
          numbersRow.minValue,
          numbersRow.maxValue,
          numbersRow.decimalPlaces!!,
      )
    }

    private fun fetchSection(base: BaseVariableProperties): SectionVariable {
      val sectionRow =
          variableSectionsDao.fetchOneByVariableId(base.id)
              ?: throw VariableIncompleteException(base.id)

      val children =
          dslContext
              .select(VARIABLE_SECTIONS.VARIABLE_ID)
              .from(VARIABLE_SECTIONS)
              .join(VARIABLE_MANIFEST_ENTRIES)
              .on(VARIABLE_SECTIONS.VARIABLE_ID.eq(VARIABLE_MANIFEST_ENTRIES.VARIABLE_ID))
              .where(VARIABLE_SECTIONS.PARENT_VARIABLE_ID.eq(base.id))
              .and(VARIABLE_MANIFEST_ENTRIES.VARIABLE_MANIFEST_ID.eq(manifestId))
              .orderBy(VARIABLE_MANIFEST_ENTRIES.POSITION)
              .fetch(VARIABLE_SECTIONS.VARIABLE_ID.asNonNullable())
              .map { fetchVariable(it) as SectionVariable }

      val recommends =
          with(VARIABLE_SECTION_RECOMMENDATIONS) {
            dslContext
                .select(RECOMMENDED_VARIABLE_ID)
                .from(VARIABLE_SECTION_RECOMMENDATIONS)
                .where(SECTION_VARIABLE_ID.eq(base.id))
                .and(VARIABLE_MANIFEST_ID.eq(manifestId))
                .fetch(RECOMMENDED_VARIABLE_ID.asNonNullable())
          }

      return SectionVariable(
          base.copy(manifestId = manifestId), sectionRow.renderHeading!!, children, recommends)
    }

    private fun fetchSelect(base: BaseVariableProperties): SelectVariable {
      val selectRow =
          variableSelectsDao.fetchOneByVariableId(base.id)
              ?: throw VariableIncompleteException(base.id)

      val options =
          dslContext
              .selectFrom(VARIABLE_SELECT_OPTIONS)
              .where(VARIABLE_SELECT_OPTIONS.VARIABLE_ID.eq(base.id))
              .orderBy(VARIABLE_SELECT_OPTIONS.POSITION)
              .fetch { record ->
                SelectOption(
                    description = record[VARIABLE_SELECT_OPTIONS.DESCRIPTION],
                    id = record[VARIABLE_SELECT_OPTIONS.ID]!!,
                    name = record[VARIABLE_SELECT_OPTIONS.NAME]!!,
                    renderedText = record[VARIABLE_SELECT_OPTIONS.RENDERED_TEXT],
                )
              }

      return SelectVariable(base, selectRow.isMultiple!!, options)
    }

    private fun fetchTable(base: BaseVariableProperties): TableVariable {
      val tableRow =
          variableTablesDao.fetchOneByVariableId(base.id)
              ?: throw VariableIncompleteException(base.id)

      val columns =
          dslContext
              .select(VARIABLE_TABLE_COLUMNS.IS_HEADER, VARIABLE_TABLE_COLUMNS.VARIABLE_ID)
              .from(VARIABLE_TABLE_COLUMNS)
              .where(VARIABLE_TABLE_COLUMNS.TABLE_VARIABLE_ID.eq(base.id))
              .orderBy(VARIABLE_TABLE_COLUMNS.POSITION)
              .fetch { record ->
                TableColumn(
                    record[VARIABLE_TABLE_COLUMNS.IS_HEADER.asNonNullable()],
                    fetchVariable(record[VARIABLE_TABLE_COLUMNS.VARIABLE_ID.asNonNullable()]))
              }

      return TableVariable(base, tableRow.tableStyleId!!, columns)
    }

    private fun fetchText(base: BaseVariableProperties): TextVariable {
      val textRow =
          variableTextsDao.fetchOneByVariableId(base.id)
              ?: throw VariableIncompleteException(base.id)

      return TextVariable(base, textRow.variableTextTypeId!!)
    }
  }
}
