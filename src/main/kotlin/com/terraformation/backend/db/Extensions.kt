package com.terraformation.backend.db

import java.util.*
import org.jooq.Collation
import org.jooq.Field
import org.jooq.impl.DSL

/**
 * Converts a jOOQ field with a nullable column type into a non-nullable one. Use this when the
 * value is guaranteed to never be null (e.g., when querying a non-nullable database column without
 * any left joins) to tell Kotlin's type system about the non-nullability.
 *
 * This is a no-op at runtime, and will likely be optimized out of existence by the JIT compiler.
 */
@Suppress("UNCHECKED_CAST") fun <T : Any> Field<T?>.asNonNullable() = this as Field<T>

/**
 * Returns the PostgreSQL collation identifier to use when sorting text values in a particular
 * locale.
 *
 * [The PostgreSQL docs](https://www.postgresql.org/docs/current/collation.html) have more detail
 * about the collation names, but briefly, both RDS and the default Homebrew PostgreSQL builds have
 * the ICU collations built in, so we'll typically use those for languages other than English.
 *
 * Note that we can't just blindly tack "-x-icu" onto the locale name; it works for some
 * language/country pairs, but if you use a collation name that PostgreSQL doesn't recognize, it's
 * treated as an error.
 *
 * To get a list of available collations (note that you should do this on an RDS database of the
 * same version we're using in production, as the list of collations can change between PostgreSQL
 * releases):
 *
 *     SELECT collname FROM pg_collation;
 */
val Locale.collation: Collation
  get() {
    val collationName =
        when (language) {
          // Languages where we can use the generic locale from ICU. In some cases, e.g.,
          // Portuguese, the translations may vary based on country, but the collation rules are
          // the same across variants.
          "de",
          "en",
          "es",
          "fr",
          "it",
          "ja",
          "ko",
          "ru",
          "pt",
          "se",
          "th",
          "uk",
          "vi" -> "$language-x-icu"
          // Gibberish uses English collation.
          "gx" -> "en-x-icu"
          // Chinese uses simplified or traditional based on locale's country code.
          "zh" ->
              when (country) {
                "HK",
                "TW" -> "zh-Hant-x-icu"
                else -> "zh-Hans-x-icu"
              }
          else -> "default"
        }

    return DSL.collation(collationName)
  }

/** Wraps a field in the "unaccent" function which removes diacritics. */
fun Field<String?>.unaccent() = DSL.function("unaccent", String::class.java, this)
