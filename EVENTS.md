# Events

## Overview

We use Spring's `ApplicationEventPublisher` to trigger actions when various operations are performed. Spring automatically calls any method that is annotated with `@EventListener` and whose parameter type matches the type of the published event. Event listeners are called synchronously and run in the same database transaction, if any, as the call to `ApplicationEventPublisher.publishEvent`.

There are three broad categories of events.

* Rate-limited events. These implement `com.terraformation.backend.ratelimit.RateLimitedEvent`. They are published by a separate event publisher that enforces rate limits; it uses the Spring event publisher under the hood. We typically use these for events that trigger email or in-app notifications, to avoid spamming users with lots of notifications.
* Persistent events. These implement `com.terraformation.backend.eventlog.PersistentEvent`. They are recorded in a database table which will be described in more detail below. These events are used to maintain an audit trail or change history for various kinds of entities.
* Transient events. These are the default kind. They're not stored anywhere and are mostly used to reduce coupling in the code.

Most of this document will talk about persistent events.

## Persistent events overview

Persistent events act as a long-term history of what happened on the system. They are stored in a database table and there is an API to query them. There are mechanisms to evolve them over time and to group related events together.

A persistent event will usually describe a single operation on a single entity. Operations that affect multiple entities will generally be represented using multiple events.

Internally, persistent events are implemented as Kotlin data classes. The classes have to conform to some naming rules and implement some interfaces; those details are described later.

In the query API, we refer to the affected entity as the **subject** of the event and the operation's effect(s) on the entity as the **actions** of the event. The query API defines a small, fixed set of actions (create, delete, update-fields) and all events get mapped to a subject and one of those actions when they're sent to a client.

The actual event classes can be more granular than that; it is valid, and expected, for multiple event classes to end up mapping to the same subject and the same kind of action. The mapping is so that clients thus don't have to be aware of the full list of event classes.

### Subjects and Actions

The event log query API payloads include a standard set of properties that allow frontend code to show a human-readable list of line items without needing to know about all the possible subject types.

To see how this works, consider an organization with two projects (A and B), the first of which has a description that was edited. If we're looking at the history of project A, the line items might look like this in the UI:

* 2025-01-01 Project created
* 2025-01-04 Field "description" changed from "Who knows" to "My project"

And if we're looking at the history of the organization, we might see:

* 2025-01-01 Project A created
* 2025-01-02 Project B created
* 2025-01-04 Project A field "description" changed from "Who knows" to "My project"

The key difference here is that in the second example, the line items distinguish which project they're referring to.

These line items are constructed using subjects and actions. Each subject payload includes "short text" and "full text" properties. The short text is used to render line items that don't need to distinguish one subject from another ("Project" in the example) and the full text includes identifying information ("Project A" and "Project B").

The actions, meanwhile, are of types `Created`, `Created`, and `FieldUpdated`, with the `FieldUpdated` action including the field name and before/after values.

The UI code can assemble usable line items using those text values even if it doesn't know a thing about the subjects. If it _does_ know about the subjects, it can decorate the line items with links or other markup.

## Persistent event class requirements and conventions

When you're adding a new persistent event class, there are some mandatory rules and some suggested patterns to follow. This may look like an excessive number of rules! But following them should keep the code easier to maintain as the number of events grows over time.

In the rest of this document, we use RFC-style upper-case "MUST" and "SHOULD" verbs to denote mandatory rules and suggested patterns, respectively.

### Naming and packaging

Class names MUST have a suffix of an upper-case V followed by a version number, like `V1`. Versioning is discussed in more detail in its own section below.

Class names SHOULD include the word `Event` immediately before the version suffix, like `ExampleEventV1`.

Classes SHOULD be in an `event` subpackage under the package for the area of the application they're related to. For example, events related to seed banking would live in the `com.terraformation.backend.seedbank.event` package.

Classes MUST include typealiases without the version suffix that point to the latest version. That is, if `ExampleEventV1` and `ExampleEventV2` exist, there must be a typealias `ExampleEvent` that points to `ExampleEventV2`. The typealiases MUST exist even if there is only one event version.

Nested classes of event classes MUST include typealiases too. For example, if `TreeEditedEventV1` defines a nested `Values` class, there must be a typealias `TreeEditedEventValues` pointing to it.

Application code MUST refer to event classes and their nested classes using these typealiases. That way it's impossible for us to forget to update all the usages of the old event version when we introduce a new one.

Typealiases SHOULD appear immediately following the class declarations.

All the event classes for a particular subject MUST be in the same package.

All the versions of an event class MUST be in the same source file.

All the event classes for a particular subject MAY be in the same source file.

### Interfaces

Classes that represent the creation of an entity MUST implement the `EntityCreatedPersistentEvent` interface.

Classes that represent the deletion of an entity MUST implement the `EntityDeletedPersistentEvent` interface.

Classes that represent the modification of field values of an existing entity SHOULD implement the `FieldsUpdatedPersistentEvent` interface, but if that's not feasible, MUST implement the `EntityUpdatedPersistentEvent` interface.

Classes that don't represent the creation, deletion, or modification of fields of an entity MUST implement the `PersistentEvent` interface.

Classes for prior versions of events MUST implement the `UpgradableEvent` interface.

All the event classes for a given subject MUST implement a sealed interface that defines the ID properties for the subject. This interface will typically be named for the subject with a `PersistentEvent` suffix, e.g., `PlantingSitePersistentEvent`. This sealed interface is used by the query API and is discussed later.

### Properties

Classes MUST be serializable and deserializable using Jackson.

Classes MUST use a consistent set of property names for entity IDs. The names MUST be camelCase versions of the ID wrapper class names, e.g., `monitoringPlotId` for the ID of type `MonitoringPlotId`.

Classes MUST include ID properties for the subject and any entities that come above the subject in our entity hierarchy, up to and including the organization ID. For example, an event for an observation photo would have the photo's file ID, the monitoring plot ID, the observation ID, the planting site ID, and the organization ID. This is to support use cases like "get all the events related to observation 123."

Classes SHOULD put information in top-level attributes instead of in nested objects, unless there's more than one instance of the same nested object such as a `List` or `Set`, or such as the `changedFrom` and `changedTo` values of an update event. Avoid embedding model objects in events; duplicate the model's fields in the event classes instead. This will allow the model to evolve without breaking backward compatibility with existing events in the database.

Classes MUST NOT include any personally identifiable information (PII). User IDs are fine to include.

All the non-nullable properties of persistent event classes MUST be listed in `src/test/resources/eventlog/eventProperties.txt`. It is used by a unit test to detect backward-incompatible changes to event classes.

### Representation of updates to existing entities

An update to a parent entity that's caused by an operation on a child entity MUST use the child entity as its subject. For example, if a nursery batch's quantity is changed due to a withdrawal, the event would be `WithdrawalCreatedEventV1` rather than `BatchQuantityUpdatedEventV1`.

Each class SHOULD represent a specific operation, even if the end result is just that an entity property gets updated. For example, if a monitoring plot is replaced in an observation, prefer an event like `MonitoringPlotReplacedEventV1` rather than a generic `ObservationUpdatedEventV1`. A rule of thumb is that the event class name should indicate the "why" and its contents should indicate the "what" of a change.

Classes SHOULD group multiple property changes together if they were changed as part of the same operation. This will be common for edit operations where the user can edit multiple fields at the same time.

Classes that represent the user editing the properties of existing entities SHOULD include both the old and new values of the properties. This SHOULD be done using two event properties `changedFrom` and `changedTo`, each of which is an instance of a nested data class called `Values`.

Nested `Values` classes, when used, MUST use nullable types for all their properties, and values of properties that didn't change MUST be set to null in both the `changedFrom` and `changedTo` instances.

## Versioning

Our data model evolves over time, and the event payloads evolve along with it. If we’re querying the event log to retrieve history, we’ll have to deal with the fact that old versions of events contain different data than new versions.

### When to create new versions

Event versions are mostly to ensure we can deserialize the JSON in the database. So at a high level, we’ll bump the version number when there’s any change that would cause deserialization of any possible existing event to fail.

Practically speaking, this means we *must* bump the version number whenever we add a new non-nullable field to the event or to any class that’s referenced, even if indirectly, by any field in the event, if that field doesn’t have a default value.

Renaming a nullable field is also grounds for a version bump: deserialization of an older instance of the event wouldn’t necessarily fail outright, but would lose information.

### Versioning implementation

Each event version is a separate data class with a version number suffix, e.g., `UserChoseFavoriteColorEventV1`. A typealias, e.g., `UserChoseFavoriteColorEvent`, points to the latest version so calling code doesn’t need to change when new event versions are introduced.

Previous versions of each event implement the `UpgradableEvent` interface. It defines a `toNextVersion` method:

```kotlin
fun toNextVersion(eventUpgradeUtils: EventUpgradeUtils): PersistentEvent
```

`EventUpgradeUtils` is a Spring bean where we can add code to pull data from the database if a version upgrade needs it.

## Subjects

Generally, there will be a separate subject type for each kind of entity in the application.

To introduce a new subject, you'll need to add code in a few places.

First, a prerequisite: make sure you've defined a sealed interface that all events related to the subject implement, e.g., `ExamplePersistentEvent`.

Add an entry for the subject to the `EventSubjectName` enum, passing its constructor a reference to your sealed interface, like

```kotlin
Example(ExamplePersistentEvent::class)
```

Add a payload class for the subject. The payload class MUST implement the `EventSubjectPayload` sealed interface, and thus MUST live in the same package as that interface.

Add a case for the subject's sealed interface to `EventLogPayloadTransformer.getSubject`. This will typically call a `forEvent` method on the subject payload class's companion object; see below for more details about that.

### Subject payload classes

The subject payload class MUST be annotated with the Jackson `@JsonTypeName` annotation, and the value MUST be the same as the subject's entry in the `EventSubjectName` enum.

`EventSubjectPayload` defines three properties, two of which are required.

* `deleted` is `true` if the entity is deleted or `null` if not. If the entity can't be deleted, the payload class can omit this property; the interface includes a default implementation that returns `null`.
* `fullText` is a localized human-readable description of the subject (more on localization below). It should describe the subject from the context of the parent entity and should include enough information to distinguish the subject from others of the same type under the same parent. For example, the full text for a project might be "Project XYZ" since an organization can have multiple projects.
* `shortText` is a localized human-readable short name for the subject. It is intended to be shown when viewing the event log for that subject, so it doesn't need to include distinguishing details. For example, the short text for a project would just be "Project".

The subject payload class SHOULD contain all the IDs from the sealed interface except the organization ID (the query API only queries one organization at a time, so it'd be redundant). UI code can use those IDs to add links or inline images to the line items.

### Subject payload creation

Subject payload classes SHOULD include a companion object with a `forEvent` method that returns a new instance of the subject payload class. This is called from `EventLogPayloadTransformer.getSubject`.

Most implementations of that method take two arguments: the event and a "context" object which is an instance of `EventLogPayloadContext`.

The context has the full list of events that were pulled from the database as part of the event log query. It's used to construct subjects for events where the event doesn't include all the information the subject needs.

Some typical uses of the context:

* Looking to see if there's a "deleted" event for the subject's entity, so the subject's `deleted` property can be set correctly.
* Finding the entity's name from the "created" event or the most recent "name updated" event, so the subject's `fullText` and/or `shortText` can include it.
* Finding the entity's type from the "created" event so the `fullText` and `shortText` can use more specific wording to describe it.

## Action payloads

If an event implements `EntityCreatedPersistentEvent`, `EntityDeletedPersistentEvent`, or `FieldsUpdatedPersistentEvent`, you don't need to add any code to generate action payloads for it.

Otherwise, add a case to `EventLogPayloadTransformer.getActions` for your new event.

## How events are stored in the database

You shouldn't need to interact with the event log at the SQL level if you're just adding new event types unless you need to backfill historical events for existing entities.

Persistent events are serialized to JSON and stored in the `event_log` table, which looks like this:

```sql
CREATE TABLE event_log (
    id BIGINT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    created_by BIGINT NOT NULL REFERENCES users (id),
    created_time TIMESTAMP WITH TIME ZONE NOT NULL,
    event_class TEXT NOT NULL,
    payload JSONB NOT NULL,
    original_event_class TEXT,
    original_payload JSONB
);
```

The ID and created by/time columns should be self-explanatory, though it’s worth noting that the created-by value will sometimes be the system user even if the event was published as the result of a user action.

`event_class` is the fully-qualified class name of the event, including its version suffix. We’ll use that for two things: deserializing the event and querying the log for specific event types.

`payload` is the event data in JSON form.

`original_event_class` and `original_payload` are null initially. The first time an event is upgraded to a newer version, the original version's class and payload are copied to these columns. The `event_class` and `payload` columns are updated with the new version after each upgrade.

There are no columns for things like organization or project IDs. They’re already included in the events as needed, and we don’t want to store them twice. We use Postgres JSON operators to pull them out of the payloads, and we have indexes on the JSON expressions to make querying on those fields efficient.

Version number suffixes are replaced with a wildcard when querying the event log, such that older event versions will be returned too. For example, a search for instances of `FooEventV6` will turn into a SQL condition like `WHERE event_class LIKE 'FooEventV%'`.

Note that the event log isn't the only way we keep track of history. There are other history mechanisms that predate it.  The event log can eventually replace some of them, but that will happen gradually over time.

## Indexing

Since we’ll be searching for values that are embedded in JSON objects, it's critical to make sure we have the right indexes in place to support our searches.

As a rule of thumb, we want an index for every kind of ID that can appear in an event, and we want to make sure the events use consistent field names for searchable IDs so the indexes will cover them.

We also include event classes in all those indexes to support searching for events by subject type: “Give me all events of types X, Y, and Z for organization N.” A composite index makes that kind of query efficient to execute since it can do all its filtering using the index without having to read from the actual table. For example:

```sql
CREATE INDEX event_log_organization_id_idx
    ON event_log (CAST(payload->>'organizationId' AS BIGINT), event_class)
    WHERE payload->>'organizationId' IS NOT NULL;
```

## Historical events and backfilling

When we're retrofitting existing code to start using persistent events, we'll usually want to backfill the history of existing entities so the event log is usable for both old and new data.

In these cases, we insert “historical events,” generally using a database migration. The JSON in the database for these events will have a special `_historical` top-level boolean field to allow us to distinguish them from application-written events. The field doesn’t have to be present in the event classes; it’s mostly for diagnostic purposes.

Where possible, historical events are attributed to users using the `created_by` values from their entities.

They are also backdated using the `created_time` values from their entities so that they appear in the correct positions when we sort the event log chronologically (which the query API does).

PostgreSQL has a `json_object` function that makes it relatively painless to assemble event payloads with values of different data types in a migration. For example, if OrganizationCreatedEventV1 has organizationId and name fields, the migration might look like:

```sql
INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    created_by,
    created_time,
    'com.terraformation.backend.customer.event.OrganizationCreatedEventV1',
    json_object(
        '_historical' VALUE true,
        'name' VALUE name,
        'organizationId' VALUE id
        ABSENT ON NULL
    )::JSONB
FROM organizations;
```

Inserting historical events isn’t required (or even possible) for all newly-introduced event types; we’ll need to decide on a case-by-case basis whether it makes sense to do.
