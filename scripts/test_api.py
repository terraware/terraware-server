import os
import random
from typing import Dict, Optional

import pytest
from box import Box
from oauthlib.oauth2 import LegacyApplicationClient
from requests_oauthlib import OAuth2Session

import swagger_client
from swagger_client import CustomerApi, ApiClient, CreateOrganizationRequestPayload

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


@pytest.fixture(scope="session")
def api_client(client) -> ApiClient:
    conf = swagger_client.Configuration()
    conf.access_token = client.session.access_token
    conf.host = DEFAULT_BASE_URL
    return ApiClient(conf)


@pytest.fixture(scope="session")
def customer_api(api_client) -> CustomerApi:
    return CustomerApi(api_client)


class TestOrganizations:
    @pytest.fixture(scope="class")
    def organization_name(self):
        return f"Test Org {random.randint(1000000, 9999999)}"

    @pytest.fixture(scope="class")
    def organization_id(self, client, organization_name):
        response = client.post("/api/v1/organizations", {"name": organization_name})
        return response.organization.id

    def test_create_organization(self, organization_id):
        assert organization_id is not None

    def test_list_organizations(self, client, organization_id):
        response = client.get("/api/v1/organizations")
        assert organization_id in [item.id for item in response.organizations]

    def test_get_organization(self, client, organization_id, organization_name):
        response = client.get(f"/api/v1/organizations/{organization_id}")
        assert response.organization.name == organization_name


class TestSwagger:
    @pytest.fixture(scope="class")
    def organization_name(self):
        return f"Test Org {random.randint(1000000, 9999999)}"

    @pytest.fixture(scope="class")
    def organization_id(self, customer_api, organization_name):
        response = customer_api.create_organization(
            CreateOrganizationRequestPayload(name=organization_name))
        return response.organization.id

    def test_create_organization(self, organization_id):
        assert organization_id is not None

    def test_list_organizations(self, customer_api, organization_id):
        response = customer_api.list_organizations()
        assert organization_id in [item.id for item in response.organizations]

    def test_get_organization(self, customer_api, organization_id, organization_name):
        response = customer_api.get_organization(organization_id)
        assert response.organization.name == organization_name
