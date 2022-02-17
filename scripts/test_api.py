import os
import unittest

import requests


def get_access_token(username: str, password: str, client_secret: str,
                     client_id: str = "dev-terraware-server",
                     realm: str = "terraware",
                     auth_server_url: str = "https://localhost:8081/auth"):
    token_url = f"{auth_server_url}/realms/{realm}/protocol/openid-connect/token"

    post_body = {"client_id": client_id, "client_secret": client_secret, "username": username,
                 "password": password, "grant_type": "password"}

    print(post_body)
    response = requests.post(token_url, data=post_body)
    response.raise_for_status()

    json = response.json()
    print(json)

    return json


class MyTestCase(unittest.TestCase):
    def setUp(self):
        self.auth_server_url = os.environ["TEST_AUTH_SERVER_URL"]
        self.client_secret = os.environ["TEST_CLIENT_SECRET"]
        self.password = os.environ["TEST_PASSWORD"]
        self.username = os.environ["TEST_USERNAME"]

    def test_url(self):
        self.assertIsNotNone(get_access_token(self.username, self.password, self.client_secret,
                                              auth_server_url=self.auth_server_url))


if __name__ == '__main__':
    unittest.main()
