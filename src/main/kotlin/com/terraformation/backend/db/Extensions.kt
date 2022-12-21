package com.terraformation.backend.db

import org.jooq.Field

/**
 * Converts a jOOQ field with a nullable column type into a non-nullable one. Use this when the
 * value is guaranteed to never be null (e.g., when querying a non-nullable database column without
 * any left joins) to tell Kotlin's type system about the non-nullability.
 *
 * This is a no-op at runtime, and will likely be optimized out of existence by the JIT compiler.
 */
@Suppress("UNCHECKED_CAST") fun <T : Any> Field<T?>.asNonNullable() = this as Field<T>
