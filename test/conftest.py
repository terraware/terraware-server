import os
import random
from typing import Dict

import pytest
import yaml
from oauthlib.oauth2 import LegacyApplicationClient
from requests_oauthlib import OAuth2Session

from client import TerrawareClient
from util import flatten_dict

DEFAULT_BASE_URL = "http://localhost:8080"


@pytest.fixture(scope="session")
def spring_config() -> Dict:
    spring_profiles = os.environ.get("TEST_SPRING_PROFILES")
    if spring_profiles:
        with open("../src/main/resources/application.yaml", "r") as fp:
            spring_config = flatten_dict(yaml.safe_load(fp))
        for profile in spring_profiles.split(","):
            with open(f"../src/main/resources/application-{profile}.yaml", "r") as fp:
                config = flatten_dict(yaml.safe_load(fp))
                spring_config |= config
    else:
        spring_config = {}

    return spring_config


@pytest.fixture(scope="session")
def client(spring_config) -> TerrawareClient:
    client_id = os.environ.get(
        "TEST_CLIENT_ID", spring_config.get("keycloak.resource", "dev-terraware-server")
    )
    realm = os.environ.get(
        "TEST_REALM", spring_config.get("keycloak.realm", "terraware")
    )
    client_secret = os.environ.get(
        "TEST_CLIENT_SECRET", spring_config.get("keycloak.credentials.secret")
    )
    password = os.environ["TEST_PASSWORD"]
    username = os.environ["TEST_USERNAME"]
    keycloak_base_url = os.environ.get(
        "TEST_AUTH_SERVER_URL", spring_config.get("keycloak.auth-server-url")
    )

    token_url = os.environ.get(
        "TEST_TOKEN_URL",
        f"{keycloak_base_url}/realms/{realm}/protocol/openid-connect/token",
    )

    os.environ["OAUTHLIB_INSECURE_TRANSPORT"] = "1"

    oauth = OAuth2Session(client=LegacyApplicationClient(client_id=client_id))
    oauth.fetch_token(
        token_url=token_url,
        username=username,
        password=password,
        client_secret=client_secret,
        client_id=client_id,
    )

    base_url = os.environ.get("TEST_BASE_URL", DEFAULT_BASE_URL)

    return TerrawareClient(oauth, base_url)


@pytest.fixture(scope="module")
def organization_name():
    return f"Test Org {random.randint(1000000, 9999999)}"


@pytest.fixture(scope="module")
def organization_id(client, organization_name):
    response = client.post("/api/v1/organizations",
                           {"name": organization_name})
    return response.organization.id


@pytest.fixture(scope="module")
def seed_bank_facility_id(client, organization_id):
    response = client.get(
        f"/api/v1/organizations/{organization_id}", params={"depth": "Facility"}
    )
    seed_bank_project = [
        project
        for project in response.organization.projects
        if project.name == "Seed Bank"
    ][0]
    seed_bank_site = [
        site for site in seed_bank_project.sites if site.name == "Seed Bank"
    ][0]
    seed_bank_facility = [
        facility
        for facility in seed_bank_site.facilities
        if facility.type == "Seed Bank"
    ][0]
    return seed_bank_facility.id