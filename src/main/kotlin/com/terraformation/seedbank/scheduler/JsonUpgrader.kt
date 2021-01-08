package com.terraformation.seedbank.scheduler

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * A class that can upgrade JSON representations of previous versions of a class such that they can
 * be deserialized using the current version of the class. For example, if a field is renamed, this
 * would replace the old field name with the new one in the JSON data, after which the object could
 * be deserialized successfully.
 *
 * A single upgrader may handle multiple classes, but each class may by handled by at most one
 * upgrader.
 */
interface JsonUpgrader {
  /** Which classes this upgrader can handle. */
  val classNames: List<String>

  /**
   * Modifies the JSON representation of an object so that it can be deserialized as an instance of
   * the current version of the class.
   *
   * @param className Which class's data to update.
   * @param version Which version of the class was originally serialized.
   * @param tree Raw JSON data as originally serialized. This is mutable.
   * @return A correctly-transformed version of the data. This may be the same as [tree] if the tree
   * was modified in place, or it can be a newly-constructed structure.
   */
  fun upgrade(className: String, version: Int, tree: ObjectNode): ObjectNode
}

/**
 * Indicates the version of a JSON-serializable class. When the class definition changes in such a
 * way that an object that was serialized using the previous version of the class can't be
 * deserialized correctly with the current version of the class, the value of this annotation should
 * be increased. The serializer will then look for a [JsonUpgrader] for the class to transform the
 * old object's JSON representation such that the object can be deserialized.
 *
 * The serializer ignores unknown fields and sets missing fields to `null` (for nullable fields),
 * `false` (for boolean fields), or 0 (for numeric fields). So removing a field or adding a nullable
 * or primitive field doesn't require bumping the version, but renaming a field or adding a
 * non-nullable field does.
 */
annotation class JsonVersion(val value: Int = 0)

val Class<*>.jsonVersionFromAnnotation
  get() = getDeclaredAnnotation(JsonVersion::class.java)?.value ?: 0
