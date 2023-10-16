import os
from argparse import ArgumentParser, Namespace

import jwt
import requests
from typing import Optional

DEFAULT_URL = "http://localhost:8080"


def _authenticated(func):
    """Fetch a fresh access token and retry a request if it gets a 401 Unauthorized response."""

    def retry_on_unauthorized(self: "TerrawareClient", *args, **kwargs):
        try:
            return func(self, *args, **kwargs)
        except requests.exceptions.HTTPError as ex:
            if self.refresh_token and ex.response and ex.response.status_code == 401:
                self.fetch_access_token()
                return func(self, *args, **kwargs)
            else:
                raise ex

    return retry_on_unauthorized


class TerrawareClient:
    def __init__(
        self,
        refresh_token: Optional[str] = None,
        session: Optional[str] = None,
        base_url: Optional[str] = None,
    ):
        self.base_url = (base_url or DEFAULT_URL).rstrip("/")
        self.refresh_token = refresh_token

        if session:
            self.auth_header = {"Cookie": f"SESSION={session}"}
        elif refresh_token:
            self.fetch_access_token()
        else:
            self.auth_header = {}

    def _add_auth_header(self, kwargs):
        """Add an authentication header to the keyword arguments of a requests API call."""
        existing_headers = kwargs.get("headers", {})
        return {**kwargs, "headers": {**self.auth_header, **existing_headers}}

    @_authenticated
    def delete(self, url, **kwargs):
        kwargs_with_auth = self._add_auth_header(kwargs)
        r = requests.delete(self.base_url + url, **kwargs_with_auth)
        self.raise_for_status(r)
        return r.json()

    @_authenticated
    def get(self, url, **kwargs):
        kwargs_with_auth = self._add_auth_header(kwargs)
        r = requests.get(self.base_url + url, **kwargs_with_auth)
        self.raise_for_status(r)
        return r.json()

    @_authenticated
    def post_raw(self, url, **kwargs):
        kwargs_with_auth = self._add_auth_header(kwargs)
        r = requests.post(self.base_url + url, **kwargs_with_auth)
        self.raise_for_status(r)
        return r

    def post(self, url, **kwargs):
        return self.post_raw(url, **kwargs).json()

    @_authenticated
    def put(self, url, **kwargs):
        kwargs_with_auth = self._add_auth_header(kwargs)
        r = requests.put(self.base_url + url, **kwargs_with_auth)
        self.raise_for_status(r)
        return r.json()

    @staticmethod
    def raise_for_status(r: requests.Response):
        if r.status_code > 399:
            if "Content-Type" in r.headers and r.headers["Content-Type"].startswith(
                "application/json"
            ):
                payload = r.json()
                if "error" in payload and "message" in payload["error"]:
                    raise requests.exceptions.HTTPError(
                        payload["error"]["message"], response=r
                    )
            r.raise_for_status()

    def get_me(self):
        return self.get("/api/v1/users/me")["user"]

    def list_organizations(self):
        return self.get("/api/v1/organizations")["organizations"]

    def get_default_organization_id(self, require_admin=True):
        return min(
            [
                organization["id"]
                for organization in self.list_organizations()
                if not require_admin or organization["role"] in ["Admin", "Owner"]
            ]
        )

    def get_facility(self, facility_id):
        return self.get(f"/api/v1/facilities/{facility_id}")["facility"]

    def list_facilities(self):
        return self.get("/api/v1/facilities")["facilities"]

    def list_devices(self, facility_id):
        return self.get(f"/api/v1/facilities/{facility_id}/devices")["devices"]

    def get_device(self, device_id):
        return self.get(f"/api/v1/devices/{device_id}")["device"]

    def create_timeseries(self, payload):
        return self.post("/api/v1/timeseries/create", json=payload)

    def record_values(self, payload):
        return self.post("/api/v1/timeseries/values", json=payload)

    def list_timeseries(self, device_id):
        return self.get(f"/api/v1/timeseries?deviceId={device_id}")["timeseries"]

    def create_accession(self, payload):
        uri = f"/api/v2/seedbank/accessions"
        return self.post(uri, json=payload)["accession"]

    def check_in_accession(self, accession_id):
        uri = f"/api/v1/seedbank/accessions/{accession_id}/checkIn"
        return self.post(uri)["accession"]

    def get_accession(self, accession_id):
        uri = f"/api/v2/seedbank/accessions/{accession_id}"
        return self.get(uri)["accession"]

    def update_accession(self, accession_id, payload, simulate=False):
        if simulate:
            query = "?simulate=true"
        else:
            query = ""
        uri = f"/api/v2/seedbank/accessions/{accession_id}{query}"

        return self.put(uri, json=payload)["accession"]

    def delete_accession(self, accession_id):
        return self.delete(f"/api/v1/seedbank/accessions/{accession_id}")

    def create_viability_test(self, accession_id, payload):
        uri = f"/api/v2/seedbank/accessions/{accession_id}/viabilityTests"
        return self.post(uri, json=payload)["accession"]

    def delete_viability_test(self, accession_id, viability_test_id):
        uri = f"/api/v2/seedbank/accessions/{accession_id}/viabilityTests/{viability_test_id}"
        return self.delete(uri)["accession"]

    def update_viability_test(self, accession_id, viability_test_id, payload):
        uri = f"/api/v2/seedbank/accessions/{accession_id}/viabilityTests/{viability_test_id}"
        return self.put(uri, json=payload)["accession"]

    def export_search(self, payload):
        """Return a response with a text/csv content type."""
        return self.post_raw(
            "/api/v1/search", headers={"Accept": "text/csv"}, json=payload
        )

    def search(self, payload):
        return self.post("/api/v1/search", json=payload)["results"]

    def search_accession_values(self, payload):
        return self.post("/api/v1/seedbank/values", json=payload)["results"]

    def search_all_accession_values(self, payload):
        return self.post("/api/v1/seedbank/values/all", json=payload)["results"]

    def create_species(self, payload):
        return self.post("/api/v1/species", json=payload)["id"]

    def list_species(self, organization_id):
        return self.get(f"/api/v1/species?organizationId={organization_id}")["species"]

    def create_seedling_batch(self, payload):
        return self.post("/api/v1/nursery/batches", json=payload)["batch"]

    def get_planting_site(self, planting_site_id, depth="Site"):
        return self.get(f"/api/v1/tracking/sites/{planting_site_id}?depth={depth}")[
            "site"
        ]

    def get_observation(self, observation_id):
        return self.get(f"/api/v1/tracking/observations/{observation_id}")[
            "observation"
        ]

    def list_observations(self, organization_id):
        return self.get(
            f"/api/v1/tracking/observations?organizationId={organization_id}"
        )["observations"]

    def list_observation_plots(self, observation_id):
        return self.get(f"/api/v1/tracking/observations/{observation_id}/plots")[
            "plots"
        ]

    def claim_observation_plot(self, observation_id, plot_id):
        return self.post(
            f"/api/v1/tracking/observations/{observation_id}/plots/{plot_id}/claim"
        )

    def complete_observation(self, observation_id, plot_id, payload):
        return self.post(
            f"/api/v1/tracking/observations/{observation_id}/plots/{plot_id}",
            json=payload,
        )

    def fetch_access_token(self):
        if self.refresh_token:
            # This depends on how Keycloak populates some JWT fields. We don't bother verifying
            # the signature because we're only using this to form a request to send to Keycloak,
            # and Keycloak will reject it if the parameters are bogus.
            decoded_token = jwt.decode(
                self.refresh_token, options={"verify_signature": False}
            )
            base_url = decoded_token["iss"]
            client_id = decoded_token["azp"]

            r = requests.post(
                f"{base_url}/protocol/openid-connect/token",
                data={
                    "client_id": client_id,
                    "grant_type": "refresh_token",
                    "refresh_token": self.refresh_token,
                },
            )

            if (
                r.status_code > 399
                and "Content-Type" in r.headers
                and r.headers["Content-Type"].startswith("application/json")
            ):
                payload = r.json()
                if "error_description" in payload:
                    raise requests.exceptions.HTTPError(
                        payload["error_description"], response=r
                    )
            r.raise_for_status()

            access_token = r.json()["access_token"]

            self.auth_header = {"Authorization": f"Bearer {access_token}"}


def add_terraware_args(parser: ArgumentParser):
    """Add a standard set of arguments to configure a TerrawareClient.

    Use client_from_args() to create a TerrawareClient from the parsed arguments.
    """
    parser.add_argument(
        "--refresh-token",
        help="Refresh token to use (session cookie is ignored if this is set). Default is the "
        "TERRAWARE_REFRESH_TOKEN environment variable.",
    )
    parser.add_argument(
        "--session",
        help="Session cookie to use instead of canned one from test database",
    )
    parser.add_argument(
        "--url",
        "-u",
        default=DEFAULT_URL,
        help="Base URL of terraware-server. Default is http://localhost:8080.",
    )


def client_from_args(args: Namespace) -> TerrawareClient:
    refresh_token = args.refresh_token or os.getenv("TERRAWARE_REFRESH_TOKEN")

    if not refresh_token and not args.session:
        raise Exception("Must specify --refresh-token or --session")

    return TerrawareClient(
        refresh_token,
        args.session,
        args.url,
    )
