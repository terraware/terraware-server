# Custom build components

The code in this directory is compiled and made available on the classpath during the build process. It is not made available to the application code, just to the build itself.

## Generated enum classes

The application has a number of reference tables for enumerated values. For example, valid accession states are enumerated in the `accession_state` table.

In cases where the values are user-editable, or where they're used purely to populate UI elements with lists of valid values, they are treated as ordinary foreign key relationships in the generated jOOQ code.

However, in cases where the values affect server-side business logic, it's useful to turn them into `enum` classes so they're strongly typed. jOOQ doesn't have built-in support for that, so we use a [custom generator class](src/main/kotlin/com/terraformation/seedbank/jooq/EnumGenerator.kt) to do it.
