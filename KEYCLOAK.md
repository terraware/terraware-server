# Keycloak quickstart guide

Terraware-server currently depends on [Keycloak](https://keycloak.org/) to manage user registration and login.

It can use an existing Keycloak server or a locally-hosted one. Either way, some initial setup is required.

## Setting Up Keycloak

You probably don't need to set up Keycloak if you work at Terraformation. Please check with a fellow developer. It is likely that someone else has already configured a Keycloak instance for use with terraware-server development environments. If that's the case, skip the "Running Keycloak locally" and "Configuring Keycloak" sections. You may proceed to the "Terraware-server configuration" section.

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

### Configuring Keycloak

These steps apply to a local instance of Keycloak, and also to an existing one that isn't yet configured for use with terraware-server.

1. Log into the Keycloak administration console.
2. Add a new realm for Terraware users.
   1. Mouse over "Master" in the left navbar and click "Add Realm."
   2. Set the name to `terraware`.
   3. Click "Create."
3. Create a client for terraware-server to use for its Keycloak admin API requests.
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
4. Create a role that will identify the synthetic Keycloak users for API clients.
   1. Click "Roles" in the left navbar.
   2. Click "Add Role" in the header bar of the list of roles.
   3. Enter a role name of `api-client`. (You can use a different name if you prefer, but we'll use that name in these instructions.)
   4. Click "Save."
5. Create a group that grants the new role to API clients.
   1. Click "Groups" in the left navbar.
   2. Click "New" in the header bar of the list of groups.
   3. Use a group name of `api-clients`. (If you choose a different name, you will have to override a configuration setting as described later.)
   4. Click "Save."
   5. Click the "Role Mappings" tab.
   6. Select "api-client" in the list of available roles and click "Add selected."
6. Create an authentication flow that only allows users with the API client role to authenticate.
   1. Click "Authentication" in the left navbar.
   2. Click "New" in the header bar of the list of authentication flows.
   3. Set "Alias" to `API Client Authentication`. (You can use a different name if you prefer.)
   4. Click "Save."
   5. Click "Add execution" in the header bar.
   6. Select "Username Validation".
   7. Click "Save".
   8. Click "Add execution" again.
   9. Select "Password."
   10. Click "Save."
   11. Click the "Required" radio button on the "Password" line.
   12. Click "Add flow" in the header bar.
   13. Set "Alias" to `Require API Client Role`. (You can use a different name if you prefer.)
   14. Click "Save."
   15. Click the "Conditional" radio button on the "Require API Client Role" line.
   16. From the "Actions" drop-down on the "Require API Client Role" line, click "Add execution."
   17. Select "Condition - User Role."
   18. Click "Save."
   19. Click the "Required" radio button on the "Condition - User Role" line.
   20. From the "Actions" drop-down on the "Condition - User Role" line, click "Config."
   21. Set "Alias" to `api-client`.
   22. Set "User role" to `api-client`.
   23. Set "Negate output" to "On."
   24. Click "Save."
   25. Click "API Client Authentication" in the navigation links at the top of the page.
   26. From the "Actions" drop-down on the "Require API Client Role" line, click "Add execution."
   27. Select "Deny Access."
   28. Click "Save."
   29. Click the "Required" radio button on the "Deny Access" line.
7. Create a Keycloak client that terraware-server API clients will use to request access tokens.
   1. Click "Clients" in the left navbar.
   2. Click "Create" in the header bar of the list of clients.
   3. Set the client ID to `api`. Leave the other fields set to their default values.
   4. Click "Save."
   5. Set "Standard Flow Enabled" to "Off."
   6. Click "Authentication Flow Overrides" to expand that section of the page.
   7. Set "Browser Flow" to "API Client Authentication."
   8. Set "Direct Grant Flow" to "API Client Authentication."
   9. Click "Save."

### Terraware-server configuration

You'll need to run Keycloak to authenticate to terraware-server, and terraware-server needs to know how to communicate with Keycloak, which requires supplying four configuration settings. There are also some settings that have default values you can override if needed.

There are two main ways to supply these settings: in environment variables or in a profile-specific properties file. Generally, you'll use a properties file in a local development environment, and environment variables everywhere else. If for some reason you set a value both ways, the environment variable will take precedence.

If you work at Terraformation then ask a fellow developer for the values of these settings (likely in the Terraformation 1Password Eng Infra Vault).

| Property | Environment Variable | Description
| --- | --- | ---
| `spring.security.oauth2.client.provider.keycloak.issuer-uri` | `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_ISSUERURI` | The base URL of the Terraware realm on your Keycloak server. If you are running Keycloak locally, this will be `http://localhost:8081/realms/terraware`. Otherwise, it will be the URL of your Keycloak server including the realm prefix for the Terraware realm, which is usually `/realms/terraware`.
| `spring.security.oauth2.client.registration.keycloak.client-id` | `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_CLIENTID` | The client ID terraware-server will use to make Keycloak API requests. If you followed the instructions to create a local Keycloak instance, then this will be `dev-terraware-server`.
| `spring.security.oauth2.client.registration.keycloak.client-secret` | `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENTSECRET` | The secret associated with the client ID.
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI` | Set this to the same value as the other issuer URI.
| `terraware.keycloak.api-client-id` | `TERRAWARE_KEYCLOAK_API_CLIENT_ID` | The Keycloak client ID that terraware-server API clients will use to generate access tokens. If you followed the instructions to create a local Keycloak instance, then this will be `api`.
| `terraware.keycloak.api-client-group-name` | `TERRAWARE_KEYCLOAK_API_CLIENT_GROUP_NAME` | The name of the Keycloak group to add newly-created API client users to. The default is `/api-clients`. If you chose a different group name when you were setting up Keycloak, you'll need to set this. Note that because Keycloak group names are hierarchical, the value must start with `/`.
| `terraware.keycloak.api-client-username-prefix` | `TERRAWARE_KEYCLOAK_API_CLIENT_USERNAME_PREFIX` | A prefix to put at the beginning of the Keycloak usernames of API client users. The default is `api-`. This is to make the users easy to identify in the Keycloak admin console.

#### Using environment variables

If you're launching the server from the command line (including using Gradle) you can set these in your shell.

If you're launching the server from an IDE such as IntelliJ IDEA, you can set them in the IDE's configuration. In IntelliJ, environment variables are part of the "run configuration" for the server. Follow the instructions below to make IntelliJ pass the environment variables to the server each time you run it.

1. In the drop-down menu of run configurations in the toolbar at the top of the IntelliJ window, choose "Edit Configurations..."
2. Select "Application" under "Spring Boot" if it's not already selected.
3. Click the little icon at the end of the "Environment Variables" text field to pop up a dialog that shows the current set of environment variables.
4. Add all the environment variables listed above.
5. Click OK on the environment variable dialog and the run configurations dialog.

#### Using a profile-specific properties file for local development

See the "Using a profile-specific properties file for local development" section in [README.md](README.md) to learn more about this; you can also set other values that aren't related to Keycloak. The Keycloak-related part of the properties file will be structured hierarchically, for example:

```yaml
spring:
  security:
    oauth2:
      client:
        provider:
          keycloak:
            issuer-uri: http://your-server/realms/your-realm
        registration:
          keycloak:
            client-id: your-client-id
            client-secret: your-client-secret
      resourceserver:
        jwt:
          issuer-uri: http://your-server/realms/your-realm
```
