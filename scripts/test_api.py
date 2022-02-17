import os

import pytest
from oauthlib.oauth2 import LegacyApplicationClient
from requests_oauthlib import OAuth2Session


base_url = "http://localhost:8080"


@pytest.fixture(scope="session")
def client() -> OAuth2Session:
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

    return oauth


class TestOrganizations:
    @pytest.fixture(scope="class")
    def organization_id(self, client):
        r = client.post(f"{base_url}/api/v1/organizations", json={"name": "Test Org"})
        r.raise_for_status()
        return r.json()["organization"]["id"]

    def test_create_organization(self, organization_id):
        assert organization_id is not None

    def test_list_organizations(self, client, organization_id):
        r = client.get(f"{base_url}/api/v1/organizations")
        r.raise_for_status()

        assert organization_id in [item["id"] for item in r.json()["organizations"]]
