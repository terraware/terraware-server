import pytest
from box import Box


@pytest.mark.dependency()
def test_create_accession(client, seed_bank_facility_id, accession):
    expected = {
        "facilityId": seed_bank_facility_id,
        "species": "Kousa Dogwoord",
        "family": "Cornaceae",
        "numberOfTrees": 3,
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
        "id": accession.id,
        "active": "Active",
        "state": "Awaiting Check-In",
        "source": "Web",
    }

    # We don't care about the values of some fields, just that they exist.
    assert accession.accessionNumber is not None
    del accession.accessionNumber
    assert accession.speciesId >= 0
    del accession.speciesId

    assert accession == Box(expected)


@pytest.mark.dependency(depends=["test_create_accession"])
def test_check_in_accession(client, accession_url):
    response = client.post(f"{accession_url}/checkIn", json=None).accession

    assert response.state == "Pending"


@pytest.mark.dependency(depends=["test_check_in_accession"])
def test_update_accession(client, accession):
    del accession.secondaryCollectors[1]
    accession.primaryCollector = "Leann"
    accession.fieldNotes = "Other notes"

    updated = client.put_accession(accession)

    assert updated.secondaryCollectors == ["Constanza"]
    assert updated.primaryCollector == "Leann"
    assert updated.fieldNotes == "Other notes"


@pytest.mark.dependency(depends=["test_check_in_accession"])
def test_send_to_nursery(client, accession):
    accession.nurseryStartDate = "2021-03-01"
    updated = client.put_accession(accession)

    assert updated.state == "Nursery"


@pytest.mark.dependency(depends=["test_send_to_nursery"])
def test_unsend_to_nursery(client, accession):
    del accession.nurseryStartDate
    updated = client.put_accession(accession)

    assert updated.state == "Pending"
