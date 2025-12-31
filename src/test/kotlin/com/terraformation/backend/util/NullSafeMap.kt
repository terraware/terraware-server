package com.terraformation.backend.util

/**
 * A map that only allows non-null value types and throws an exception on [get] if a key isn't
 * found. This allows calling code to do null-safe lookups without having to use `!!` or `?:`.
 */
class NullSafeMap<K, V : Any>(private val map: Map<K, V>) : Map<K, V> by map {
  override operator fun get(key: K): V {
    return map.getValue(key)
  }
}

/**
 * A mutable map that only allows non-null value types and throws an exception on [get] if a key
 * isn't found. This allows calling code to do null-safe lookups without having to use `!!` or `?:`.
 */
class NullSafeMutableMap<K, V : Any>(private val map: MutableMap<K, V>) : MutableMap<K, V> by map {
  override operator fun get(key: K): V {
    return map.getValue(key)
  }
}

/** Returns a null-safe map with a given set of elements. */
fun <K, V : Any> nullSafeMapOf(vararg pairs: Pair<K, V>): NullSafeMap<K, V> =
    NullSafeMap(mapOf(*pairs))

/** Returns a null-safe mutable map with a given set of elements. */
fun <K, V : Any> nullSafeMutableMapOf(vararg pairs: Pair<K, V>): NullSafeMutableMap<K, V> =
    NullSafeMutableMap(mutableMapOf(*pairs))

/** Returns a version of this map whose `get` cannot return null. */
fun <K, V : Any> Map<K, V>.toNullSafe() = this as? NullSafeMap<K, V> ?: NullSafeMap(this)

/** Returns a version of this map whose `get` cannot return null. */
fun <K, V : Any> MutableMap<K, V>.toNullSafe() =
    this as? NullSafeMutableMap<K, V> ?: NullSafeMutableMap(this)
