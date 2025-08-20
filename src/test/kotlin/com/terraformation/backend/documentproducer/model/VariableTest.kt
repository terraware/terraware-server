package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableInjectionDisplayStyle
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.VariableSelectOptionId
import com.terraformation.backend.db.docprod.VariableTableStyle
import com.terraformation.backend.db.docprod.VariableTextType
import com.terraformation.backend.db.docprod.VariableUsageType
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.documentproducer.db.VariableNotFoundException
import com.terraformation.backend.documentproducer.db.VariableNotListException
import com.terraformation.backend.documentproducer.db.VariableTypeMismatchException
import com.terraformation.backend.documentproducer.db.VariableValueInvalidException
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VariableTest {
  @Nested
  inner class ValidateGeneric {
    @Test
    fun `rejects nonzero list positions for non-list variables`() {
      assertThrows<VariableNotListException> {
        testValidate(
            TextVariable(baseVariable(), VariableTextType.SingleLine),
            NewTextValue(baseValue(listPosition = 1), "Text"),
        )
      }
    }

    @Test
    fun `allows nonzero list positions for list variables`() {
      testValidate(
          TextVariable(baseVariable(isList = true), VariableTextType.SingleLine),
          NewTextValue(baseValue(listPosition = 1), "Text"),
      )
    }

    @Test
    fun `rejects values of incorrect types`() {
      val numberValue = NewNumberValue(baseValue(), BigDecimal.ONE)
      val textValue = NewTextValue(baseValue(), "Text")

      val cases: List<Pair<Variable, VariableValue<*, *>>> =
          listOf(
              NumberVariable(baseVariable(), null, null, 0) to textValue,
              TextVariable(baseVariable(), VariableTextType.SingleLine) to numberValue,
              DateVariable(baseVariable()) to textValue,
              ImageVariable(baseVariable()) to textValue,
              SelectVariable(baseVariable(), false, emptyList()) to textValue,
              SectionVariable(baseVariable(), true) to textValue,
              TableVariable(baseVariable(), VariableTableStyle.Horizontal, emptyList()) to
                  textValue,
              LinkVariable(baseVariable()) to textValue,
          )

      cases.forEach { (variable, value) ->
        assertThrows<VariableTypeMismatchException>(variable::class.java.simpleName) {
          testValidate(variable, value)
        }
      }
    }
  }

  @Nested
  inner class ValidateNumber {
    @Test
    fun `allows value that meets constraints`() {
      testValidate(
          NumberVariable(baseVariable(), BigDecimal.ONE, BigDecimal.TWO, 2),
          NewNumberValue(baseValue(), BigDecimal("1.54")),
      )
    }

    @Test
    fun `rejects values below minimum value`() {
      assertThrows<VariableValueInvalidException> {
        testValidate(
            NumberVariable(baseVariable(), BigDecimal.TEN, null, 0),
            NewNumberValue(baseValue(), BigDecimal.ONE),
        )
      }
    }

    @Test
    fun `rejects values above maximum value`() {
      assertThrows<VariableValueInvalidException> {
        testValidate(
            NumberVariable(baseVariable(), null, BigDecimal.ONE, 0),
            NewNumberValue(baseValue(), BigDecimal.TWO),
        )
      }
    }

    @Test
    fun `rejects values with too many decimal places`() {
      assertThrows<VariableValueInvalidException> {
        testValidate(
            NumberVariable(baseVariable(), null, null, 3),
            NewNumberValue(baseValue(), BigDecimal("1.2345")),
        )
      }
    }
  }

  @Nested
  inner class ValidateEmail {
    @Test
    fun `allows valid email address`() {
      testValidate(EmailVariable(baseVariable()), NewEmailValue(baseValue(), "valid@example.com"))
    }

    @Test
    fun `rejects valid email address with name`() {
      assertThrows<VariableValueInvalidException> {
        testValidate(
            EmailVariable(baseVariable()),
            NewEmailValue(baseValue(), "Real Person <valid@example.com>"),
        )
      }
    }

    @Test
    fun `rejects text that is not an email address`() {
      assertThrows<VariableValueInvalidException> {
        testValidate(EmailVariable(baseVariable()), NewEmailValue(baseValue(), "invalid"))
      }
    }

    @Test
    fun `rejects lists of email addresses`() {
      assertThrows<VariableValueInvalidException> {
        testValidate(EmailVariable(baseVariable()), NewEmailValue(baseValue(), "a@b.com,c@d.com"))
      }
    }
  }

  @Nested
  inner class ValidateText {
    @Test
    fun `allows single-line value if variable is single-line`() {
      testValidate(
          TextVariable(baseVariable(), VariableTextType.SingleLine),
          NewTextValue(baseValue(), "abc"),
      )
    }

    @Test
    fun `allows multi-line value if variable is multi-line`() {
      testValidate(
          TextVariable(baseVariable(), VariableTextType.MultiLine),
          NewTextValue(baseValue(), "abc\ndef"),
      )
    }

    @Test
    fun `rejects multi-line value if variable is single-line`() {
      assertThrows<VariableValueInvalidException> {
        testValidate(
            TextVariable(baseVariable(), VariableTextType.SingleLine),
            NewTextValue(baseValue(), "abc\ndef"),
        )
      }
    }
  }

  @Nested
  inner class ValidateSelect {
    private val optionId1 = VariableSelectOptionId(1)
    private val optionId2 = VariableSelectOptionId(2)
    private val option1 = SelectOption(optionId1, "Option 1", null, null)
    private val option2 = SelectOption(optionId2, "Option 2", null, null)

    @Test
    fun `allows single selection if variable is single-select`() {
      testValidate(
          SelectVariable(baseVariable(), false, listOf(option1)),
          NewSelectValue(baseValue(), setOf(optionId1)),
      )
    }

    @Test
    fun `allows multiple selection if variable is multi-select`() {
      testValidate(
          SelectVariable(baseVariable(), true, listOf(option1, option2)),
          NewSelectValue(baseValue(), setOf(optionId1, optionId2)),
      )
    }

    @Test
    fun `allows empty selection`() {
      testValidate(
          SelectVariable(baseVariable(), true, listOf(option1)),
          NewSelectValue(baseValue(), emptySet()),
      )
    }

    @Test
    fun `rejects multiple selection if variable is single-select`() {
      assertThrows<VariableValueInvalidException> {
        testValidate(
            SelectVariable(baseVariable(), false, listOf(option1, option2)),
            NewSelectValue(baseValue(), setOf(optionId1, optionId2)),
        )
      }
    }

    @Test
    fun `rejects invalid option ID`() {
      assertThrows<VariableValueInvalidException> {
        testValidate(
            SelectVariable(baseVariable(), false, listOf(option1)),
            NewSelectValue(baseValue(), setOf(optionId2)),
        )
      }
    }
  }

  @Nested
  inner class ValidateSection {
    @Test
    fun `accepts valid text section value`() {
      testValidate(
          SectionVariable(baseVariable(), true),
          NewSectionValue(baseValue(), SectionValueText("Text")),
      )
    }

    @Test
    fun `accepts valid injection`() {
      testValidate(
          SectionVariable(baseVariable(), true),
          NewSectionValue(
              baseValue(),
              SectionValueVariable(
                  VariableId(2),
                  VariableUsageType.Injection,
                  VariableInjectionDisplayStyle.Block,
              ),
          ),
      )
    }

    @Test
    fun `accepts valid reference`() {
      testValidate(
          SectionVariable(baseVariable(), true),
          NewSectionValue(
              baseValue(),
              SectionValueVariable(VariableId(2), VariableUsageType.Reference, null),
          ),
      )
    }

    @Test
    fun `rejects reference to nonexistent variable`() {
      assertThrows<VariableValueInvalidException> {
        testValidate(
            SectionVariable(baseVariable(), true),
            NewSectionValue(
                baseValue(),
                SectionValueVariable(
                    nonexistentVariableId,
                    VariableUsageType.Injection,
                    VariableInjectionDisplayStyle.Block,
                ),
            ),
        )
      }
    }

    @Test
    fun `rejects injection without display style`() {
      assertThrows<VariableValueInvalidException> {
        testValidate(
            SectionVariable(baseVariable(), true),
            NewSectionValue(
                baseValue(),
                SectionValueVariable(VariableId(2), VariableUsageType.Injection, null),
            ),
        )
      }
    }
  }

  @Nested
  inner class ConvertValue {
    @Nested
    inner class ConvertNumber {
      @Test
      fun `returns number if old value is still valid`() {
        assertConversionResult(
            expectedValue = BigDecimal.ONE,
            oldVariable = NumberVariable(baseVariable(1), null, null, 0),
            oldValue = NewNumberValue(baseValue(1), BigDecimal.ONE),
            newVariable =
                NumberVariable(
                    baseVariable(2),
                    minValue = BigDecimal.ZERO,
                    maxValue = null,
                    decimalPlaces = 0,
                ),
        )
      }

      @Test
      fun `returns null if old value is no longer valid`() {
        assertConversionResult(
            expectedValue = null,
            oldVariable = NumberVariable(baseVariable(), null, null, 0),
            oldValue = NewNumberValue(baseValue(), BigDecimal.ONE),
            newVariable =
                NumberVariable(
                    baseVariable(),
                    minValue = BigDecimal.TEN,
                    maxValue = null,
                    decimalPlaces = 0,
                ),
        )
      }

      @Test
      fun `converts text value to number`() {
        assertConversionResult(
            expectedValue = BigDecimal("123.45"),
            oldVariable = TextVariable(baseVariable(1), VariableTextType.SingleLine),
            oldValue = NewTextValue(baseValue(1), "123.45"),
            newVariable = NumberVariable(baseVariable(2), null, null, 2),
        )
      }
    }

    @Nested
    inner class ConvertText {
      @Test
      fun `returns first line of old multi-line value if converting to single-line`() {
        assertConversionResult(
            expectedValue = "First line",
            oldVariable = TextVariable(baseVariable(1), VariableTextType.MultiLine),
            oldValue = NewTextValue(baseValue(1), "First line\nSecond line\n"),
            newVariable = TextVariable(baseVariable(2), VariableTextType.SingleLine),
        )
      }

      @Test
      fun `converts select to comma-separated list`() {
        assertConversionResult(
            expectedValue = "Option 2, Option 3",
            oldVariable =
                SelectVariable(
                    baseVariable(1),
                    true,
                    listOf(
                        SelectOption(VariableSelectOptionId(10), "Option 1", null, null),
                        SelectOption(VariableSelectOptionId(11), "Option 2", null, null),
                        SelectOption(VariableSelectOptionId(12), "Option 3", null, null),
                    ),
                ),
            oldValue =
                NewSelectValue(
                    baseValue(1),
                    setOf(VariableSelectOptionId(11), VariableSelectOptionId(12)),
                ),
            newVariable = TextVariable(baseVariable(2), VariableTextType.SingleLine),
        )
      }

      @Test
      fun `converts date`() {
        assertConversionResult(
            expectedValue = "2023-01-02",
            oldVariable = DateVariable(baseVariable(1)),
            oldValue = NewDateValue(baseValue(1), LocalDate.of(2023, 1, 2)),
            newVariable = TextVariable(baseVariable(2), VariableTextType.SingleLine),
        )
      }

      @Test
      fun `converts number`() {
        assertConversionResult(
            expectedValue = "1.2",
            oldVariable = NumberVariable(baseVariable(1), null, null, 1),
            oldValue = NewNumberValue(baseValue(1), BigDecimal("1.2")),
            newVariable = TextVariable(baseVariable(2), VariableTextType.SingleLine),
        )
      }

      @Test
      fun `converts link with title`() {
        assertConversionResult(
            expectedValue = "Google (https://google.com/)",
            oldVariable = LinkVariable(baseVariable(1)),
            oldValue =
                NewLinkValue(baseValue(1), LinkValueDetails(URI("https://google.com/"), "Google")),
            newVariable = TextVariable(baseVariable(2), VariableTextType.SingleLine),
        )
      }

      @Test
      fun `converts link without title`() {
        assertConversionResult(
            expectedValue = "https://google.com/",
            oldVariable = LinkVariable(baseVariable(1)),
            oldValue =
                NewLinkValue(baseValue(1), LinkValueDetails(URI("https://google.com/"), null)),
            newVariable = TextVariable(baseVariable(2), VariableTextType.SingleLine),
        )
      }
    }

    @Nested
    inner class ConvertDate {
      @Test
      fun `preserves existing date value`() {
        val date = LocalDate.of(2023, 7, 8)
        assertConversionResult(
            expectedValue = date,
            oldVariable = DateVariable(baseVariable(1)),
            oldValue = NewDateValue(baseValue(1), date),
            newVariable = DateVariable(baseVariable(2)),
        )
      }

      @Test
      fun `converts valid date text to date`() {
        assertConversionResult(
            expectedValue = LocalDate.of(2021, 3, 6),
            oldVariable = TextVariable(baseVariable(1), VariableTextType.SingleLine),
            oldValue = NewTextValue(baseValue(1), "2021-03-06"),
            newVariable = DateVariable(baseVariable(2)),
        )
      }

      @Test
      fun `does not convert text that is not a valid date`() {
        assertConversionResult(
            expectedValue = null,
            oldVariable = TextVariable(baseVariable(1), VariableTextType.SingleLine),
            oldValue = NewTextValue(baseValue(1), "xyzzy"),
            newVariable = DateVariable(baseVariable(2)),
        )
      }
    }

    @Nested
    inner class ConvertEmail {
      @Test
      fun `preserves existing email value`() {
        val emailValue = "existing@example.com"

        assertConversionResult(
            expectedValue = emailValue,
            oldVariable = EmailVariable(baseVariable(1)),
            oldValue = NewEmailValue(baseValue(1), emailValue),
            newVariable = EmailVariable(baseVariable(2)),
        )
      }

      @Test
      fun `converts valid email text to email`() {
        val emailValue = "existing@example.com"

        assertConversionResult(
            expectedValue = emailValue,
            oldVariable = TextVariable(baseVariable(1), VariableTextType.SingleLine),
            oldValue = NewTextValue(baseValue(1), emailValue),
            newVariable = EmailVariable(baseVariable(2)),
        )
      }

      @Test
      fun `does not convert text that is not a valid email address`() {
        assertConversionResult(
            expectedValue = null,
            oldVariable = TextVariable(baseVariable(1), VariableTextType.SingleLine),
            oldValue = NewTextValue(baseValue(1), "xyzzy"),
            newVariable = EmailVariable(baseVariable(2)),
        )
      }
    }

    @Nested
    inner class ConvertImage {
      @Test
      fun `preserves existing image value`() {
        val details = ImageValueDetails("caption", FileId(5))
        assertConversionResult(
            expectedValue = details,
            oldVariable = ImageVariable(baseVariable(1)),
            oldValue = NewImageValue(baseValue(1), details),
            newVariable = ImageVariable(baseVariable(2)),
        )
      }

      @Test
      fun `does not convert non-image values`() {
        assertConversionResult(
            expectedValue = null,
            oldVariable = TextVariable(baseVariable(1), VariableTextType.SingleLine),
            oldValue = NewTextValue(baseValue(1), "text"),
            newVariable = ImageVariable(baseVariable(2)),
        )
      }
    }

    @Nested
    inner class ConvertSelect {
      @Test
      fun `maps old options to new options with the same names`() {
        val oldOptions =
            listOf(
                SelectOption(VariableSelectOptionId(5), "Option 1", null, null),
                SelectOption(VariableSelectOptionId(6), "Option 2", null, null),
                SelectOption(VariableSelectOptionId(7), "Option 3", null, null),
            )
        val newOptions =
            listOf(
                SelectOption(VariableSelectOptionId(10), "New Option", null, null),
                SelectOption(VariableSelectOptionId(11), "Option 2", null, null),
                SelectOption(VariableSelectOptionId(12), "Option 3", null, null),
            )
        assertConversionResult(
            expectedValue = setOf(VariableSelectOptionId(11), VariableSelectOptionId(12)),
            oldVariable = SelectVariable(baseVariable(1), true, oldOptions),
            oldValue =
                NewSelectValue(
                    baseValue(1),
                    setOf(
                        VariableSelectOptionId(5),
                        VariableSelectOptionId(6),
                        VariableSelectOptionId(7),
                    ),
                ),
            newVariable = SelectVariable(baseVariable(2), true, newOptions),
        )
      }

      @Test
      fun `converts multi select to single select if one option selected`() {
        val oldOptions =
            listOf(
                SelectOption(VariableSelectOptionId(5), "Option 1", null, null),
                SelectOption(VariableSelectOptionId(6), "Option 2", null, null),
                SelectOption(VariableSelectOptionId(7), "Option 3", null, null),
            )
        val newOptions =
            listOf(
                SelectOption(VariableSelectOptionId(10), "Option 1", null, null),
                SelectOption(VariableSelectOptionId(11), "Option 2", null, null),
                SelectOption(VariableSelectOptionId(12), "Option 3", null, null),
            )
        assertConversionResult(
            expectedValue = setOf(VariableSelectOptionId(11)),
            oldVariable = SelectVariable(baseVariable(1), true, oldOptions),
            oldValue = NewSelectValue(baseValue(1), setOf(VariableSelectOptionId(6))),
            newVariable = SelectVariable(baseVariable(2), false, newOptions),
        )
      }

      @Test
      fun `does not convert multi select to single select if multiple options selected`() {
        val oldOptions =
            listOf(
                SelectOption(VariableSelectOptionId(5), "Option 1", null, null),
                SelectOption(VariableSelectOptionId(6), "Option 2", null, null),
                SelectOption(VariableSelectOptionId(7), "Option 3", null, null),
            )
        val newOptions =
            listOf(
                SelectOption(VariableSelectOptionId(10), "Option 1", null, null),
                SelectOption(VariableSelectOptionId(11), "Option 2", null, null),
                SelectOption(VariableSelectOptionId(12), "Option 3", null, null),
            )
        assertConversionResult(
            expectedValue = null,
            oldVariable = SelectVariable(baseVariable(1), true, oldOptions),
            oldValue =
                NewSelectValue(
                    baseValue(1),
                    setOf(VariableSelectOptionId(6), VariableSelectOptionId(7)),
                ),
            newVariable = SelectVariable(baseVariable(2), false, newOptions),
        )
      }

      @Test
      fun `treats text value as selected option if same as option name ignoring case`() {
        val newOptions =
            listOf(
                SelectOption(VariableSelectOptionId(10), "Option 1", null, null),
                SelectOption(VariableSelectOptionId(11), "Option 2", null, null),
            )
        assertConversionResult(
            expectedValue = setOf(VariableSelectOptionId(11)),
            oldVariable = TextVariable(baseVariable(1), VariableTextType.SingleLine),
            oldValue = NewTextValue(baseValue(1), "option 2"),
            newVariable = SelectVariable(baseVariable(2), false, newOptions),
        )
      }

      @Test
      fun `does not convert other variable types`() {
        val newOptions =
            listOf(
                SelectOption(VariableSelectOptionId(10), "Option 1", null, null),
            )
        assertConversionResult(
            expectedValue = null,
            oldVariable = DateVariable(baseVariable(1)),
            oldValue = NewDateValue(baseValue(1), LocalDate.EPOCH),
            newVariable = SelectVariable(baseVariable(2), false, newOptions),
        )
      }
    }

    @Nested
    inner class ConvertSection {
      @Test
      fun `preserves existing section value`() {
        val fragment = SectionValueText("text")
        assertConversionResult(
            expectedValue = fragment,
            oldVariable = SectionVariable(baseVariable(1), true),
            oldValue = NewSectionValue(baseValue(1), fragment),
            newVariable = SectionVariable(baseVariable(2), true),
        )
      }

      @Test
      fun `does not convert other variable types`() {
        assertConversionResult(
            expectedValue = null,
            oldVariable = TextVariable(baseVariable(1), VariableTextType.MultiLine),
            oldValue = NewTextValue(baseValue(1), "abc\ndef"),
            newVariable = SectionVariable(baseVariable(2), true),
        )
      }
    }

    @Nested
    inner class ConvertTable {
      @Test
      fun `converts existing table value`() {
        // Can't use assertConversionResult because table values don't have payloads
        val oldVariable = TableVariable(baseVariable(1), VariableTableStyle.Horizontal, emptyList())
        val oldValue = NewTableValue(baseValue(1))
        val newVariable = TableVariable(baseVariable(2), VariableTableStyle.Horizontal, emptyList())
        val newRowValueId = VariableValueId(123)

        val converted = tryConvert(oldVariable, oldValue, newVariable, newRowValueId)

        assertEquals(newVariable.id, converted?.variableId, "Variable ID")
        assertEquals(newRowValueId, converted?.rowValueId, "New row value ID")
      }

      @Test
      fun `does not convert other variable types`() {
        assertConversionResult(
            expectedValue = null,
            oldVariable = TextVariable(baseVariable(1), VariableTextType.MultiLine),
            oldValue = NewTextValue(baseValue(1), "abc\ndef"),
            newVariable =
                TableVariable(baseVariable(2), VariableTableStyle.Horizontal, emptyList()),
        )
      }
    }

    @Nested
    inner class ConvertLink {
      @Test
      fun `preserves existing link value`() {
        val details = LinkValueDetails(URI("https://google.com"), "Google")
        assertConversionResult(
            expectedValue = details,
            oldVariable = LinkVariable(baseVariable(1)),
            oldValue = NewLinkValue(baseValue(1), details),
            newVariable = LinkVariable(baseVariable(2)),
        )
      }

      @Test
      fun `converts URLs in text values to links`() {
        assertConversionResult(
            expectedValue = LinkValueDetails(URI("https://google.com"), null),
            oldVariable = TextVariable(baseVariable(1), VariableTextType.SingleLine),
            oldValue = NewTextValue(baseValue(1), "https://google.com"),
            newVariable = LinkVariable(baseVariable(2)),
        )
      }
    }
  }

  /** Variable ID that will fail if a validation rule tries to look it up. */
  private val nonexistentVariableId = VariableId(10)

  /**
   * Helper function to fetch a variable. Returns a dummy variable unless the variable ID is
   * [nonexistentVariableId], in which case it throws an exception.
   */
  private fun mockFetchVariable(variableId: VariableId): Variable {
    if (variableId == nonexistentVariableId) {
      throw VariableNotFoundException(variableId)
    } else {
      return LinkVariable(baseVariable(variableId.value))
    }
  }

  /**
   * Helper function to call a variable's validation function. If the validation function tries to
   * fetch [nonexistentVariableId], the fetch will fail.
   */
  private fun testValidate(variable: Variable, value: VariableValue<*, *>) {
    variable.validate(value, this::mockFetchVariable)
  }

  private fun tryConvert(
      oldVariable: Variable,
      oldValue: VariableValue<*, *>,
      newVariable: Variable,
      newRowValueId: VariableValueId? = null,
  ): NewValue? {
    return newVariable.convertValue(oldVariable, oldValue, newRowValueId, this::mockFetchVariable)
  }

  private fun assertConversionResult(
      expectedValue: Any?,
      oldVariable: Variable,
      oldValue: VariableValue<*, *>,
      newVariable: Variable,
      newRowValueId: VariableValueId? = VariableValueId(123),
  ) {
    val converted = tryConvert(oldVariable, oldValue, newVariable, newRowValueId)

    if (expectedValue != null) {
      assertEquals(expectedValue, converted?.value, "Converted value")
      assertEquals(newVariable.id, converted?.variableId, "Variable ID")
      assertEquals(newRowValueId, converted?.rowValueId, "New row value ID")
    } else {
      assertNull(converted, "Should not have returned a converted value")
    }
  }

  private fun baseValue(variableId: Long = 1, listPosition: Int = 0) =
      BaseVariableValueProperties(
          citation = null,
          id = null,
          listPosition = listPosition,
          projectId = ProjectId(1),
          rowValueId = null,
          variableId = VariableId(variableId),
      )

  private fun baseVariable(id: Long = 1, isList: Boolean = false) =
      BaseVariableProperties(
          id = VariableId(id),
          isList = isList,
          isRequired = false,
          manifestId = VariableManifestId(2),
          name = "Test",
          position = 0,
          stableId = StableId("A"),
      )
}
