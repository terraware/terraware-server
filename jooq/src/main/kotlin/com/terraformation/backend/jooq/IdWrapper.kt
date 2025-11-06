package com.terraformation.backend.jooq

import org.jooq.codegen.JavaWriter
import org.jooq.meta.jaxb.ForcedType

class IdWrapper(
    private val className: String,
    includeExpressions: List<String>,
    private val eventLogPropertyName: String = className.replaceFirstChar { it.lowercase() },
) {
  private val converterName = "${className}Converter"
  private val includeExpression = "(?i:" + includeExpressions.joinToString("|") + ")"

  override fun toString() = className

  fun forcedType(targetPackage: String): ForcedType {
    return ForcedType()
        .withUserType("$targetPackage.$className")
        .withConverter("$targetPackage.$converterName")
        .withIncludeTypes("BIGINT")
        .withIncludeExpression(includeExpression)
  }

  fun render(out: JavaWriter) {
    out.println(
        """
      class $className @JsonCreator constructor(@get:JsonValue override val value: Long) : LongIdWrapper<$className> {
        constructor(value: String) : this(value.toLong())
        override val eventLogPropertyName: String get() = "$eventLogPropertyName"
        override fun equals(other: Any?): Boolean = other is $className && other.value == value
        override fun hashCode(): Int = value.hashCode()
        override fun toString(): String = value.toString()
      }
      
      class $converterName : AbstractConverter<Long, $className>(Long::class.java, $className::class.java) {
        override fun from(dbValue: Long?): $className? = dbValue?.let { $className(it) }
        override fun to(wrappedValue: $className?): Long? = wrappedValue?.value
      }

    """
            .trimIndent()
    )
  }
}
