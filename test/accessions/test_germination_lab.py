import pytest


@pytest.fixture(scope="module", autouse=True)
def setup_accession(client, accession_id, accession_url):
    client.post(f"{accession_url}/checkIn", json=None)

    accession = client.get_accession(accession_id)

    accession.processingMethod = "Count"
    accession.initialQuantity = {"quantity": 1000, "units": "Seeds"}
    accession.germinationTestTypes = ["Lab"]

    client.put_accession(accession)


def test_set_germination_test_types(client, accession):
    assert accession.germinationTestTypes == ["Lab"]


@pytest.mark.dependency()
def test_create_first_test(client, accession):
    test_details = {
        "startDate": "2021-02-09",
        "substrate": "Paper Petri Dish",
        "treatment": "Scarify",
        "seedsSown": 100,
        "notes": "A lab test note",
        "staffResponsible": "Staff 1",
        "testType": "Lab",
    }

    accession.germinationTests = [test_details]

    updated = client.put_accession(accession)

    expected = [{
        **test_details,
        "id": updated.germinationTests[0].id,
        "remainingQuantity": {"quantity": 900, "units": "Seeds"},
    }]

    assert updated.germinationTests == expected


@pytest.mark.dependency(depends=["test_create_first_test"])
def test_modify_test(client, accession):
    accession.germinationTests[0].substrate = "Nursery Media"
    del accession.germinationTests[0].notes

    updated = client.put_accession(accession)

    assert updated.germinationTests == accession.germinationTests


@pytest.mark.dependency(depends=["test_create_first_test"])
def test_create_later_test(client, accession):
    test_details = {
        "startDate": "2021-02-12",
        "seedType": "Stored",
        "substrate": "Agar Petri Dish",
        "treatment": "Soak",
        "seedsSown": 200,
        "testType": "Lab",
    }

    accession.germinationTests.append(test_details)

    updated = client.put_accession(accession)

    expected = [accession.germinationTests[0], {
        **test_details,
        "id": updated.germinationTests[1].id,
        "remainingQuantity": {"quantity": 700, "units": "Seeds"},
    }]

    assert updated.germinationTests == expected


@pytest.mark.dependency(depends=["test_create_later_test"])
def test_remaining_quantity_is_based_on_date(client, accession):
    test_details = {
        "startDate": "2021-02-01",
        "seedType": "Fresh",
        "substrate": "Other",
        "treatment": "Other",
        "seedsSown": 50,
        "testType": "Lab",
    }

    accession.germinationTests.append(test_details)

    updated = client.put_accession(accession)

    expected = [
        {
            **accession.germinationTests[0],
            "remainingQuantity": {"quantity": 850, "units": "Seeds"},
        },
        {
            **accession.germinationTests[1],
            "remainingQuantity": {"quantity": 650, "units": "Seeds"},
        },
        {
            **test_details,
            "id": updated.germinationTests[2].id,
            "remainingQuantity": {"quantity": 950, "units": "Seeds"},
        },
    ]

    assert updated.germinationTests == expected


@pytest.mark.dependency(depends=["test_remaining_quantity_is_based_on_date"])
def test_delete_test(client, accession):
    del accession.germinationTests[2]

    updated = client.put_accession(accession)

    expected = [
        {
            **accession.germinationTests[0],
            "remainingQuantity": {"quantity": 900, "units": "Seeds"},
        },
        {
            **accession.germinationTests[1],
            "remainingQuantity": {"quantity": 700, "units": "Seeds"},
        },
    ]

    assert updated.germinationTests == expected
