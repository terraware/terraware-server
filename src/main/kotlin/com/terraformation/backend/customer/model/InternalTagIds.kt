package com.terraformation.backend.customer.model

import com.terraformation.backend.db.default_schema.InternalTagId

/**
 * System-defined internal tag IDs that the application code cares about. This isn't a
 * jOOQ-generated enum because there can also be admin-defined internal tags.
 */
object InternalTagIds {
  val Reporter = InternalTagId(1)
  val Internal = InternalTagId(2)
  val Testing = InternalTagId(3)
}
