package com.terraformation.backend.search.field

import org.jooq.Field
import org.jooq.impl.DSL

/**
 * Search field that is only available to some users. Wraps an underlying search field. If the user
 * fails the permission check, values of this field are always null.
 */
class RestrictedField(
    private val underlyingField: SearchField,
    private val permissionCheck: () -> Boolean,
) : SearchField by underlyingField {
  private val nullSelectFields = underlyingField.selectFields.map { DSL.castNull(it) }

  override val orderByField: Field<*>
    get() = DSL.castNull(underlyingField.orderByField)

  override val selectFields: List<Field<*>>
    get() {
      return if (permissionCheck()) {
        underlyingField.selectFields
      } else {
        nullSelectFields
      }
    }
}

fun SearchField.withRestriction(permissionCheck: () -> Boolean): RestrictedField =
    RestrictedField(this, permissionCheck)
