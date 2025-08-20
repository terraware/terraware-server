package com.terraformation.backend.search.field

import com.terraformation.backend.search.SearchFieldPath

/**
 * An alternate name for another search field. Using an alias is equivalent to using the target
 * field name, except that search results will use the alias's name.
 *
 * This is primarily used to support short names for fields in flattened sublists for backward
 * compatibility with earlier versions of the accession search API.
 */
class AliasField
private constructor(
    /** Alternate name for the field. */
    override val fieldName: String,

    /**
     * Which underlying field this alias refers to. The path must be relative to the table that
     * contains this alias field. It can refer to flattened sublists but not nested ones.
     */
    val targetPath: SearchFieldPath,

    /**
     * The underlying field. Other than the field name, all the properties on the alias delegate to
     * this.
     */
    val original: SearchField,
) : SearchField by original {
  constructor(
      fieldName: String,
      targetPath: SearchFieldPath,
  ) : this(fieldName, targetPath, targetPath.searchField)

  init {
    if (targetPath.isNested) {
      throw IllegalArgumentException("Cannot alias nested path $targetPath")
    }
  }

  override fun raw(): SearchField? {
    return if (localize) {
      val rawOriginal = original.raw() ?: return null
      AliasField(rawFieldName(), SearchFieldPath(targetPath.prefix, rawOriginal), rawOriginal)
    } else {
      null
    }
  }

  override fun rawFieldName() = if (localize) "$fieldName(raw)" else fieldName
}
