package com.terraformation.backend.db

interface EnumFromReferenceTable<T : Enum<T>> {
  val id: Int
  val displayName: String
  val tableName: String
}
