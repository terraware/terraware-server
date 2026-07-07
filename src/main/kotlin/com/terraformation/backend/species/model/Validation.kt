package com.terraformation.backend.species.model

private val emEnDashRegex = Regex("[—–]")
private val invalidScientificNameChars = Regex("[^A-Za-z. -]")
private val rankMarkers = setOf("f.", "subsp.", "var.")
private val whitespaceRegex = Regex("\\s+")

/**
 * Validates the syntax of a scientific name. This does not check for higher-level conditions such
 * as collisions with existing names, just verifies that the format is correct. Calls one of the
 * caller-supplied functions if validation fails.
 */
fun validateScientificNameSyntax(
    value: String,
    onTooShort: () -> Unit,
    onTooLong: () -> Unit,
    onInvalidCharacter: (String) -> Unit,
) {
  val normalizedValue = normalizeScientificName(value)
  val invalidChar = invalidScientificNameChars.find(normalizedValue)?.value
  if (invalidChar != null) {
    onInvalidCharacter(invalidChar)
  }

  val wordCount = normalizedValue.split(' ').size
  if (wordCount < 2) {
    onTooShort()
  } else if (wordCount > 4) {
    onTooLong()
  }
}

/**
 * Transforms a full species scientific name to a normalized form.
 *
 * Normalized species names are either two words (genus and specific epithet) or four words (genus,
 * specific epithet, infraspecific rank abbreviation, infraspecific epithet). For example, "Aloe
 * vera" or "Euphorbia milii var. splendens".
 *
 * Authorship, hybrid markers, and other elements are removed, and em- and en-dashes are turned to
 * hyphens.
 */
fun normalizeScientificName(value: String): String {
  val words = value.replace("×", "").replace(emEnDashRegex, "-").trim().split(whitespaceRegex)

  val numSignificantWords =
      when {
        words.size < 2 -> throw IllegalArgumentException("$value is not a valid species name")
        words.size >= 4 && words[2] in rankMarkers -> 4
        else -> 2
      }

  return words.subList(0, numSignificantWords).joinToString(" ")
}
