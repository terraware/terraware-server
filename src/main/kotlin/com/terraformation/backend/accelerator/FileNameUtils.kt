package com.terraformation.backend.accelerator

/**
 * Matches characters that aren't allowed in filenames.
 *
 * Based on the naming conventions section of the
 * [Win32 documentation](https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file).
 */
private val illegalFilenameCharacters = Regex("[<>:\"/\\\\|?*]")

/**
 * Replaces characters that aren't safe to include in filenames with hyphens.
 *
 * The list of unsafe characters is from
 * [Microsoft](https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file).
 */
fun sanitizeForFilename(fileName: String): String {
  return fileName.replace(illegalFilenameCharacters, "-")
}
