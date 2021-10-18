package com.terraformation.backend.util

/**
 * Holds a value that should be computed on first access and then reused after that. This is similar
 * to the `val x: Type by lazy {}` construct, but allows the computation function to live at the
 * point of access rather than the point of declaration of the variable, which can sometimes improve
 * clarity.
 *
 * This class is not thread-safe; if it is used concurrently in multiple threads, it is possible for
 * the computation function to be called twice.
 */
class MemoizedValue<T : Any> {
  private var value: T? = null

  /** True if the value has already been computed. */
  val isComputed: Boolean
    get() = value != null

  /**
   * Returns the value, computing it if it hasn't previously been accessed.
   *
   * If another thread is currently computing the value, blocks until that thread finishes.
   */
  fun get(computeFunc: () -> T): T {
    return value
        ?: synchronized(this) {
          value
              ?: run {
                val computedValue = computeFunc()
                value = computedValue
                computedValue
              }
        }
  }
}
