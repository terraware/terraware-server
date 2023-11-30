package com.terraformation.backend.db

interface EnumFromReferenceTable<ID : Any, T : Enum<T>> {
  val id: ID
  /** JSON string representation of this enum value. */
  val jsonValue: String
  val tableName: String
}
