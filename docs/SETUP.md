# Setting up the project for local development

## Prerequisites

* Docker (used by the build process and by automated tests)
* Java version 20 or higher ([AdoptOpenJDK](https://adoptopenjdk.net/) is a convenient place to get it)
* PostgreSQL (version 13 or higher recommended)
* PostGIS (version 3.1 or higher recommended)
* PGVector (version 0.8.0 or higher)

## Initial setup

* Create a local database: `createdb terraware`
* Follow the set up instructions in [KEYCLOAK.md](KEYCLOAK.md).

## Running the server directly

Mac/Linux: `./gradlew bootRun`

Windows: `gradlew.bat bootRun`

See the "Editing The Code" section below for details on how to run the server inside of IntelliJ.

The server will listen on port 8080.

Fetch the API schema to confirm the server is up and running. (This doesn't require authentication.)

        curl http://localhost:8080/v3/api-docs.yaml

## Running the server via Docker

If you wish to run the Terraware server and/or Postgres database via docker, check out the `./docker/Makefile`.

### Run both the server and database

This is useful if you just want the server running locally for frontend development
1. Copy `./docker/.env.sample` to `./docker/.env` and fill in the necessary Keycloak environment variables
2. Run `make -C docker run`

### Run just the database

This is useful if you are doing backend development and running the server in IntelliJ, and you don't want to install Postgres natively
1. Run `make -C docker run-postgres`

### Run just the server

This is useful if you are doing frontend development and only want to run the server, perhaps you are running Postgres natively or in Docker elsewhere
1. Run `make -C docker run-server`

## Viewing the API documentation

Start the server via the command line or in IntelliJ and then navigate to: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html).

## Running the tests

Mac/Linux: `./gradlew check`

Windows: `gradlew.bat check`

The `check` target will run the linter as well as the actual tests; to run just the tests themselves, use `test` instead.

# Using the front end

To use the web front end, you'll need access to a Keycloak instance, either an existing one or a local one. See [KEYCLOAK.md](KEYCLOAK.md) for setup instructions.

Clone the [Terraware web app front end code base](https://github.com/terraware/terraware-web).

Follow the front end repo's initial setup instructions to install dependencies and so forth.

Run `yarn start` from the directory where you cloned the front end code. It will start a local server that listens on HTTP port 3000.

Connect to http://localhost:3000/ to log in.

# Editing the code

First, familiarize yourself with the project's [coding conventions](CONVENTIONS.md).

By far the best Kotlin development environment is [IntelliJ IDEA](https://www.jetbrains.com/idea/). You can use other editors or IDEs (the build, as you've seen from the quickstart, doesn't depend on an IDE) but IntelliJ is what you want.

1. Run IntelliJ.
2. In the welcome dialog, click Open.
3. Navigate to this repo using the file selector dialog.
4. Select `build.gradle.kts`.
5. Click Open.
6. In the popup, click "Open as Project".
7. Dismiss the hints-and-tips popup (or read some hints and tips, if you like).
8. On the left of the window is a folder view. Click the triangle to the left of the project folder to expand it.
9. Keep expanding: `src`, `main`, `kotlin`, `com.terraformation.backend`.
10. Right-click on `Application`.
11. Select "Run 'Application'".

The code should build (if you've previously built it from the command line, it will reuse some of those build artifacts) and the server should launch, with its output in a pane at the bottom of the IntelliJ window.

Once you've launched the application once, it will appear in the drop-down menu of run configurations in the toolbar at the top of the IntelliJ window. You can select it there and click the Run (triangle) or Debug (beetle) button to the right of the drop-down menu. You can also launch it with keyboard shortcuts but that's beyond the scope of this quick intro.

Depending on how you set the Keycloak-related environment variables in the initial setup steps above, the server might complain that it can't find some required `keycloak` properties. If that's the case revisit the "Terraware-server configuration" section in [KEYCLOAK.md](KEYCLOAK.md).

# Using a profile-specific properties file for local development

If you need to change some configuration settings in your local development environment, but you don't want to fuss with environment variables, you can put the configuration in YAML files and tell the server to read them.

Copy the file `src/main/resources/application-dev.yaml.sample` to `src/main/resources/application-dev.yaml`. This file will not be included when the code is packaged into a jar, and will be ignored by git.
It has the necessary Keycloak and Postgres configuration variables stubbed out for you.

See the other `application.yaml` files in [`src/main/resources`](src/main/resources) for some examples, but it'll look something like this:

```yaml
terraware:
  daily-tasks:
    enabled: false
```

Then tell the server to use this configuration in addition to the default one.

If you're running it from the command line, set the `SPRING_PROFILES_ACTIVE` environment variable to `default,dev`.

If you're running it from IntelliJ, you can set the list of profiles in the run configuration:

1. In the drop-down menu of run configurations in the toolbar at the top of the IntelliJ window, choose "Edit Configurations..."
2. Select "Application" under "Spring Boot" in the list of configurations if it's not already selected.
3. Scroll to the "Spring Boot" section of the main part of the dialog. Expand it if needed.
4. Set the "Active profiles:" field to `default,dev`.
5. Click OK.

If you run into this error when trying to run:

```
Exception in thread "main" java.lang.RuntimeException: Please add -Djava.locale.providers=SPI,CLDR to the JVM's command-line arguments.
```

then perform the following:

1. In the drop-down menu of run configurations in the toolbar at the top of the IntelliJ window, choose "Edit Configurations..."
2. Select "Application"
3. Click "Modify options" -> "Add VM options"
4. In the VM options field, add this: `-Djava.locale.providers=SPI,CLDR`
5. Click OK.

## Using multiple local profiles

If you have more than one set of local configuration settings and you want to easily switch between them, you can create more than one properties file. Just make sure the profile name starts with `dev` so it'll be ignored by git and excluded from the application jarfile.

For example, you could create `src/main/resources/application-dev-foo.yaml` and then add `dev-foo` to the list of active profiles.

You can have as many local profiles in the list as you like. If the same config setting is specified in more than one of them, the last one wins.

# Configuring email

By default, the server will output email messages to its log rather than trying to send them. To enable email, you need to tell it which mail server to connect to and how to authenticate. It supports both SMTP and the AWS Simple Email Service (SES) version 2.

You can also configure it to send email to you rather than to the intended recipients; this is useful for testing locally when you want to make sure you don't accidentally spam anyone else.

To use SMTP:

```yaml
terraware:
  email:
    enabled: true
    override-address: your-name@your-domain.com
    sender-address: your-name@your-domain.com

spring:
  mail:
    host: smtp-host.yourdomain.com
    port: 587   # This should be the SMTP host's STARTTLS port; 587 is a common one
    username: your-smtp-username
    password: your-smtp-password
```

If Gmail is used as an email provider for the above, then, as of June 2022, your main login password will not work. Instead, you will need to generate an "app password" from your Google account page and enter it in `spring.mail.password` in the yaml above (see https://support.google.com/accounts/answer/185833?hl=en).

To use SES, you need to supply AWS credentials, e.g., by setting the `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` environment variables, by configuring them in `~/.aws/config`, or by using an instance profile. Then configure the server:

```yaml
terraware:
  email:
    enabled: true
    override-address: your-name@your-domain.com
    sender-address: your-name@your-domain.com
    use-ses: true
```

To disable recipient address overriding, set its config option to false (in addition to the other configuration shown above):

```yaml
terraware:
  email:
    always-send-to-override-address: false
```

To include a prefix in the subject lines of all email messages, useful to identify that they're from your dev environment (note that this is already included in the `default` profile, so if you're using that, you don't need to do this explicitly):

```yaml
terraware:
  email:
    subject-prefix: "[DEV]"
```

# Super-Admins

Some operations are not available to regular users of the system; they must be requested by privileged administrative users. Internally, these are referred to as "Super-Admins".

Super-Admin privileges can be granted using the "Manage Global Roles" link from the admin UI if you're already logged in as a Super-Admin.

Creating the initial Super-Admin user currently requires connecting to the server's database and modifying the user global roles table directly. The exact command you'll need to run to connect to the database will vary a bit depending on where it's running (local host, Docker container, managed cloud database service, etc.). For a database running on your local host, `psql terraware` will usually work.

Once you're connected to the database, run the following query to change the user with a particular email address to a super-admin:

```sql
INSERT INTO user_global_roles (user_id, global_role_id)
SELECT id, 1
FROM users
WHERE email = 'your_email@your.domain';
```

# Importing GBIF backbone data

The server uses a public species database from the [Global Biodiversity Information Facility (GBIF)](https://gbif.org/). If you're working with species-related parts of the code, you'll need to import the data into your local database.

This must be done by a super-admin; see above for more information on that.

1. Download `backbone.zip` from [the GBIF backbone dataset page](https://www.gbif.org/dataset/d7dddbf4-2cf0-4f39-9b2a-bb099caae36c) (it's the "Source archive" item in the download menu at the top of the page).
2. Log into the admin interface on your terraware-server instance. For a locally-running server instance, it'll typically be [http://localhost:8080/admin/](http://localhost:8080/admin/).
3. If you are a super-admin, you'll see an "Import GBIF species data" section on the admin home page. If you don't see that section, make sure you've set your user type properly as described in the "Super-Admins" section above.
4. Select the zipfile you downloaded from the GBIF site, and hit the "Upload Zipfile" button.
5. Watch the server logs to monitor the progress of the import. It will take several minutes to finish.

Once it's done, the species lookup endpoints under `/api/v1/species/lookup` should start returning real results.

# Translating to other languages

Human-readable strings can be found in `.properties` files in `src/main/resources/i18n`.

Use [llm-autotranslate](https://www.npmjs.com/package/llm-autotranslate) to generate translations into the other supported languages.

Get an OpenAI API key from your OpenAI account administrator or create one using [the OpenAI platform console](https://platform.openai.com/api-keys). It should have write permission on the Responses API.

Put the key in your `.env` file under the name `OPENAI_API_KEY`. You can also set it as an environment variable if you prefer.

To translate newly-added or edited strings, you have two choices. You can do it as a one-off operation:

```shell
yarn translate
```

Or you can run autotranslate in "watch mode," which will watch for changes to the English properties files and automatically request translations and update the other files as needed. You can leave it running in the background.

```shell
yarn translate:start &
```

