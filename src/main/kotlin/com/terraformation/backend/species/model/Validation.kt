package com.terraformation.backend.species.model

private val invalidScientificNameChars = Regex("[^A-Za-z. -]")

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
 * Normalizes the scientific name for a species. Normalizations performed include:
 * - replace en- and em-dashes with hyphens
 */
fun normalizeScientificName(value: String): String {
  // normalize dashes
  return value.replace(Regex("[—–]"), "-")
}
