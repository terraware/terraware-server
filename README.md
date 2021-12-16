# Terraware API Server

This is the back end for the software suite that runs in Terraformation's modular seed banks.

It has two main areas of focus: the seed processing workflow and monitoring of the seed bank's physical infrastructure.

The initial version will be deployed to Raspberry Pi servers using Balena for device management. See the [Terraware Balena repo](https://github.com/terraware/balena/) for details about that and for links to some of the other services that interact with this code.

The server does not depend on the Balena or Raspberry Pi environments and should work on any hardware that supports the Java virtual machine.

## Quickstart (to try running it locally)

### Prerequisites

* Docker (used by the build process and by automated tests)
* Java version 17 or higher ([AdoptOpenJDK](https://adoptopenjdk.net/) is a convenient place to get it)
* PostgreSQL (version 13 or higher recommended)
* PostGIS (version 3.1 or higher recommended)

### Initial setup

* Create a local database: `createdb terraware`
* Follow the set up instructions in [KEYCLOAK.md](KEYCLOAK.md).

### Running the server

Mac/Linux: `./gradlew bootRun`

Windows: `gradlew.bat bootRun`

See the "Editing The Code" section below for details on how to run the server inside of IntelliJ.

The server will listen on port 8080.

Fetch the API schema to confirm the server is up and running. (This doesn't require authentication.)

    curl http://localhost:8080/v3/api-docs.yaml

### Viewing the API documentation

Start the server via the command line or in IntelliJ and then navigate to: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html).

### Running the tests

Mac/Linux: `./gradlew check`

Windows: `gradlew.bat check`

The `check` target will run the linter as well as the actual tests; to run just the tests themselves, use `test` instead.

## Using the front end

To use the web front end, you'll need access to a Keycloak instance, either an existing one or a local one. See [KEYCLOAK.md](KEYCLOAK.md) for setup instructions.

Clone the [seed bank app front end code base](https://github.com/terraware/seedbank-app).

Follow the front end repo's initial setup instructions to install dependencies and so forth.

Run `yarn start` from the directory where you cloned the front end code. It will start a local server that listens on HTTP port 3000.

Connect to http://localhost:3000/ to log in.

## Editing the code

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

## Using a profile-specific properties file for local development

If you need to change some configuration settings in your local development environment, but you don't want to fuss with environment variables, you can put the configuration in YAML files and tell the server to read them.

Create a file `src/main/resources/application-dev.yaml`. This file will not be included when the code is packaged into a jar, and will be ignored by git.

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

### Using multiple local profiles

If you have more than one set of local configuration settings and you want to easily switch between them, you can create more than one properties file. Just make sure the profile name starts with `dev` so it'll be ignored by git and excluded from the application jarfile.

For example, you could create `src/main/resources/application-dev-foo.yaml` and then add `dev-foo` to the list of active profiles.

You can have as many local profiles in the list as you like. If the same config setting is specified in more than one of them, the last one wins.

## Configuring email

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

## How to contribute

We welcome your contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for information about contributing to the project's development, including a discussion of coding conventions.
