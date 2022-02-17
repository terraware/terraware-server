import os
import unittest
from typing import Optional

from oauthlib.oauth2 import LegacyApplicationClient
from requests_oauthlib import OAuth2Session


def terraware_client(username: str, password: str, client_secret: str,
                     client_id: str = "dev-terraware-server",
                     realm: str = "terraware",
                     keycloak_base_url: str = "https://localhost:8081/auth",
                     token_url: Optional[str] = None):
    token_url = token_url or f"{keycloak_base_url}/realms/{realm}/protocol/openid-connect/token"
    oauth = OAuth2Session(client=LegacyApplicationClient(client_id=client_id))
    oauth.fetch_token(token_url=token_url, username=username, password=password,
                      client_secret=client_secret, client_id=client_id)
    return oauth


class MyTestCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        auth_server_url = os.environ["TEST_AUTH_SERVER_URL"]
        client_secret = os.environ["TEST_CLIENT_SECRET"]
        password = os.environ["TEST_PASSWORD"]
        username = os.environ["TEST_USERNAME"]
        cls.client = terraware_client(username, password, client_secret,
                                      keycloak_base_url=auth_server_url)

    def test_url(self):
        r = self.client.get("http://localhost:8080/api/v1/organizations")
        print(r.json())


if __name__ == '__main__':
    unittest.main()
