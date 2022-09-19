package com.terraformation.backend.species.model

private val invalidScientificNameChars = Regex("[^A-Za-z. ]")

/**
 * Validates the syntax of a scientific name. This does not check for higher-level conditions such
 * as collisions with existing names, just verifies that the format is correct. Calls one of the
 * caller-supplied functions if validation fails.
 */
fun validateScientificNameSyntax(
    value: String,
    onTooShort: () -> Unit,
    onTooLong: () -> Unit,
    onInvalidCharacter: (String) -> Unit
) {
  val invalidChar = invalidScientificNameChars.find(value)?.value
  if (invalidChar != null) {
    onInvalidCharacter(invalidChar)
  }

  val wordCount = value.split(' ').size
  if (wordCount < 2) {
    onTooShort()
  } else if (wordCount > 4) {
    onTooLong()
  }
}
