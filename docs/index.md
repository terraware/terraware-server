# Terraware Server Documentation

## Project docs

Crucial docs for getting started:

* [SETUP.md](SETUP.md): how to set the server up for local development. Start here.
* [KEYCLOAK.md](KEYCLOAK.md): how to set up Keycloak locally or configure the server to use an existing Keycloak instance; you'll need to do this as part of the setup process.

If you're going to be contributing code:

* [CONVENTIONS.md](CONVENTIONS.md): some of the project's coding conventions.

Setup docs for optional parts of the system, only needed if you're working on code related to these features:

* [ATLASSIAN.md](ATLASSIAN.md): how to configure the server to file Jira tasks. Only needed if you're working on features related to customer support.
* [DROPBOX.md](DROPBOX.md): how to configure Dropbox integration for storing sensitive uploaded dcocuments.

## Database schema diagrams

The database has some subject-area-specific schemas as well as a default public one.

* [Default schema](schema/all/public/relationships.html) (`public`), or a subset of its tables:
  * [Customer-related tables](schema/customer/public/relationships.html)
  * [Device-related tables](schema/device/public/relationships.html)
  * [Species-related tables](schema/species/public/relationships.html)
* [Accelerator schema](schema/all/accelerator/relationships.html) (`accelerator`)
* [Nursery schema](schema/all/nursery/relationships.html) (`nursery`)
* [Seed bank schema](schema/all/seedbank/relationships.html) (`seedbank`)
* [Tracking schema](schema/all/tracking/relationships.html) (`tracking`)

## Code docs

[Dokka class documentation](dokka/index.html)

## Other docs

* [Dependency license report](license-report/index.html)
* [Git log since last release](unreleased.log)
* [Notifications](notifications.html)

