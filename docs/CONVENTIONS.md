# Coding conventions

Code should be written in idiomatic Kotlin and should generally follow the guidelines in the official Kotlin [Coding Conventions](https://kotlinlang.org/docs/reference/coding-conventions.html) document.

It runs on the JVM, and thus makes extensive use of Java libraries. There are no plans to support multiplatform builds. If you find a Java library that does just what you need, use it rather than reinventing the wheel!

Currently, the build targets the Java 24 JVM. We use the Amazon Corretto JVM and will generally target the most recent Corretto release.

## Formatting

All Kotlin code must be formatted with [ktfmt](https://github.com/facebookincubator/ktfmt). You can run ktfmt in a couple different ways:

* Install the [ktfmt IntelliJ plugin](https://plugins.jetbrains.com/plugin/14912-ktfmt) (recommended). This will cause IntelliJ's "Reformat Code" action to use ktfmt (Default Code Style is fine).
  * You can also enable this to run automatically on saving of the file if you want. To do this: Preferences -> Tools -> Actions on Save, enable Reformat Code (optionally limit file type) -> OK.
* Invoke it from the command line. The project's Gradle configuration has a task for that purpose. On Mac/Linux, run `./gradlew spotlessApply` and on Windows, run `gradlew.bat spotlessApply`.

There isn't currently a way to make IntelliJ's real-time formatting adhere strictly to ktfmt's formatting rules, but the supplied `.editorconfig` file is an approximation. IntelliJ should detect it and use it automatically.

To prevent yourself from accidentally pushing a PR that fails the formatting check, you can add a git pre-push hook. There's an example in this directory: [pre-push](pre-push).

### Wildcard imports

We import individual classes and symbols rather than using wildcard imports.

By default, IntelliJ uses wildcard imports for the `java.util` and `javax` packages, and overriding that default in `.editorconfig` doesn't work reliably. You'll want to remove those packages manually from the Kotlin code style preferences ("Auto-Import" tab) in IntelliJ's settings.

## Language features

Strongly prefer non-nullable types over null checks. In general, you only want to use a nullable type if the absence of a value is a normal, expected condition rather than a sign of a problem.

Use nullability instead of Optional. In general, Kotlin’s ?. and ?: operators give you everything Optional does but without the wrapper object or added syntax.

Prefer throwing exceptions over returning explicit error results that the caller has to explicitly check for.

Prefer immutable objects in your public methods. That is, a class with val fields is usually preferable to var. Use mutability when it’s truly the best solution, but it shouldn’t be your default choice.

## Git

Commit messages (and PR titles) should use either present-tense imperative or past tense.

## Code organization

### Package hierarchy

We generally lay out packages by functional area and then by component type. That is, the package for code related to reading and writing user data in the database will be `com.terraformation.backend.user.db` rather than `com.terraformation.backend.db.user`.

In general, you'll see the following subpackages under a functional-area parent package such as `com.terraformation.backend.device`:

- `api` for controllers and payload classes
- `db` for store classes (see below)
- `event` for application event classes
- `model` for model classes

There can be other subpackages as needed. Service classes (see below) live in the functional-area package.

### Files with multiple classes/functions

It is fine to put multiple top-level declarations in the same source file if they’re closely related.

Payload classes often live in the same file as the controller classes that use them, though if the payloads are especially complex, it sometimes improves readability to move some of them to separate files.

Generally, if a class has a significant amount of code, it’ll live in its own file.

## Database access

We use a schema-first, code-generation approach, as opposed to a code-first approach where the database gets created based on class structure.

We use the [jOOQ](https://jooq.org) library to generate code that provides a fluent, type-safe SQL query building API as well as some basic ORM features.

To make changes to the data model, add migration scripts. See the "Database migrations" section below for more details.

### Rows vs. models

jOOQ generates a few classes to represent table data. The one you’ll use most often has a Row suffix, e.g., UsersRow for the users table. These classes are referred to as “POJO” classes by jOOQ itself. (The Record classes will be discussed below.)

We use these classes a lot, both to interact with jOOQ DAOs (see below) and to pass data around internally. You should feel free to use them! However, they do have some downsides:

All the fields are nullable, even if the corresponding database columns aren’t. So you will end up having to account for nulls, often using !! or ?. constructs, even when you know the value can never actually be null. This can get annoying.

They are representations of individual tables. Sometimes a business object spans multiple tables, and the Row classes have no way to represent it.

There’s no way to customize them. You can define extension methods, but you can’t, e.g., add validation logic or omit fields you don’t care about.

The field names are always derived from the database column names, and idiomatic column names aren’t always idiomatic field names.

So we will often define “model” classes explicitly in the code. Sometimes these look just like the Row classes but with non-nullable fields. Sometimes they are higher-level classes that have lists of child objects. It depends on the context.

Generally, we’ll include a Model suffix on the names of model classes to clearly distinguish them from the Row classes. If a model has child objects that are never accessed except via the parent, the child classes don’t need the Model suffix.

Try to be consistent – for a given table, use models everywhere or use Rows everywhere, not a random mix of the two. (But it’s fine to consistently use models for one table and Rows for another table.)

#### New and Existing models

One tricky situation when you’re trying to be precise about nullability is that there are often values that are guaranteed to exist when you read something from the database, but not needed as input when you write something. IDs are the most common example: every database row has one, but you never know the ID until you insert the row.

Our pattern for this is to declare “new” and “existing” model classes, e.g., NewOrganizationModel and ExistingOrganizationModel, where the fields in question are either nullable or don't exist at all on the "new" classes.

We do this either of two ways: by declaring completely separate classes or by using generics and typealiases.

Separate classes are straightforward:

```kotlin
data class NewOrganizationModel(
    val name: String
)

data class ExistingOrganizationModel(
    val id: OrganizationId,
    val name: String
)
```

The disadvantage of this approach is that it’s awkward to write code that works with both existing and new objects. So another approach is to use one class:

```kotlin
data class OrganizationModel<ID : OrganizationId?>(
    val id: ID,
    val name: String
)

typealias NewOrganizationModel = OrganizationModel<Nothing?>
typealias ExistingOrganizationModel = OrganizationModel<OrganizationId>
```

In this approach, there is always an id field, but it must always be set to null if you’re using the NewOrganizationModel typealias, and can never be null if you’re using ExistingOrganizationModel. Functions that don’t care which variety of model they’re using can accept `OrganizationModel<*>` parameters.

### DAOs and stores

jOOQ generates a “DAO” class for each table (other than enum tables). The DAO class gives you a basic set of CRUD operations. It works with the Row classes.

We often use the DAO classes for inserting new rows and fetching single rows; they’re more succinct than constructing SQL statements.

However, we usually don’t call them directly: generally, we define a “store” class and call the DAO from the store.

Why we use store classes:

Permission checks. We want to throw an exception if the current user doesn’t have permission to read or modify a particular object in the database.

Populating mandatory fields. Many tables have fields like “created time” or “modified by user” and the store class can ensure that these are always set to the correct values rather than forcing every caller to remember to set them.

Models. The store classes can accept and return models rather than rows.

Sorting. The DAO classes have methods to fetch multiple rows, but they don’t guarantee a sort order.

More sophisticated operations. Sometimes we want to do things that aren’t trivial single-table CRUD operations.

Stores should not depend on other stores! See the next section for more. But it’s fine for a store to depend on DAOs and low-level helper objects.

### Rows vs. Records

TL;DR: Generally prefer the `Row` classes over the `Record` classes. The rest of this section talks about the differences between the two.

jOOQ generates two different classes to represent a row of data from a table: the Row class discussed above and a Record class.

The `Row` classes are dumb, lightweight data classes that act as simple containers for the data. They have no behavior; they’re just collections of mutable fields. Application code can create and copy them as needed, and they can be passed to the DAO classes for simple CRUD operations.

The `Record` classes are more heavyweight. They keep track of things like which specific fields have been modified, such that when you save them back to the database, jOOQ can generate a more precise UPDATE statement.

Generated `Record` classes are per-table, but they also implement the jOOQ `Record` interface. That means that anything you could do with the results of a `dslContext.select(...).fetch()` call, you can also do with a generated `Record` class. For example, you can look up values by column rather than using the generated properties:

```kotlin
xyzRecord.someColumnName == xyzRecord[XYZ.SOME_COLUMN_NAME]
```

In fact, if you look at the implementation, the generated properties are actually implemented as column lookups under the hood. This means they’re much slower than the simple field accesses on a Row class, though the performance difference isn’t usually a problem in our applications.

A `Record` is attached to a database context and knows how to persist itself. To update the database with changes to a `Row` object, you call `xyzDao.update(xyzRow)` but to update the database with changes to a `Record` object that you’ve gotten from a previous database query, you call `xyzRecord.store()`.

The `Record` classes are more flexible and powerful, and we do use them in places, but we generally prefer the `Row` classes for their lower memory footprint, faster field access, and the convenience of the DAO classes.

### Database migrations: where to put the “create table” commands

We use [Flyway](https://flywaydb.org/) to manage database migrations. The migration scripts live in [src/main/resources/db/migration](../src/main/resources/db/migration/) and are organized into subdirectories in groups of 50 to make them easier to navigate.

See [the migration README file](../src/main/resources/db/migration/README.md) for more details about how to write migrations.

## Services

In some cases, we also have “service” classes as a layer above the store classes. These are mostly used in cases where a single operation needs to span data in multiple stores, or needs to interact with stores and with non-store services (e.g., sending email).

We use this pattern rather than having stores talk directly to each other because it reduces the likelihood of circular dependencies and tight coupling where store A calls methods in store B and store B calls methods in store A.

Event listeners also typically live in service classes rather than store classes, even if they do nothing but call a method on a store class.

## Dependency injection

If you need to call methods in other classes and the other classes can be instantiated once and then reused, use dependency injection rather than explicitly instantiating them. This makes it easier to replace the dependencies with stubs when testing, and also makes the interaction between service classes more explicit in the code.

Whenever possible, use constructor injection, that is, declaring your dependencies as constructor arguments. You should almost never need to use the `@Autowired` or `@Inject` annotations on a field.

Prefer JSR-299 (CDI) annotations such as `@Named` and `@Inject` over Spring-specific annotations like `@Service` and `@Autowired`. But not all Spring annotations have JSR-299 equivalents, so use the Spring ones as needed.

## Testability and tests

Automated tests are important tools to allow the code to evolve safely, and they serve as documentation of the code's intended behavior. The project does not have a specific coverage target (since that often leads to low-quality, thoughtless tests) but in general, any nontrivial business logic should be well covered by tests, including failure cases.

Stub out dependencies when possible. The project uses the [MockK](https://mockk.io/) library which provides a convenient stubbing API. It also supports verifying method calls on mocks; try to only use verification when an interaction with another object is part of a function's documented behavior rather than an implementation detail. As a rule of thumb, verify write operations and callbacks but not read operations.

Use coverage analysis tools such as IntelliJ's built-in coverage analyzer to help find code paths your tests didn't exercise.

Assume libraries and frameworks work properly. Don't write tests to check whether, e.g., the `@Secured` annotation on a controller actually rejects unauthenticated users, or that jOOQ is generating valid SQL. The exception is if you've run into a library bug that requires a workaround; in that case it's appropriate to add a test case to verify that the bug is present, so that when the bug is fixed the test will fail and tell us we can remove the workaround.

Never perform database queries inline in the application code; add separate data-access classes. For simple CRUD operations, the autogenerated jOOQ DAO classes may suffice. This keeps business-logic tests fast because the data access can be stubbed out cleanly.

Keep data-access classes focused on data access. Don't be afraid to use SQL to do what it's good at, rather than treating the database as a dumb key/value store. For example, use a join rather than fetching a list of IDs and then separately fetching the records those IDs refer to. But avoid embedding actual business logic in SQL queries unless there's a clear benefit in performance or safety or clarity, because then you'll be forced to write database-backed tests for the logic and those tests are slower and more brittle. If you do write nontrivial SQL queries, cover them with tests.

## Payload classes

We define separate classes for API payloads rather than directly exposing our model classes to clients. This lets us evolve the API independently of the internal representation. In many cases the two are identical; that's okay.

The exception is enums; it's fine to expose those directly in payload classes rather than duplicating them.

Use data classes to represent API request and response payloads whenever possible, rather than using generic `Map` objects. This will allow the build process to generate more useful API documentation.

We use annotations to produce an OpenAPI (Swagger) schema for client-side code generation and to document the API. Make sure to include `@Schema` annotations to describe your payloads and their fields, especially any constraints that aren't obvious from the data types, e.g., if a payload has two fields only one of which is allowed to be non-null, that should be documented. But don't write docs that just restate what's already in the field's name.

Generally, these payload classes should live in the same file as the controller classes that implement the API endpoints. If a particular payload class is used by 3 or more controllers, though, it should be moved to a separate file (which may contain multiple such payload classes if they are related to each other).
