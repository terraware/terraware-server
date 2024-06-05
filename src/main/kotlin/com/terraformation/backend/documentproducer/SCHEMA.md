# Database schema

This document describes the structure of the database schema. It doesn't attempt to document every column of every table; see the table and column comments for more fined-grained documentation.

## Documents

Top-level information about documents is in the `documents` table. This is a mutable table; a given document's ID stays stable over time.

A document is associated with a specific variable manifest; this is discussed in more detail below.

## Versioning

A lot of the data related to documents is treated as immutable. When we want to change something, we create a new copy of it and, if needed, include a reference to the previous version in the new copy. The old version continues to exist.

This approach is so we can evolve the definitions of documents without modifying existing documents. We don't want to modify existing documents because we need to support saving documents, and also because we'll likely want to support editing documents without migrating them to the latest versions (the "Verra asked for changes and we don't want to have to revamp the whole document just to change one paragraph" use case).

You can think of it a bit like git: commits are immutable, some commits are tagged, you can always check out an old commit, and changing a file means you're creating a new commit rather than altering an existing one in place.

## Variables

Terminology:

* Variable: Describes a piece of information we need to know about a project.
* Variable manifest: A list of all the variables for a particular methodology.
* Variable value: The user-supplied (or calculated) value of a particular variable in a particular document.

`variable_manifests` contains metadata about variable manifests. The manifest with the highest ID for a given methodology is considered the current manifest for that methodology.

`variables` contains basic structural information about variables, including how a variable relates to other variables. Inter-variable relationships are described below.

`variable_manifest_entries` defines which variables appear in which manifests in what order. A variable can appear in multiple manifests if its definition doesn't change. Variable names and descriptions are also here, because we want to be able to rename or document a variable without changing its underlying identity.

Each variable has a data type. Some data types have data-type-specific configuration that can differ from one variable to another. This type-specific configuration is stored in child tables such as `variable_numbers`.

The list of data types in the database schema is not exactly the same as the list of data types in the product.

### Select variables

Single-selection and multiple-selection variables are modeled the same way in the schema.

`variable_selects.is_multiple` determines whether or not multiple selections are allowed.

`variable_select_options` has the ordered list of available options.

### Lists

The product has a "list" data type, but the database schema doesn't. Instead, any variable can be a list or not, depending on the value of `variables.is_list`.

Lists will be discussed in more detail in the section on variable values.

### Tables

In the schema, a table is a variable that acts as a container for an ordered list of columns, where each column is itself a variable.

On a table variable, `variables.is_list` controls whether or not the table allows multiple rows.

The variable for each column must be listed in `variable_table_columns`, which links it to the table variable.

The order that columns appear in the table is based on their variable IDs, which in turn are based on the order they appear in the manifest.

Since a table is a type of variable, and any type of variable can be a column in a table, the database schema is able to represent nested tables in case we need them in the future.

### Sections

Sections define the contents of the rendered document will be organized. In product discussions, you will hear the terms "template" and "outline" used; those concepts are represented as sections in the database schema.

A section is a variable that can optionally be a child of another section, forming a tree structure. `variable_sections.parent_variable_id` and `variable_sections.position` define the parent-child relationships and the order of the child sections.

By default, the name of each section will be rendered as a numbered header in the document. However, if `variable_sections.render_heading` is false, the section header won't be included. This is to support cases where we want to break textual parts of the document up into a sequence of smaller parts for purposes of editing, but present it as a single contiguous span of text in the finished document. We'd model that by putting child sections under the parent section and setting their `render_heading`s to false.

Sections are always lists, that is, `variables.is_list` is always true for a section variable. This will be discussed in more detail in the description of section variable values below.

### Recommendations

A section can "recommend" other variables; these recommendations are presented by default in the section editing UI so users can easily inject them into the text of sections.

The recommendations are stored in `variable_section_recommendations`. They are per-manifest-version because they can be added and removed over time.

## Variable versioning

When a new variable manifest is uploaded, its contents are compared with the current manifest for the same methodology. (How the comparison works is an application implementation detail that doesn't affect the database schema.) For each variable, there are three possible outcomes.

If a variable from the current manifest is not present in the new manifest, we skip inserting a `variable_manifest_entries` row for it.

If a variable from the new manifest is not present in the current manifest, we insert a `variables` row for it with `replaces_variable_id` set to null, and then insert a `variable_manifest_entries` row with the new manifest ID and the new variable ID.

If the variable's configuration is unchanged, or the variable was renamed but not changed other than that, we insert a new row into `variable_manifest_entries` with the new manifest ID and the existing variable ID. Existing variable values don't need to be touched since the variable ID hasn't changed.

If the variable's configuration has changed, e.g., an option has been added to a select variable, we insert a new `variables` row for it with `replaces_variable_id` set to the ID of the variable in the current manifest, and then insert a `variable_manifest_entries` row with the new manifest ID and the new variable ID.

A change to the configuration of any of the columns of a table is considered a change to the configuration of the table variable. That is, if you have a table T with columns A, B, and C, and a new manifest updates one of the columns such that you now have A, B', and C, you'll also get T'.

See below for more on `replaces_variable_id`.

## Variable values

Variable values are versioned; when the user edits the value of a variable, we insert it into the database and leave the previous value in place. This gives us a history of changes, and is also key to the implementation of saved document versions, which are discussed later.

`variable_values` contains the non-type-specific metadata for values, and also contains the values for simple scalars such as numbers and text variables.

For non-scalar data types, the values are stored in child tables such as `variable_image_values`.

### Lists

`variable_values.list_position` is the list position of the value. For single-value variables (where `variables.is_list` is false) the position is always 0. For list variables, there is one `variable_values` row per entry in the list, each with a different position.

You can think of non-list variables as having a maximum list size of 1.

### Tables

Tables are modeled as lists of row values. The value of each cell in a row is modeled as a value of the variable that represents the cell's column. Each cell's value is associated with the row value for its containing row.

Leaving versioning aside for the moment, this means:

* Each row in the table value has a `variable_values` row whose `variable_id` is the ID of the variable that represents the table as a whole.
* Each cell in the table value has a `variable_values` row whose `variable_id` is the ID of the variable that represents its column.
* Each cell also has a `variable_value_table_rows` row that links it to its row.

Cell values are just variable values and have the same versioning behavior as standalone variables: edits are represented as new values for the same variable ID, associated with the same row.

### Sections

Section contents are modeled as lists of values. Each value can either be text or a reference to another variable.

When we render the whole document, we do a depth-first pre-order traversal of the tree of section variables. For each one, we render its contents, then recursively render its children.

When a section uses a variable, the usage is either an "injection" or a "reference" as determined by `variable_section_values.usage_type_id`.

An injection means we insert an appropriately-formatted version of the current value of the variable. "Appropriately-formatted" will vary considerably depending on the variable type: the value of a number variable will be rendered inline as part of the surrounding text, whereas the value of an image variable will be rendered as a separate element in the document with a caption included. (The details of rendering are outside the scope of the database schema, of course.)

A reference means that we insert a short label indicating where in the document the variable is injected. For sections, this will be the section number. For images and tables, we will generate labels like "Figure 17" based on where variables are injected in the document, and the reference will be the label.

`variable_section_values` also has other values to fine-tune the rendering, e.g., whether to render a list as a sentence fragment that can appear in the middle of a paragraph or as a bullet list. Some of these settings will only  apply to certain data types.

## Citations

Each variable value can optionally have a citation; it is stored in `variable_values.citation`.

There is no deduplication of citations: if two values cite the same source, each one will have the same `variable_values.citation` value. The product requirements say that the citation of each value should be editable without affecting any other citations, so deduplication would complicate update operations for no benefit.

### Citations on lists, tables, sections

Currently, there is no explicit way to associate a citation with a variable as a whole, just with individual values.

In cases where the UI needs to present a citation as if it applies to an entire list or table or section, we'll use a convention of putting the citation on the first list element.

A table is a list where each value represents a row, so for tables, this means putting the citation on the first row. If the table is empty, create a row to hold the citation; it's totally fine to have a row with no column values attached to it.

A section is also a list, so this means putting the value on the first value in the list of the secion's fragments. If the section is empty, create a text section value with an empty string as the text.

## Migrating documents to new manifests

A document is always associated with a particular manifest. When a new manifest is created, existing documents continue to be associated with the previous one until they are migrated to the new one. (Whether this happens automatically or via explicit user action is a UI question that doesn't affect the database schema.)

Recall that when a variable's definition changes between manifests, the new definition is considered a different variable and is linked to the old one via `variables.replaces_variable_id`.

At a high level, the process of migrating a document's data to a new manifest looks like this:

* For each variable in the new manifest:
  * If the document has no value with the variable's ID:
    * For each of the values associated with the variable's `replaces_variable_id`, if any:
      * If the existing value is still valid given the variable's settings in the new manifest:
        * Insert a new `variable_values` row (and any child table rows as dictated by the variable type) with the new variable ID and the existing value.

Tables are a bit more complex. If the definition of any column variable has changed, or if a column has been added or removed, the table as a whole is considered to have changed, and there will be replacements for all the column variables, even ones whose definitions didn't change, as well as a replacement for the table variable itself. Since the table variable is new, we need a new `variable_values` for each row. And then we need new `variable_values` rows for all the cells with their `variable_id`s pointing to the new column variables and their `table_row_value_id`s pointing to the replacement rows.

Variable references in sections also require additional handling since they need to point to the new versions of variables:

* For each variable in the new manifest that has a `replaces_variable_id` value:
  * For each section value whose `referenced_variable_id` is equal to the outer loop's `replaces_variable_id`:
    * Insert new `variable_values` and `variable_section_values` rows with the same section variable ID and list position, but with `referenced_variable_id` set to the new ID of the variable from the outer loop.

## Saved versions

Because changes variable values are nondestructive, saving a document doesn't require copying any data. All we need to do is record the ID of the variable manifest that was being used by the document at the time it was saved, and the maximum variable value ID that existed at the time.

Retrieving a saved version's variable values then becomes a search filter along the lines of `WHERE variable_values.id <= document_saved_versions.max_variable_value_id`. Any subsequent edits, including migrations to new variable manifest versions, will be disregarded.

Similar filters can be applied to variables to get a snapshot of the configuration as it existed at the time the version was saved; variables that were defined in later manifests will have higher IDs and will be filtered out.

Being able to work with the values and definitions of variables that have subsequently been removed or redefined means we can support editing old documents without forcing them to migrate to new variable manifest versions. This will be important after documents are submitted to Verra: it can take months to get feedback, and in response to the feedback we may only need to make minor changes to a document. We want to be able to make those changes and resubmit a revised document without also pulling in whatever unrelated manifest changes have happened in the meantime.

Note that the above only applies to the data in the database. You can't count on being able to reproduce a byte-identical rendered document over time, because the rendering code in the application might have changed. We will thus probably also want to save a copy of the rendered document, but that's outside the scope of the database schema.

## Integrity constraints

The document tables have integrity constraints to guarantee as much correctness and internal consistency as possible. In some cases these require tables to have redundant columns or redundant unique constraints.

Child tables that hold per-type information, e.g., `variable_numbers`, have both a parent ID and a type ID, even though the type is already available by joining with the parent table by ID. This is to support type safety on child tables. A foreign-key constraint on (parent ID, type ID) and a check constraint on type ID, in combination, guarantee that the correct child table is used. For example, if `variables.variable_type_id` says a variable is text, the database will forbid you from inserting a `variable_numbers` row for it.
