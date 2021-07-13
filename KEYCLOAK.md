# Keycloak / OAuth2 Proxy quickstart guide

Terraware-server currently depends on [Keycloak](https://keycloak.org/) to manage user registration and login, and on [OAuth2 Proxy](https://oauth2-proxy.github.io/oauth2-proxy/) to manage authentication of incoming requests.

It can use an existing Keycloak server or a locally-hosted one. Either way, some initial setup is required.

## Using an existing Keycloak instance that's already set up for terraware-server

If someone else has already configured a Keycloak instance for use with terraware-server development environments, you just need a few pieces of information to configure OAuth2 Proxy.

- The client ID and client secret of the Keycloak client that OAuth2 Proxy should use for authenticating users to local development environments. The client ID will often be `localhost-oauth2-proxy`.
- The name of the Keycloak realm where Terraware users are stored. This will often be `terraware`.
- The URL of the Keycloak server.

In this case, you don't need to set up Keycloak. Proceed to the "Running OAuth2 Proxy" section.

## Running Keycloak locally

The easiest way to bring up a local Keycloak server in a development environment is to use Keycloak's official Docker image. By default, the Keycloak image will use a local database inside the container, so there's no need to set up a separate database server. Obviously that's not what you'd want for production use, but for a local dev environment it's usually sufficient.

This repo has a Docker Compose file that launches a local Keycloak server. The easiest way to get started is to use it:

```shell
docker compose -f docker/keycloak-docker-compose.yml up -d
```

Go to http://localhost:8081/ and click "Administration Console".

Log in with a username of `admin` and a password of `admin` (these are set in environment variables in the Docker Compose file).

Follow the instructions in the next section to configure Keycloak for terraware-server.

## Configuring Keycloak

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

## Installing OAuth2 Proxy

You'll want to install the proxy locally. There is a Docker image, and you can make it work if your local host is running Linux, but due to limitations of the networking features in Docker for Mac and Docker for Windows, it's easier to run it on the host OS.

On OS X with Homebrew, you can install it by running `brew install oauth2-proxy`.

See [the OAuth2 Proxy install docs](https://oauth2-proxy.github.io/oauth2-proxy/docs/) for other options.

In addition, you'll need to generate a cookie secret for OAuth2 Proxy to use when it generates session cookies. The cookie secret should be 32 characters long. You can generate it any way you like, for example:

```shell
base64 < /dev/random | head -c32
```

Copy the file [`oauth2-proxy.cfg.sample`](oauth2-proxy.cfg.sample) to `oauth2-proxy.cfg` and edit the following config settings:

| Setting | Description |
| --- | ---
| `client_secret` | The client secret for the OAuth2 Proxy client, as shown in the Keycloak admin UI.
| `cookie_secret` | The random value you generated above.
| `oidc_issuer_url` | The address of your realm on your Keycloak server. Typically this will look like `https://your.keycloak.server/auth/realms/terraware`. You can leave this alone if you're running Keycloak locally with a realm name of `terraware`.
| `client_id` | The ID of the client that will be used to authenticate users to Keycloak; you can leave this alone if you're using the default one.

Then run the proxy:

```shell
oauth2-proxy -f oauth2-proxy.cfg
```

The config file is set up for a typical terraware-server development environment, where the front end (as launched with `yarn start` from the front end repo) listens on HTTP port 3000 and the back end (terraware-server) listens on HTTP port 8080. The proxy itself is configured to listen on port 4000.

Connect to http://localhost:4000/ and you should see a login form.

Proceed to the "Keycloak environment variables" section in [README.md](README.md) to tell terraware-server how to connect to Keycloak.
