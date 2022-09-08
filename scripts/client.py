from argparse import ArgumentParser, Namespace
import requests
from typing import Optional

DEFAULT_URL = "http://localhost:8080"


class TerrawareClient:
    def __init__(
        self,
        bearer: Optional[str] = None,
        session: Optional[str] = None,
        base_url: Optional[str] = None,
    ):
        if bearer:
            self.auth_header = {"Authorization": f"Bearer {bearer}"}
        else:
            self.auth_header = {"Cookie": f"SESSION={session}"}
        self.base_url = (base_url or DEFAULT_URL).rstrip("/")

    def _add_auth_header(self, kwargs):
        """Add an authentication header to the keyword arguments of a requests API call."""
        existing_headers = kwargs.get("headers", {})
        return {**kwargs, "headers": {**self.auth_header, **existing_headers}}

    def delete(self, url, **kwargs):
        kwargs_with_auth = self._add_auth_header(kwargs)
        r = requests.delete(self.base_url + url, **kwargs_with_auth)
        r.raise_for_status()
        return r.json()

    def get(self, url, **kwargs):
        kwargs_with_auth = self._add_auth_header(kwargs)
        r = requests.get(self.base_url + url, **kwargs_with_auth)
        r.raise_for_status()
        return r.json()

    def post_raw(self, url, **kwargs):
        kwargs_with_auth = self._add_auth_header(kwargs)
        r = requests.post(self.base_url + url, **kwargs_with_auth)
        r.raise_for_status()
        return r

    def post(self, url, **kwargs):
        return self.post_raw(url, **kwargs).json()

    def put(self, url, **kwargs):
        kwargs_with_auth = self._add_auth_header(kwargs)
        r = requests.put(self.base_url + url, **kwargs_with_auth)
        r.raise_for_status()
        return r.json()

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

    def create_accession(self, payload, version=1):
        uri = f"/api/v{version}/seedbank/accessions"
        return self.post(uri, json=payload)["accession"]

    def check_in_accession(self, accession_id):
        uri = f"/api/v1/seedbank/accessions/{accession_id}/checkIn"
        return self.post(uri)["accession"]

    def get_accession(self, accession_id, version=1):
        uri = f"/api/v{version}/seedbank/accessions/{accession_id}"
        return self.get(uri)["accession"]

    def update_accession(self, accession_id, payload, simulate=False, version=1):
        if simulate:
            query = "?simulate=true"
        else:
            query = ""
        uri = f"/api/v{version}/seedbank/accessions/{accession_id}{query}"

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

    def list_species(self, organization_id):
        return self.get(f"/api/v1/species?organizationId={organization_id}")["species"]


def add_terraware_args(parser: ArgumentParser):
    """Add a standard set of arguments to configure a TerrawareClient.

    Use client_from_args() to create a TerrawareClient from the parsed arguments.
    """
    parser.add_argument(
        "--bearer",
        help="Bearer token to use (session cookie is ignored if this is set)",
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
    return TerrawareClient(args.bearer, args.session, args.url)
