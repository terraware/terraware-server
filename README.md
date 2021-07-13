# Terraware API Server

This is the back end for the software suite that runs in Terraformation's modular seed banks.

It has two main areas of focus: the seed processing workflow and monitoring of the seed bank's physical infrastructure.

The initial version will be deployed to Raspberry Pi servers using Balena for device management. See the [Terraware Balena repo](https://github.com/terraware/balena/) for details about that and for links to some of the other services that interact with this code.

The server does not depend on the Balena or Raspberry Pi environments and should work on any hardware that supports the Java virtual machine.

## Quickstart (to try running it locally)

### Prerequisites

* Docker (used by the build process and by automated tests)
* Java version 15 or higher ([AdoptOpenJDK](https://adoptopenjdk.net/) is a convenient place to get it)
* PostgreSQL (version 12 or higher recommended)
* [Keycloak](https://keycloak.org/), either an existing server or a local one. See [KEYCLOAK.md](KEYCLOAK.md) for setup instructions.
* [OAuth2 Proxy](https://github.com/oauth2-proxy/oauth2-proxy). See [KEYCLOAK.md](KEYCLOAK.md) for setup instructions.

### Initial setup

* Create a local database: `createdb terraware`
* Pull the PostgreSQL Docker image so the build process can launch it: `docker pull postgres:12`
* Get credentials for administrative API access to your Keycloak instance. (Terraformation employees can find instructions for this in Confluence.)
* Launch OAuth2 Proxy as described in [KEYCLOAK.md](KEYCLOAK.md).

#### Keycloak environment variables

You'll need to run Keycloak to authenticate to the server, and the server needs to know how to communicate with Keycloak, which requires setting four environment variables.

If you're launching the server from the command line (including using Gradle) you can set these in your shell.

If you're launching the server from an IDE such as IntelliJ IDEA, you can set them in the IDE's configuration. In IntelliJ, environment variables are part of the "run configuration" for the server.

| Variable | Description
| --- | ---
| `TERRAWARE_KEYCLOAK_SERVER_URL` | Your Keycloak server's API address. If you are running Keycloak locally, this will be `http://localhost:8081/auth`. Otherwise, it will be the URL of your Keycloak server including the path prefix for the Keycloak API, which is usually `/auth`.
|`TERRAWARE_KEYCLOAK_REALM` | The name of the Keycloak realm that contains terraware-server user information. If you followed the instructions for a local Keycloak instance in [KEYCLOAK.md](KEYCLOAK.md), this will be `terraware`.
|`TERRAWARE_KEYCLOAK_CLIENT_ID` | The client ID terraware-server will use to make Keycloak API requests. If you followed the instructions for a local Keycloak instance in [KEYCLOAK.md](KEYCLOAK.md), this will be `dev-terraware-server`.
|`TERRAWARE_KEYCLOAK_CLIENT_SECRET` | The secret associated with the client ID.

### Running the server

Set the Keycloak configuration environment variables as described above.

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

## Using the front end

To use the web front end, you'll need to run OAuth2 Proxy and you'll need access to a Keycloak instance, either an existing one or a local one. See [KEYCLOAK.md](KEYCLOAK.md) for setup instructions.

Clone the [seed bank app front end code base](https://github.com/terraware/seedbank-app).

Follow the front end repo's initial setup instructions to install dependencies and so forth.

Run `yarn start` from the directory where you cloned the front end code. It will start a local server that listens on HTTP port 3000. You won't use that directly, but OAuth2 Proxy will forward requests to it.

Connect to http://localhost:4000/ to log in.

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

Depending on how you set the Keycloak-related environment variables in the initial setup steps above, the server might complain that it can't find some required `terraware.keycloak` properties. In that case, you can set the environment variables in the IntelliJ run configuration:

1. In the drop-down menu of run configurations in the toolbar at the top of the IntelliJ window, choose "Edit Configurations..."
2. Select "Application" under "Spring Boot" if it's not already selected.
3. Click the little icon at the end of the "Environment Variables" text field to pop up a dialog that shows the current set of environment variables.
4. Add all the `TERRAWARE_KEYCLOAK` environment variables listed in the "Keycloak environment variables" section above.
5. Click OK on the environment variable dialog and the run configurations dialog.

Now IntelliJ will pass those environment variables to the server each time you run it.

## How to contribute

We welcome your contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for information about contributing to the project's development, including a discussion of coding conventions.
