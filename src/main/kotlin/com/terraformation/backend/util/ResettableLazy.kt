package com.terraformation.backend.util

/**
 * Lazy-initializing delegate that allows resetting the cached value.
 *
 * Usage:
 *
 *     fun computeValue(): XYZ { ... }
 *     // The ResettableLazy is stored separately from the delegating property
 *     private val resettableXyz = ResettableLazy { computeValue() }
 *     // Property delegates to the ResettableLazy
 *     val xyz: XYZ by resettableXyz
 *     // Access the lazy-initialized value as you would with a "by lazy" property
 *     println(xyz.field)
 *     // Reset the property to cause its compute function to be called again on next access
 *     resettableXyz.reset()
 */
class ResettableLazy<out T>(private val computeValue: () -> T) : Lazy<T> {
  @Volatile private var cachedValue: Any? = Uninitialized

  override val value: T
    get() {
      val valueCopy = cachedValue
      return if (valueCopy !== Uninitialized) {
        @Suppress("UNCHECKED_CAST")
        valueCopy as T
      } else {
        synchronized(this) {
          // Value may have been initialized by another thread before we acquired the lock.
          val valueCopyAfterLock = cachedValue
          if (valueCopyAfterLock !== Uninitialized) {
            @Suppress("UNCHECKED_CAST")
            valueCopyAfterLock as T
          } else {
            val newValue = computeValue()
            cachedValue = newValue
            newValue
          }
        }
      }
    }

  /**
   * Resets the object, clearing its cached value. The [computeValue] function will be called the
   * next time the object's value is accessed.
   */
  fun reset() {
    cachedValue = Uninitialized
  }

  override fun isInitialized(): Boolean = cachedValue !== Uninitialized

  override fun toString(): String = value.toString()

  override fun equals(other: Any?): Boolean = value?.equals(other) ?: (other == null)

  override fun hashCode(): Int = value.hashCode()

  companion object {
    /**
     * Sentinel value to indicate that [cachedValue] needs to be computed. We use this rather than
     * null because it's valid for [T] to be a nullable type, and thus [computeValue] may return a
     * legitimate null value.
     */
    private object Uninitialized
  }
}
