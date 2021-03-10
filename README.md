# Seed Bank Server

This is the back end for the software suite that runs in Terraformation's modular seed banks.

It has two main areas of focus: the seed processing workflow and monitoring of the seed bank's physical infrastructure.

The initial version will be deployed to Raspberry Pi servers using Balena for device management. See the [Terraware Balena repo](https://github.com/terraware/balena/) for details about that and for links to some of the other services that interact with this code.

The server does not depend on the Balena or Raspberry Pi environments and should work on any hardware that supports the Java virtual machine.

## Quickstart (to try running it locally)

### Prerequisites

* Docker (used by the build process and by automated tests)
* Java version 15 or higher ([AdoptOpenJDK](https://adoptopenjdk.net/) is a convenient place to get it)
* PostgreSQL (version 12 or higher recommended)

### Initial setup

* Create a local database: `createdb seedbank`
* Pull the PostgreSQL Docker image so the build process can launch it: `docker pull postgres:12`

### Running the server

Mac/Linux: `./gradlew run`

Windows: `gradlew.bat run`

The server will listen on port 8080. As a demo, it will create an API client with an API key of `dummyKey`, which you can use to make API requests.

Fetch the API schema. (This doesn't require authentication.)

    curl http://localhost:8080/v3/api-docs.yaml

Fetch the details of a sample site.

    curl -H "Authorization: Basic $(echo -n user:dummyKey | base64)" http://localhost:8080/api/v1/site/1

You can browse the API interactively at [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html).

### Running the tests

Mac/Linux: `./gradlew check`

Windows: `gradlew.bat check`

The `check` target will run the linter as well as the actual tests; to run just the tests themselves, use `test` instead.

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
9. Keep expanding: `src`, `main`, `kotlin`, `com.terraformation.seedbank`.
10. Right-click on `Application`.
11. Select "Run 'Application'".

The code should build (if you've previously built it from the command line, it will reuse some of those build artifacts) and the server should launch, with its output in a pane at the bottom of the IntelliJ window.

Once you've launched the application once, it will appear in the drop-down menu of run configurations in the toolbar at the top of the IntelliJ window. You can select it there and click the Run (triangle) or Debug (beetle) button to the right of the drop-down menu. You can also launch it with keyboard shortcuts but that's beyond the scope of this quick intro.

## How to contribute

We welcome your contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for information about contributing to the project's development, including a discussion of coding conventions.
