package com.terraformation.backend.db

/**
 * Initializes jOOQ's generated key classes on the calling thread. Call this before any code that
 * might use jOOQ from multiple threads.
 *
 * The generated `Keys.kt` files for different schemas refer to each other's keys, and their static
 * initializers form a cycle (for example, nursery keys refer to seedbank keys, which refer to
 * default_schema keys, which indirectly refer to nursery keys). If two threads start initializing
 * different schemas' key classes at the same time, each one can end up waiting for a class the
 * other one is initializing, and the JVM blocks both threads forever.
 */
fun initializeJooqKeys() {
  DefaultCatalog.DEFAULT_CATALOG.schemas.forEach { schema ->
    schema.tables.forEach { table ->
      table.primaryKey
      table.references
    }
  }
}
