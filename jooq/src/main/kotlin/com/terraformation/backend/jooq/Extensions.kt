package com.terraformation.backend.jooq

import org.jooq.meta.jaxb.EmbeddableDefinitionType
import org.jooq.meta.jaxb.EmbeddableField

/** Converts "foo_bar_baz" to "fooBarBaz". */
fun String.toCamelCase() =
    replace(Regex("_(.)")) { match -> match.groupValues[1].replaceFirstChar { it.uppercaseChar() } }

/** Converts "foo_bar_baz" to "FooBarBaz". */
fun String.toPascalCase() = toCamelCase().replaceFirstChar { it.uppercaseChar() }

fun EmbeddableDefinitionType.withColumns(vararg columns: String): EmbeddableDefinitionType {
  return withFields(columns.map { EmbeddableField().withExpression(it) })
}
