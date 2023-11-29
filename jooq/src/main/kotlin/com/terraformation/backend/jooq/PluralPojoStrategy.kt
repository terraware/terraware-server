package com.terraformation.backend.jooq

import org.jooq.codegen.DefaultGeneratorStrategy
import org.jooq.codegen.GeneratorStrategy
import org.jooq.meta.Definition
import org.jooq.meta.EmbeddableDefinition

/**
 * Generate less-awkward names for POJO classes for tables with plural names. For example, if we
 * have a table "automobiles", the default jOOQ behavior is to generate a POJO class called
 * `Automobiles`, but that class name implies that it's a collection of some kind. This strategy
 * causes the class to be called `AutomobilesRow` instead.
 */
class PluralPojoStrategy : DefaultGeneratorStrategy() {
  override fun getJavaClassName(definition: Definition, mode: GeneratorStrategy.Mode): String {
    val transformedName = super.getJavaClassName(definition, mode)
    return if (mode == GeneratorStrategy.Mode.POJO && definition !is EmbeddableDefinition) {
      "${transformedName}Row"
    } else {
      transformedName
    }
  }
}
