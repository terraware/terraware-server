import pytest

from util import expect_error


@pytest.mark.dependency()
def test_set_initial_quantity(client, accession):
    accession.processingMethod = "Count"
    accession.initialQuantity = {"quantity": 300, "units": "Seeds"}

    updated = client.put_accession(accession)

    assert updated.state == "Processing"


def test_set_negative_initial_quantity(client, accession):
    accession.processingMethod = "Count"
    accession.initialQuantity = {"quantity": -3, "units": "Seeds"}

    with expect_error():
        client.put_accession(accession)


def test_set_initial_quantity_with_mismatched_units(client, accession):
    accession.processingMethod = "Weight"
    accession.initialQuantity = {"quantity": 300, "units": "Seeds"}

    with expect_error():
        client.put_accession(accession)


@pytest.mark.dependency(depends=["test_set_initial_quantity"])
def test_set_drying_dates(client, accession):
    accession.dryingStartDate = "2021-01-01"
    accession.dryingEndDate = "2021-01-01"
    accession.dryingMoveDate = "2021-01-01"
    accession.processingNotes = "Processing Notes"
    accession.processingStaffResponsible = "Staff"

    updated = client.put_accession(accession)

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
