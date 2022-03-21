import pytest
from box import Box

from util import expect_error


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


@pytest.mark.dependency()
def test_create_accession(client, seed_bank_facility_id, accession_id, accession_url):
    accession = client.get(accession_url).accession

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
        "id": accession_id,
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
    response = client.post(
        f"{accession_url}/checkIn", json=None
    ).accession

    assert response.state == "Pending"


@pytest.mark.dependency(depends=["test_check_in_accession"])
def test_update_accession(client, accession_url):
    accession = client.get(accession_url).accession
    del accession.secondaryCollectors[1]
    accession.primaryCollector = "Leann"
    accession.fieldNotes = "Other notes"

    updated = client.put(accession_url, json=accession).accession

    assert updated.secondaryCollectors == ["Constanza"]
    assert updated.primaryCollector == "Leann"
    assert updated.fieldNotes == "Other notes"


@pytest.mark.dependency(depends=["test_check_in_accession"])
def test_send_to_nursery(client, accession_url):
    accession = client.get(accession_url).accession
    accession.nurseryStartDate = "2021-03-01"
    updated = client.put(accession_url, json=accession).accession

    assert updated.state == "Nursery"


@pytest.mark.dependency(depends=["test_send_to_nursery"])
def test_unsend_to_nursery(client, accession_url):
    accession = client.get(accession_url).accession
    del accession.nurseryStartDate
    updated = client.put(accession_url, json=accession).accession

    assert updated.state == "Pending"


@pytest.mark.dependency(depends=["test_unsend_to_nursery"])
def test_set_initial_quantity(client, accession_url):
    accession = client.get(accession_url).accession
    accession.processingMethod = "Count"
    accession.initialQuantity = {"quantity": 300, "units": "Seeds"}

    updated = client.put(accession_url, json=accession).accession

    assert updated.state == "Processing"


@pytest.mark.dependency(depends=["test_check_in_accession"])
def test_set_negative_initial_quantity(client, accession_url):
    accession = client.get(accession_url).accession
    accession.processingMethod = "Count"
    accession.initialQuantity = {"quantity": -3, "units": "Seeds"}

    with expect_error():
        client.put(accession_url, json=accession)


@pytest.mark.dependency(depends=["test_check_in_accession"])
def test_set_initial_quantity_with_mismatched_units(client, accession_url):
    accession = client.get(accession_url).accession
    accession.processingMethod = "Weight"
    accession.initialQuantity = {"quantity": 300, "units": "Seeds"}

    with expect_error():
        client.put(accession_url, json=accession)


@pytest.mark.dependency(depends=["test_set_initial_quantity"])
def test_set_drying_dates(client, accession_url):
    accession = client.get(accession_url).accession
    accession.dryingStartDate = "2021-01-01"
    accession.dryingEndDate = "2021-01-01"
    accession.dryingMoveDate = "2021-01-01"
    accession.processingNotes = "Processing Notes"
    accession.processingStaffResponsible = "Staff"

    updated = client.put(accession_url, json=accession).accession

    assert updated.processingNotes == "Processing Notes"
    assert updated.processingStaffResponsible == "Staff"
    assert updated.state == "Dried"


@pytest.mark.dependency(depends=["test_set_drying_dates"])
def test_overdue_drying_summary(client, seed_bank_facility_id):
    response = client.get(f"/api/v1/seedbank/summary/{seed_bank_facility_id}")
    assert response.to_dict() == {
        "activeAccessions": {"current": 1, "lastWeek": 0},
        "species": {"current": 1, "lastWeek": 0},
        "families": {"current": 1, "lastWeek": 0},
        "overduePendingAccessions": 0,
        "overdueProcessedAccessions": 0,
        "overdueDriedAccessions": 1,
        "recentlyWithdrawnAccessions": 0,
    }


@pytest.mark.dependency(depends=["test_overdue_drying_summary"])
def test_set_storage_details(client, accession_url):
    accession = client.get(accession_url).accession
    accession.storageStartDate = "2021-02-04"
    accession.storagePackets = 5
    accession.storageLocation = "Refrigerator 1"
    accession.storageNotes = "Storage Notes"
    accession.storageStaffResponsible = "Storage Staff"

    updated = client.put(accession_url, json=accession).accession

    assert updated.storageLocation == "Refrigerator 1"
    assert updated.storagePackets == 5
    assert updated.storageStartDate == "2021-02-04"
    assert updated.storageNotes == "Storage Notes"
    assert updated.storageStaffResponsible == "Storage Staff"
    assert updated.state == "In Storage"
