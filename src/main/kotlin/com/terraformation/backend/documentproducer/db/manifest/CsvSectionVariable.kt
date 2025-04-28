package com.terraformation.backend.documentproducer.db.manifest

import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.embeddables.pojos.VariableManifestEntryId
import com.terraformation.backend.db.docprod.tables.pojos.VariablesRow

// In the header order in the CSV
data class CsvSectionVariable(
    /** Column 1/A - Name */
    val name: String,
    /** Column 2/B - Identifier that stays stable across settings changes. */
    val stableId: StableId,
    /** Column 3/C - Description (optional) */
    val description: String?,
    /** Column 4/D - Recommended variables (one name per line, optionally prefixed with a hyphen) */
    val recommendedVariables: List<String>,
    /** Column 5/E - Parent (for non-top-level sections and table columns) */
    val parent: String?,
    /**
     * Column 6/F - Non-numbered section? (for sections that aren't assigned a number in the section
     * hierarchy; typically a leaf-level section)
     */
    val isNonNumberedSection: Boolean,
    /** Column 7/G - Default text for the section with optionally embedded variables */
    val defaultSectionText: String?,

    /** Position in the sheet, recorded against the variable manifest entry */
    val position: Int,
    /** The path to the variable within the hierarchy */
    var variablePath: String,
    /** The path to the parent variable within the hierarchy */
    var parentPath: String?,
    /** The base variable ID */
    var variableId: VariableId? = null,
    /** If this variable replaces one from an earlier manifest, that variable's ID. */
    var replacesVariableId: VariableId? = null,
    /** Primary key for the variable manifest entry since there is no ID for this entity */
    var variableManifestEntryKey: VariableManifestEntryId? = null,
) {
  fun mapToVariablesRow() =
      VariablesRow(
          description = description,
          id = variableId,
          isList = true,
          isRequired = false,
          name = name,
          replacesVariableId = replacesVariableId,
          stableId = stableId,
          variableTypeId = VariableType.Section,
      )
}
