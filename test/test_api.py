import os
import random
from contextlib import contextmanager
from http import HTTPStatus
from typing import Dict, Optional

import pytest
from box import Box
from oauthlib.oauth2 import LegacyApplicationClient
from requests import HTTPError
from requests_oauthlib import OAuth2Session

DEFAULT_BASE_URL = "http://localhost:8080"


class TerrawareClient:
    def __init__(self, session: OAuth2Session, base_url: str):
        self.session = session
        self.base_url = base_url

    def delete(self, local_url: str, **kwargs) -> Box:
        r = self.session.delete(f"{self.base_url}{local_url}", **kwargs)
        r.raise_for_status()
        return Box(r.json())

    def get(self, local_url: str, **kwargs) -> Box:
        r = self.session.get(f"{self.base_url}{local_url}", **kwargs)
        r.raise_for_status()
        return Box(r.json())

    def post(self, local_url: str, json: Optional[Dict], **kwargs) -> Box:
        r = self.session.post(f"{self.base_url}{local_url}", json=json, **kwargs)
        r.raise_for_status()
        return Box(r.json())

    def put(self, local_url: str, json: Optional[Dict], **kwargs) -> Box:
        r = self.session.put(f"{self.base_url}{local_url}", json=json, **kwargs)
        r.raise_for_status()
        return Box(r.json())


@pytest.fixture(scope="session")
def client() -> TerrawareClient:
    client_id = os.environ.get("TEST_CLIENT_ID") or "dev-terraware-server"
    realm = os.environ.get("TEST_REALM") or "terraware"
    client_secret = os.environ["TEST_CLIENT_SECRET"]
    password = os.environ["TEST_PASSWORD"]
    username = os.environ["TEST_USERNAME"]
    keycloak_base_url = os.environ["TEST_AUTH_SERVER_URL"]

    token_url = os.environ.get(
        "TEST_TOKEN_URL") or f"{keycloak_base_url}/realms/{realm}/protocol/openid-connect/token"

    os.environ["OAUTHLIB_INSECURE_TRANSPORT"] = "1"

    oauth = OAuth2Session(client=LegacyApplicationClient(client_id=client_id))
    oauth.fetch_token(token_url=token_url, username=username, password=password,
                      client_secret=client_secret, client_id=client_id)

    return TerrawareClient(oauth, DEFAULT_BASE_URL)


@contextmanager
def expect_error(status: HTTPStatus = HTTPStatus.BAD_REQUEST, message: Optional[str] = None):
    with pytest.raises(HTTPError) as exc_info:
        yield exc_info

    assert exc_info.value.response.status_code == status.value
    payload = exc_info.value.response.json()
    assert payload["status"] == "error"
    if message:
        assert payload["error"]["message"] == message


@pytest.fixture(scope="session")
def organization_name():
    return f"Test Org {random.randint(1000000, 9999999)}"


@pytest.fixture(scope="session")
def organization_id(client, organization_name):
    response = client.post("/api/v1/organizations", {"name": organization_name})
    return response.organization.id


@pytest.fixture(scope="session")
def seed_bank_facility_id(client, organization_id):
    response = client.get(f"/api/v1/organizations/{organization_id}",
                          params={"depth": "Facility"})
    seed_bank_project = \
        [project for project in response.organization.projects if project.name == "Seed Bank"][0]
    seed_bank_site = [site for site in seed_bank_project.sites if site.name == "Seed Bank"][0]
    seed_bank_facility = \
        [facility for facility in seed_bank_site.facilities if facility.type == "Seed Bank"][0]
    return seed_bank_facility.id


class TestOrganizations:
    def test_create_organization(self, organization_id):
        assert organization_id is not None

    def test_create_organization_with_empty_name(self, client):
        with expect_error():
            client.post("/api/v1/organizations", {"name": ""})

    def test_list_organizations(self, client, organization_id):
        response = client.get("/api/v1/organizations")
        assert organization_id in [item.id for item in response.organizations]

    def test_get_organization(self, client, organization_id, organization_name):
        response = client.get(f"/api/v1/organizations/{organization_id}")
        assert response.organization.name == organization_name


class TestSpecies:
    species_name = "Test Species"

    def test_create_species_requires_name(self, client, organization_id):
        with expect_error():
            client.post("/api/v1/species", {"organizationId": organization_id, "name": ""})

    @pytest.mark.dependency()
    def test_create_species(self, client, organization_id):
        response = client.post("/api/v1/species",
                               {"organizationId": organization_id, "name": self.species_name})
        assert response.id > 0

    @pytest.mark.dependency(depends=["TestSpecies::test_create_species"])
    def test_create_species_rejects_duplicate_name(self, client, organization_id):
        with expect_error(HTTPStatus.CONFLICT):
            response = client.post("/api/v1/species",
                                   {"organizationId": organization_id, "name": self.species_name})


@pytest.mark.dependency(depends=["TestSpecies::test_create_species"])
def test_seed_summary(client, seed_bank_facility_id):
    response = client.get(f"/api/v1/seedbank/summary/{seed_bank_facility_id}")
    zero_counts = {"current": 0, "lastWeek": 0}
    assert response.to_dict() == {
        "activeAccessions": zero_counts,
        "species": {"current": 1, "lastWeek": 0},
        "families": zero_counts,
        "overduePendingAccessions": 0,
        "overdueProcessedAccessions": 0,
        "overdueDriedAccessions": 0,
        "recentlyWithdrawnAccessions": 0,
    }
