package com.terraformation.backend.file

import java.nio.file.Path

/**
 * Deletes a file or directory after passing it to a function. If [this] is a directory, its
 * contents are deleted too. This would typically be used like
 *
 * ```
 * createTempDirectory().useAndDelete { dir -> ... }
 * ```
 */
fun <T> Path.useAndDelete(func: (Path) -> T): T {
  return try {
    func(this)
  } finally {
    toFile().deleteRecursively()
  }
}
