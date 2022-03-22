from typing import Optional, Dict

from box import Box
from requests_oauthlib import OAuth2Session


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

    def get_accession(self, accession_id: int) -> Box:
        return self.get(f"/api/v1/seedbank/accession/{accession_id}").accession

    def put_accession(self, accession: Box) -> Box:
        return self.put(f"/api/v1/seedbank/accession/{accession.id}", json=accession).accession
