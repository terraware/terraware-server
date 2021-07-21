package com.terraformation.backend.jooq

/** Converts "foo_bar_baz" to "FooBarBaz". */
fun String.toPascalCase() = replace(Regex("_(.)")) { it.groupValues[1].capitalize() }.capitalize()
