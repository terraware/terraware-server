package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.docprod.DependencyCondition
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.VariableSelectOptionId
import com.terraformation.backend.db.docprod.VariableTableStyle
import com.terraformation.backend.db.docprod.VariableTextType
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableUsageType
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.documentproducer.db.VariableNotFoundException
import com.terraformation.backend.documentproducer.db.VariableNotListException
import com.terraformation.backend.documentproducer.db.VariableTypeMismatchException
import com.terraformation.backend.documentproducer.db.VariableValueInvalidException
import java.lang.Exception
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeParseException
import org.apache.commons.validator.routines.EmailValidator

/**
 * Generic information about a variable as it appears in a specific manifest, minus the variable's
 * data type.
 *
 * You probably want [Variable] instead of this interface; this interface mostly exists so that the
 * implementations can delegate to [BaseVariableProperties] to cut down on needless repetition.
 */
interface BaseVariable {
  /** The position of this variable in each of its associated deliverables, if applicable. */
  val deliverablePositions: Map<DeliverableId, Int>

  /** The display question of this variable, if applicable * */
  val deliverableQuestion: String?

  /** The condition for the variable dependency, if applicable * */
  val dependencyCondition: DependencyCondition?

  /** The value for the variable dependency, if applicable * */
  val dependencyValue: String?

  /** The ID of the variable this variable depends on, if applicable * */
  val dependencyVariableStableId: StableId?

  /** Optional description if the variable's name isn't sufficient. Can vary between manifests. */
  val description: String?

  /** Variable ID. Cannot vary between manifests. */
  val id: VariableId

  /** Whether this variable is internal only. */
  val internalOnly: Boolean

  /** Whether this variable can have multiple values. Cannot vary between manifests. */
  val isList: Boolean

  /**
   * Whether this variable is required to be set if it appears in a questionnaire deliverable and
   * its conditions are met.
   */
  val isRequired: Boolean

  /** Which manifest this object's per-manifest values come from. Can vary between manifests. */
  val manifestId: VariableManifestId?

  /** The variable's user-facing name. Can vary between manifests. */
  val name: String

  /** The variable's position in the manifest, starting from 0. Can vary between manifests. */
  val position: Int

  /** Which sections this variable is recommended by. Can vary between manifests. */
  val recommendedBy: Collection<VariableId>

  /**
   * If this is variable was defined differently in a prior manifest version, the ID of the variable
   * it replaces. Cannot vary between manifests.
   */
  val replacesVariableId: VariableId?

  val stableId: StableId
}

/**
 * Properties that are relevant for variables of all data types. There can be more than one of these
 * for a single variable, since some of the values here can change between manifests.
 *
 * You usually won't access this directly; these properties are all exposed as properties of the
 * classes that implement [Variable].
 */
data class BaseVariableProperties(
    override val deliverablePositions: Map<DeliverableId, Int> = emptyMap(),
    override val deliverableQuestion: String? = null,
    override val dependencyCondition: DependencyCondition? = null,
    override val dependencyValue: String? = null,
    override val dependencyVariableStableId: StableId? = null,
    override val description: String? = null,
    override val id: VariableId,
    override val internalOnly: Boolean = false,
    override val isList: Boolean = false,
    override val isRequired: Boolean = false,
    override val manifestId: VariableManifestId?,
    override val name: String,
    override val position: Int,
    override val recommendedBy: Collection<VariableId> = emptyList(),
    override val replacesVariableId: VariableId? = null,
    override val stableId: StableId,
) : BaseVariable

/**
 * Common interface for all variable types. Defines the generic information about a variable as it
 * appears in a specific manifest. That is, it combines the manifest-independent facts about a
 * variable (its data type, etc.) and information that can vary between manifests for a single
 * variable (its description, etc.)
 *
 * This is a `sealed` interface, meaning that the Kotlin compiler knows that all the implementations
 * are defined here. It can thus do exhaustiveness checks on `when` expressions that look at which
 * class a variable is. Of particular note is that [BaseVariableProperties] is not a variable class
 * (hence the split between `BaseVariable` and this interface).
 */
sealed interface Variable : BaseVariable {
  val type: VariableType

  /**
   * Checks whether or not a value is valid for this variable. This includes both generic and
   * type-specific checks.
   *
   * @param fetchVariable Function to look up a variable. Used for validation of data types that can
   *   include references to other variables. Should throw [VariableNotFoundException] if the
   *   variable does not exist in the manifest.
   * @throws VariableNotListException The variable is not a list but the value had a nonzero list
   *   position.
   * @throws VariableTypeMismatchException The value was of the wrong type.
   * @throws VariableValueInvalidException The value was of the correct type but didn't conform to
   *   this variable's restrictions.
   */
  @Throws(
      VariableNotListException::class,
      VariableTypeMismatchException::class,
      VariableValueInvalidException::class,
  )
  fun validate(
      value: VariableValue<*, *>,
      fetchVariable: (VariableId) -> Variable,
  ) {
    if (!isList && value.listPosition > 0) {
      throw VariableNotListException(id)
    }

    validateForType(value, fetchVariable)
  }

  /**
   * Call [validate] instead of this.
   *
   * Checks whether or not a value is valid for this variable. This has different checks for each
   * data type. This is called by [validate] which is the actual entry point that external callers
   * should use.
   *
   * @throws VariableTypeMismatchException The value was of the wrong type.
   * @throws VariableValueInvalidException The value was of the correct type but didn't conform to
   *   this variable's restrictions.
   */
  fun validateForType(
      value: VariableValue<*, *>,
      fetchVariable: (VariableId) -> Variable,
  )

  /**
   * Converts a value of another variable to a value of this variable, if the existing value is
   * valid for this variable.
   */
  fun convertValue(
      oldVariable: Variable,
      oldValue: VariableValue<*, *>,
      newRowValueId: VariableValueId?,
      fetchVariable: (VariableId) -> Variable,
  ): NewValue? {
    return convertValueForType(oldVariable, oldValue, newRowValueId)?.let { convertedValue ->
      try {
        validate(convertedValue, fetchVariable)
        convertedValue
      } catch (e: Exception) {
        null
      }
    }
  }

  /**
   * Call [convertValue] instead of this.
   *
   * Converts a value of another variable to a value of this variable if their types are compatible.
   */
  fun convertValueForType(
      oldVariable: Variable,
      oldValue: VariableValue<*, *>,
      newRowValueId: VariableValueId?,
  ): NewValue?

  /** Returns a sequence of this variable and its descendents, if any. */
  fun walkTree(): Sequence<Variable> = sequenceOf(this)
}

/**
 * Returns base properties for a copy of an old value using a new variable. Helper function for
 * [Variable.convertValueForType] implementations.
 */
private fun Variable.baseForValue(oldValue: VariableValue<*, *>, newRowValueId: VariableValueId?) =
    BaseVariableValueProperties(
        null,
        oldValue.projectId,
        oldValue.listPosition,
        id,
        oldValue.citation,
        newRowValueId,
    )

data class NumberVariable(
    private val base: BaseVariableProperties,
    val minValue: BigDecimal?,
    val maxValue: BigDecimal?,
    val decimalPlaces: Int?,
) : Variable, BaseVariable by base {
  override val type: VariableType
    get() = VariableType.Number

  override fun validateForType(
      value: VariableValue<*, *>,
      fetchVariable: (VariableId) -> Variable,
  ) {
    if (value !is NumberValue) {
      throw VariableTypeMismatchException(id, VariableType.Number)
    }

    if (minValue != null && value.value < minValue) {
      throw VariableValueInvalidException(id, "${value.value} is less than minimum value $minValue")
    }
    if (maxValue != null && value.value > maxValue) {
      throw VariableValueInvalidException(
          id,
          "${value.value} is greater than maximum value $maxValue",
      )
    }
    if (decimalPlaces != null && value.value.scale() > decimalPlaces) {
      throw VariableValueInvalidException(
          id,
          "${value.value} has more decimal places than the maximum $decimalPlaces",
      )
    }
  }

  override fun convertValueForType(
      oldVariable: Variable,
      oldValue: VariableValue<*, *>,
      newRowValueId: VariableValueId?,
  ): NewValue? {
    return when (oldValue) {
      is NumberValue -> NewNumberValue(baseForValue(oldValue, newRowValueId), oldValue.value)
      is TextValue -> {
        val possibleBigDecimal = oldValue.value.toBigDecimalOrNull()
        possibleBigDecimal?.let { bigDecimal ->
          NewNumberValue(baseForValue(oldValue, newRowValueId), bigDecimal)
        }
      }
      else -> null
    }
  }
}

data class TextVariable(
    private val base: BaseVariableProperties,
    val textType: VariableTextType,
) : Variable, BaseVariable by base {
  override val type: VariableType
    get() = VariableType.Text

  override fun validateForType(
      value: VariableValue<*, *>,
      fetchVariable: (VariableId) -> Variable,
  ) {
    if (value !is TextValue) {
      throw VariableTypeMismatchException(id, VariableType.Text)
    }

    if (textType == VariableTextType.SingleLine && value.value.contains('\n')) {
      throw VariableValueInvalidException(id, "Single-line text value cannot contain line breaks")
    }
  }

  override fun convertValueForType(
      oldVariable: Variable,
      oldValue: VariableValue<*, *>,
      newRowValueId: VariableValueId?,
  ): NewValue? {
    val text =
        when (oldValue) {
          is DateValue -> "${oldValue.value}"
          is EmailValue -> oldValue.value
          is NumberValue -> oldValue.value.stripTrailingZeros().toPlainString()
          is TextValue ->
              if (textType == VariableTextType.MultiLine) {
                oldValue.value
              } else {
                oldValue.value.substringBefore('\n').trim()
              }
          is LinkValue ->
              oldValue.value.title?.let { title -> "$title (${oldValue.value.url})" }
                  ?: "${oldValue.value.url}"
          is SelectValue ->
              if (oldVariable is SelectVariable)
                  oldValue.value
                      .mapNotNull { optionId ->
                        oldVariable.options.firstOrNull { it.id == optionId }?.name
                      }
                      .joinToString(", ")
              else null
          is DeletedValue,
          is ImageValue,
          is SectionValue,
          is TableValue -> null
        }

    return text?.let { NewTextValue(baseForValue(oldValue, newRowValueId), it) }
  }
}

data class DateVariable(
    private val base: BaseVariableProperties,
) : Variable, BaseVariable by base {
  override val type: VariableType
    get() = VariableType.Date

  override fun validateForType(
      value: VariableValue<*, *>,
      fetchVariable: (VariableId) -> Variable,
  ) {
    if (value !is DateValue) {
      throw VariableTypeMismatchException(id, VariableType.Date)
    }
  }

  override fun convertValueForType(
      oldVariable: Variable,
      oldValue: VariableValue<*, *>,
      newRowValueId: VariableValueId?,
  ): NewValue? {
    val date =
        when (oldValue) {
          is DateValue -> oldValue.value
          is TextValue -> {
            try {
              LocalDate.parse(oldValue.value)
            } catch (e: DateTimeParseException) {
              null
            }
          }
          else -> null
        }

    return date?.let { NewDateValue(baseForValue(oldValue, newRowValueId), it) }
  }
}

data class EmailVariable(
    private val base: BaseVariableProperties,
) : Variable, BaseVariable by base {
  override val type: VariableType
    get() = VariableType.Email

  override fun validateForType(
      value: VariableValue<*, *>,
      fetchVariable: (VariableId) -> Variable,
  ) {
    if (value !is EmailValue) {
      throw VariableTypeMismatchException(id, VariableType.Email)
    }

    if (!EmailValidator.getInstance().isValid(value.value)) {
      throw VariableValueInvalidException(id, "Value is not a valid email address")
    }
  }

  override fun convertValueForType(
      oldVariable: Variable,
      oldValue: VariableValue<*, *>,
      newRowValueId: VariableValueId?,
  ): NewValue? {
    val oldText =
        when (oldValue) {
          is TextValue -> oldValue.value
          is EmailValue -> oldValue.value
          else -> null
        }

    val normalizedText = oldText?.trim()?.lowercase()

    return if (normalizedText != null && EmailValidator.getInstance().isValid(normalizedText)) {
      NewEmailValue(baseForValue(oldValue, newRowValueId), normalizedText)
    } else {
      null
    }
  }
}

data class ImageVariable(
    private val base: BaseVariableProperties,
) : Variable, BaseVariable by base {
  override val type: VariableType
    get() = VariableType.Image

  override fun validateForType(
      value: VariableValue<*, *>,
      fetchVariable: (VariableId) -> Variable,
  ) {
    if (value !is ImageValue) {
      throw VariableTypeMismatchException(id, VariableType.Image)
    }
  }

  override fun convertValueForType(
      oldVariable: Variable,
      oldValue: VariableValue<*, *>,
      newRowValueId: VariableValueId?,
  ): NewValue? {
    return if (oldValue is ImageValue) {
      NewImageValue(baseForValue(oldValue, newRowValueId), oldValue.value)
    } else {
      null
    }
  }
}

data class SelectOption(
    val id: VariableSelectOptionId,
    val name: String,
    val description: String?,
    val renderedText: String?,
)

data class SelectVariable(
    private val base: BaseVariableProperties,
    val isMultiple: Boolean,
    val options: List<SelectOption>,
) : Variable, BaseVariable by base {
  private val validOptionIds: Set<VariableSelectOptionId> by lazy { options.map { it.id }.toSet() }

  override val type: VariableType
    get() = VariableType.Select

  override fun validateForType(
      value: VariableValue<*, *>,
      fetchVariable: (VariableId) -> Variable,
  ) {
    if (value !is SelectValue) {
      throw VariableTypeMismatchException(id, VariableType.Select)
    }

    if (!isMultiple && value.value.size > 1) {
      throw VariableValueInvalidException(
          id,
          "Cannot select multiple values on single-selection variable",
      )
    }

    value.value.forEach { optionId ->
      if (optionId !in validOptionIds) {
        throw VariableValueInvalidException(
            id,
            "$optionId is not a valid option ID for this variable",
        )
      }
    }
  }

  override fun convertValueForType(
      oldVariable: Variable,
      oldValue: VariableValue<*, *>,
      newRowValueId: VariableValueId?,
  ): NewValue? {
    // Try to match the textual representation(s) of the old option with the names of the options
    // in this variable.
    val oldValueNames: Collection<String>? =
        when (oldValue) {
          is SelectValue ->
              if (oldVariable is SelectVariable) {
                oldValue.value.mapNotNull { optionId ->
                  oldVariable.options.firstOrNull { it.id == optionId }?.name
                }
              } else {
                null
              }
          is TextValue -> listOf(oldValue.value)
          else -> null
        }

    val newOptionIds =
        oldValueNames?.mapNotNull { oldName ->
          options.firstOrNull { it.name.equals(oldName.trim(), ignoreCase = true) }?.id
        }

    return if (!newOptionIds.isNullOrEmpty()) {
      NewSelectValue(baseForValue(oldValue, newRowValueId), newOptionIds.toSet())
    } else {
      null
    }
  }
}

data class SectionVariable(
    private val base: BaseVariableProperties,
    val renderHeading: Boolean,
    val children: List<SectionVariable> = emptyList(),
    val recommends: List<VariableId> = emptyList(),
) : Variable, BaseVariable by base {
  /** Whether this variable can have multiple values. Always true for sections. */
  override val isList: Boolean
    get() = true

  override val type: VariableType
    get() = VariableType.Section

  override fun validateForType(
      value: VariableValue<*, *>,
      fetchVariable: (VariableId) -> Variable,
  ) {
    if (value !is SectionValue) {
      throw VariableTypeMismatchException(id, VariableType.Section)
    }

    if (value.value is SectionValueVariable) {
      if (
          value.value.usageType == VariableUsageType.Injection && value.value.displayStyle == null
      ) {
        throw VariableValueInvalidException(id, "Display style is required for variable injections")
      }

      val usedVariableId = value.value.usedVariableId

      try {
        fetchVariable(usedVariableId)
      } catch (e: VariableNotFoundException) {
        throw VariableValueInvalidException(
            id,
            "Variable $usedVariableId is used but does not exist in manifest",
        )
      }
    }
  }

  override fun convertValueForType(
      oldVariable: Variable,
      oldValue: VariableValue<*, *>,
      newRowValueId: VariableValueId?,
  ): NewValue? {
    return if (oldValue is SectionValue) {
      NewSectionValue(baseForValue(oldValue, newRowValueId), oldValue.value)
    } else {
      null
    }
  }

  override fun walkTree(): Sequence<Variable> = sequence {
    yield(this@SectionVariable)
    children.forEach { child -> yieldAll(child.walkTree()) }
  }
}

data class TableColumn(
    val isHeader: Boolean,
    val variable: Variable,
)

data class TableVariable(
    private val base: BaseVariableProperties,
    val tableStyle: VariableTableStyle,
    val columns: List<TableColumn>,
) : Variable, BaseVariable by base {
  override val type: VariableType
    get() = VariableType.Table

  override fun validateForType(
      value: VariableValue<*, *>,
      fetchVariable: (VariableId) -> Variable,
  ) {
    if (value !is TableValue) {
      throw VariableTypeMismatchException(id, VariableType.Table)
    }
  }

  override fun convertValueForType(
      oldVariable: Variable,
      oldValue: VariableValue<*, *>,
      newRowValueId: VariableValueId?,
  ): NewValue? {
    return if (oldValue is TableValue) {
      NewTableValue(baseForValue(oldValue, newRowValueId))
    } else {
      null
    }
  }

  override fun walkTree(): Sequence<Variable> = sequence {
    yield(this@TableVariable)
    columns.forEach { column -> yieldAll(column.variable.walkTree()) }
  }
}

data class LinkVariable(
    private val base: BaseVariableProperties,
) : Variable, BaseVariable by base {
  override val type: VariableType
    get() = VariableType.Link

  override fun validateForType(
      value: VariableValue<*, *>,
      fetchVariable: (VariableId) -> Variable,
  ) {
    if (value !is LinkValue) {
      throw VariableTypeMismatchException(id, VariableType.Link)
    }
  }

  override fun convertValueForType(
      oldVariable: Variable,
      oldValue: VariableValue<*, *>,
      newRowValueId: VariableValueId?,
  ): NewValue? {
    return when (oldValue) {
      is LinkValue -> NewLinkValue(baseForValue(oldValue, newRowValueId), oldValue.value)
      is TextValue -> {
        // If the text value was a URI, use it as the link URI.
        val uri =
            try {
              URI.create(oldValue.value)
            } catch (e: Exception) {
              null
            }

        uri?.let { NewLinkValue(baseForValue(oldValue, newRowValueId), LinkValueDetails(it, null)) }
      }
      else -> null
    }
  }
}
