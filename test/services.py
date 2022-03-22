import logging
import random
import time
from pprint import pprint
from typing import Optional, Tuple

import docker
from keycloak import KeycloakAdmin
from keycloak.exceptions import KeycloakConnectionError

logger = logging.getLogger(__name__)


#
# End-to-end tests touch multiple services that form a dependency graph:
#
# - PostgreSQL database
# - Keycloak server (using an in-memory database)
# - Terraware server configured to talk to PostgreSQL and Keycloak
#
#


def start_keycloak(image="quay.io/keycloak/keycloak:14.0.0"):
    client = docker.from_env()
    containers = client.containers.list(all=True, filters={"name": "/keycloak"})

    for container in containers:
        if container.status == "running":
            logger.info("Using existing Keycloak container %s", container.id)
            return
        else:
            logger.info("Removing stopped Keycloak container %s", container.id)
            container.remove()

    client.containers.run(
        image=image,
        detach=True,
        ports={"8080/tcp": 8081},
        name="keycloak",
        environment={
            "KEYCLOAK_USER": "admin",
            "KEYCLOAK_PASSWORD": "admin",
            "WEB_APP_URL": "http://localhost:3000/",
        },
    )


def get_keycloak_admin(realm="master"):
    return KeycloakAdmin(
        server_url="http://localhost:8081/auth/",
        username="admin",
        password="admin",
        realm_name=realm,
        user_realm_name="master",
    )


def init_keycloak() -> KeycloakAdmin:
    retries_remaining = 30

    # If we've just started the Keycloak container, wait for it to become ready.
    while True:
        try:
            logger.debug("Trying to connect to Keycloak")
            keycloak_admin = get_keycloak_admin()
            break
        except KeycloakConnectionError as ex:
            retries_remaining -= 1
            if retries_remaining > 0:
                logger.debug("Unable to connect to Keycloak; retrying", ex)
                time.sleep(1)
            else:
                raise ex

    keycloak_admin.create_realm(
        payload={
            "realm": "terraware",
            "enabled": True,
            "displayName": "Terraware",
            "duplicateEmailsAllowed": False,
            "editUsernameAllowed": False,
            "loginWithEmailAllowed": True,
            "registrationAllowed": True,
            "registrationEmailAsUsername": True,
            "resetPasswordAllowed": True,
            "verifyEmail": False,
        },
        skip_exists=True,
    )

    # We don't need admin access to the master realm any more.
    keycloak_admin = get_keycloak_admin("terraware")

    keycloak_admin.create_client(
        payload={
            "clientId": "dev-terraware-server",
            "standardFlowEnabled": True,
            "serviceAccountsEnabled": True,
            "redirectUris": ["http://localhost:8080/*"],
        },
        skip_exists=True,
    )

    client = [
        client
        for client in keycloak_admin.get_clients()
        if client["clientId"] == "dev-terraware-server"
    ][0]
    pprint(client)
    service_user = keycloak_admin.get_client_service_account_user(client["id"])

    pprint(service_user)

    return keycloak_admin


email_count = 0


def create_user(
    keycloak_admin: KeycloakAdmin,
    email: Optional[str] = None,
    first_name: Optional[str] = None,
    last_name: Optional[str] = None,
) -> Tuple[str, str]:
    """Create a new user. Return the Keycloak user ID.

    :param email Email address, or None to generate a unique one.
    :return A tuple of the email address and Keycloak user ID.
    """
    global email_count
    email_count += 1

    email = email or f"{time.time()}@{email_count}.org"
    first_name = first_name or email.split("@")[0]
    last_name = last_name or email.split("@")[1]

    user_id = keycloak_admin.create_user(
        payload={
            "email": email,
            "username": email,
            "firstName": first_name,
            "lastName": last_name,
        }
    )

    return email, user_id


start_keycloak("sgrimm/keycloak:14.0.0")
keycloak_admin = init_keycloak()

pprint(create_user(keycloak_admin))
