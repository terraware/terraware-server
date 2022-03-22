import pytest


@pytest.fixture(scope="module")
def accession_id(client, seed_bank_facility_id):
    create_response = client.post(
        "/api/v1/seedbank/accession",
        json={
            "facilityId": seed_bank_facility_id,
            "species": "Kousa Dogwoord",
            "family": "Cornaceae",
            "numberOfTrees": "3",
            "founderId": "234908098",
            "endangered": "Yes",
            "rare": "Yes",
            "sourcePlantOrigin": "Outplant",
            "receivedDate": "2021-02-03",
            "secondaryCollectors": ["Constanza", "Leann"],
            "fieldNotes": "Some notes",
            "collectedDate": "2021-02-01",
            "primaryCollector": "Carlos",
            "siteLocation": "Sunset Overdrive",
            "landowner": "Yacin",
            "environmentalNotes": "Cold day",
        },
    )

    return create_response.accession.id


@pytest.fixture(scope="module")
def accession_url(accession_id):
    return f"/api/v1/seedbank/accession/{accession_id}"


@pytest.fixture(scope="function")
def accession(client, accession_id):
    return client.get_accession(accession_id)
