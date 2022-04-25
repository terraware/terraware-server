package com.terraformation.backend.jooq

import org.jooq.meta.jaxb.EmbeddableDefinitionType
import org.jooq.meta.jaxb.EmbeddableField

/** Converts "foo_bar_baz" to "FooBarBaz". */
fun String.toPascalCase() = replace(Regex("_(.)")) { it.groupValues[1].capitalize() }.capitalize()

fun EmbeddableDefinitionType.withColumns(vararg columns: String): EmbeddableDefinitionType {
  return withFields(columns.map { EmbeddableField().withExpression(it) })
}
