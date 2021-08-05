# Keycloak / OAuth2 Proxy quickstart guide

Terraware-server currently depends on [Keycloak](https://keycloak.org/) to manage user registration and login, and on [OAuth2 Proxy](https://oauth2-proxy.github.io/oauth2-proxy/) to manage authentication of incoming requests.

It can use an existing Keycloak server or a locally-hosted one. Either way, some initial setup is required.

## Setting Up Keycloak

You probably don't need to set up Keycloak if you work at Terraformation. Please check with a fellow developer. It is likely that someone else has already configured a Keycloak instance for use with terraware-server development environments. If that's the case, skip the "Running Keycloak locally" and "Configuring Keycloak" sections. You may proceed to the "Keycloak Environment Variables" section.

If you don't have access to a Keycloak instance then proceed to "Running Keycloak locally".
### Running Keycloak locally

The easiest way to bring up a local Keycloak server in a development environment is to use Keycloak's official Docker image. By default, the Keycloak image will use a local database inside the container, so there's no need to set up a separate database server. Obviously that's not what you'd want for production use, but for a local dev environment it's usually sufficient.

This repo has a Docker Compose file that launches a local Keycloak server. The easiest way to get started is to use it:

```shell
docker compose -f docker/keycloak-docker-compose.yml up -d
```

Go to http://localhost:8081/ and click "Administration Console".

Log in with a username of `admin` and a password of `admin` (these are set in environment variables in the Docker Compose file).

Follow the instructions in the next section to configure Keycloak for terraware-server.

###Configuring Keycloak

These steps apply to a local instance of Keycloak, and also to an existing one that isn't yet configured for use with terraware-server.

1. Log into the Keycloak administration console.
2. Add a new realm for Terraware users.
   1. Mouse over "Master" in the left navbar and click "Add Realm."
   2. Set the name to `terraware`.
   3. Click "Create."
3. Create a client for OAuth2 Proxy.
   1. Click "Clients" in the left navbar.
   2. Click "Create" on the header bar of the list of clients.
   3. Set the client ID to `localhost-oauth2-proxy` and the root URL to `http://localhost:4000/`.
   4. Click "Save." This will cause a bunch of additional fields to appear in the UI.
   5. Set the access type to "confidential".
   6. Click "Save."
   7. Click the "Credentials" tab.
   8. Copy the value of the "client secret" field; you'll be using it later.
4. Create a client for terraware-server to use for its Keycloak admin API requests.
   1. Click "Clients" in the left navbar.
   2. Click "Create" on the header bar of the list of clients.
   3. Set the client ID to `dev-terraware-server`.
   4. Click "Save." This will cause a bunch of additional fields to appear in the UI.
   5. Set the access type to "confidential", "Standard Flow Enabled" to "Off," and "Service Accounts Enabled" to "On."
   6. Click "Save." This will cause some new tabs to appear.
   7. Click the "Service Account Roles" tab.
   8. Click "Client Roles" and choose "realm-management" from the drop-down.
   9. Select each of the following roles and click the "Add selected" button:
      - manage-users
      - view-realm
      - view-users
   10. Click the "Credentials" tab.
   11. Copy the value of the "client secret" field; you'll be using it later.


### Keycloak environment variables

You'll need to run Keycloak to authenticate to the server, and the server needs to know how to communicate with Keycloak, which requires setting four environment variables. If you work at Terraformation then ask a fellow developer for the values of these environment variables (likely in the Terraformation 1Password Eng Infra Vault).

| Variable | Description
| --- | ---
|`TERRAWARE_KEYCLOAK_SERVER_URL` | Your Keycloak server's API address. If you are running Keycloak locally, this will be `http://localhost:8081/auth`. Otherwise, it will be the URL of your Keycloak server including the path prefix for the Keycloak API, which is usually `/auth`.
|`TERRAWARE_KEYCLOAK_REALM` | The name of the Keycloak realm that contains terraware-server user information. If you followed the instructions to create a local Keycloak instance then this will be `terraware`.
|`TERRAWARE_KEYCLOAK_CLIENT_ID` | The client ID terraware-server will use to make Keycloak API requests. If you followed the instructions to create a local Keycloak instance, then this will be `dev-terraware-server`.
|`TERRAWARE_KEYCLOAK_CLIENT_SECRET` | The secret associated with the client ID.

If you're launching the server from the command line (including using Gradle) you can set these in your shell.

If you're launching the server from an IDE such as IntelliJ IDEA, you can set them in the IDE's configuration. In IntelliJ, environment variables are part of the "run configuration" for the server. Follow the instructions below to make IntelliJ pass the environment variables to the server each time you run it.

1. In the drop-down menu of run configurations in the toolbar at the top of the IntelliJ window, choose "Edit Configurations..."
2. Select "Application" under "Spring Boot" if it's not already selected.
3. Click the little icon at the end of the "Environment Variables" text field to pop up a dialog that shows the current set of environment variables.
4. Add all the `TERRAWARE_KEYCLOAK` environment variables listed in the "Keycloak environment variables" section above.
5. Click OK on the environment variable dialog and the run configurations dialog.


## Setting Up OAuth2 Proxy

### Installation
You'll want to install the proxy locally. There is a Docker image, and you can make it work if your local host is running Linux. However, it's easier to run the proxy on the host OS because of limitations of the networking features in Docker for Mac and Docker for Windows.

On OS X with Homebrew, you can install it by running `brew install oauth2_proxy`.

See [the OAuth2 Proxy install docs](https://oauth2-proxy.github.io/oauth2-proxy/docs/) for other options.


### Configuration
Copy the file [`oauth2-proxy.cfg.sample`](oauth2-proxy.cfg.sample) to `oauth2-proxy.cfg` and edit the following config settings. If you work at Terraformation then ask a fellow developer where to find the setting values (they will likely be in the Terraformation 1Password Eng Infra Vault).

| Setting | Description |
| --- | ---
| `client_secret` | The client secret for the OAuth2 Proxy client, as shown in the Keycloak admin UI.
| `cookie_secret` |  A cookie secret for OAuth2 Proxy to use when it generates session cookies. The cookie secret should be 32 characters long.
| `oidc_issuer_url` | The address of your realm on your Keycloak server. Typically this will look like `https://your.keycloak.server/auth/realms/terraware`. You can leave this alone if you're running Keycloak locally with a realm name of `terraware`.
| `client_id` | The ID of the client that will be used to authenticate users to Keycloak; you can leave this alone if you're using the default one.

Wondering how to generate the `cookie secret`? This will work:
```shell
base64 < /dev/random | head -c32
```
Then run the proxy:

```shell
oauth2-proxy --config oauth2-proxy.cfg
```

The config file is set up for a typical terraware-server development environment, where the front end (as launched with `yarn start` from the front end repo) listens on HTTP port 3000 and the back end (terraware-server) listens on HTTP port 8080. The proxy itself is configured to listen on port 4000.

Connect to http://localhost:4000/ and you should see a login form.

Proceed to the "Keycloak environment variables" section in [README.md](README.md) to tell terraware-server how to connect to Keycloak.
