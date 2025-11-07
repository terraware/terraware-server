package com.terraformation.backend.db

interface LongIdWrapper<T : LongIdWrapper<T>> : Comparable<T> {
  val value: Long
  val eventLogPropertyName: String

  override fun compareTo(other: T): Int = value.compareTo(other.value)
}
