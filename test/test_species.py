from http import HTTPStatus

from util import expect_error

species_name = "Test Species"


def test_create_species_requires_name(client, organization_id):
    with expect_error():
        client.post("/api/v1/species", {"organizationId": organization_id, "name": ""})


def test_create_species(client, organization_id):
    response = client.post(
        "/api/v1/species",
        {"organizationId": organization_id, "name": species_name},
    )
    assert response.id > 0


def test_create_species_rejects_duplicate_name(client, organization_id):
    with expect_error(HTTPStatus.CONFLICT):
        response = client.post(
            "/api/v1/species",
            {"organizationId": organization_id, "name": species_name},
        )


def test_seed_summary(client, seed_bank_facility_id):
    response = client.get(f"/api/v1/seedbank/summary/{seed_bank_facility_id}")
    zero_counts = {"current": 0, "lastWeek": 0}
    assert response.to_dict() == {
        "activeAccessions": zero_counts,
        "species": {"current": 1, "lastWeek": 0},
        "families": zero_counts,
        "overduePendingAccessions": 0,
        "overdueProcessedAccessions": 0,
        "overdueDriedAccessions": 0,
        "recentlyWithdrawnAccessions": 0,
    }
