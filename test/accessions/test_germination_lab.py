import pytest


@pytest.fixture(scope="module", autouse=True)
def check_in_accession(client, accession_id, accession_url):
    client.post(f"{accession_url}/checkIn", json=None)

    accession = client.get_accession(accession_id)

    accession.processingMethod = "Count"
    accession.initialQuantity = {"quantity": 1000, "units": "Seeds"}
    accession.germinationTestTypes = ["Lab"]

    return client.put_accession(accession)


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

    expected = [
        {
            **test_details,
            "id": updated.germinationTests[0].id,
            "remainingQuantity": {"quantity": 900, "units": "Seeds"},
        }
    ]

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

    expected = [
        accession.germinationTests[0],
        {
            **test_details,
            "id": updated.germinationTests[1].id,
            "remainingQuantity": {"quantity": 700, "units": "Seeds"},
        },
    ]

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


@pytest.mark.dependency(depends=["test_delete_test"])
def test_add_first_germination(client, accession):
    accession.germinationTests[0].germinations = [
        {"seedsGerminated": 10, "recordingDate": "2021-02-09"}
    ]

    updated = client.put_accession(accession)

    expected = [
        {
            **accession.germinationTests[0],
            "totalSeedsGerminated": 10,
            "totalPercentGerminated": 10,
        },
        accession.germinationTests[1],
    ]

    assert updated.germinationTests == expected
    assert updated.latestViabilityPercent == 10
    assert updated.totalViabilityPercent == int(10 / 300 * 100)


@pytest.mark.dependency(depends=["test_add_first_germination"])
def test_add_second_germination(client, accession):
    accession.germinationTests[0].germinations.append(
        {"seedsGerminated": 15, "recordingDate": "2021-05-09"}
    )

    updated = client.put_accession(accession)

    expected = [
        {
            **accession.germinationTests[0],
            "germinations": [
                # Germinations are returned in reverse recordingDate order
                accession.germinationTests[0].germinations[1],
                accession.germinationTests[0].germinations[0],
            ],
            "totalSeedsGerminated": 25,
            "totalPercentGerminated": 25,
        },
        accession.germinationTests[1],
    ]

    assert updated.germinationTests == expected
    assert updated.latestViabilityPercent == 25
    assert updated.totalViabilityPercent == int(25 / 300 * 100)


@pytest.mark.dependency(depends=["test_add_second_germination"])
def test_modify_germination(client, accession):
    accession.germinationTests[0].germinations[0].seedsGerminated = 25

    updated = client.put_accession(accession)

    expected = [
        {
            **accession.germinationTests[0],
            "totalSeedsGerminated": 35,
            "totalPercentGerminated": 35,
        },
        accession.germinationTests[1],
    ]

    assert updated.germinationTests == expected
    assert updated.latestViabilityPercent == 35
    assert updated.totalViabilityPercent == int(35 / 300 * 100)


@pytest.mark.dependency(depends=["test_modify_germination"])
def test_delete_germination(client, accession):
    del accession.germinationTests[0].germinations[1]

    updated = client.put_accession(accession)

    expected = [
        {
            **accession.germinationTests[0],
            "totalSeedsGerminated": 25,
            "totalPercentGerminated": 25,
        },
        accession.germinationTests[1],
    ]

    assert list(updated.germinationTests) == expected
    assert updated.latestViabilityPercent == 25
    assert updated.totalViabilityPercent == int(25 / 300 * 100)


@pytest.mark.dependency(depends=["test_delete_germination"])
def test_add_cut_test(client, accession):
    accession.cutTestSeedsFilled = 15
    accession.cutTestSeedsEmpty = 50
    accession.cutTestSeedsCompromised = 10

    updated = client.put_accession(accession)

    assert updated.cutTestSeedsFilled == 15
    assert updated.cutTestSeedsEmpty == 50
    assert updated.cutTestSeedsCompromised == 10

    # (Cut test filled + germinated total) / (cut test total + germination test sown total)
    assert updated.totalViabilityPercent == int((25 + 15) / (300 + 75) * 100)


@pytest.mark.dependency(depends=["test_add_cut_test"])
def test_modify_cut_test(client, accession):
    accession.cutTestSeedsFilled = 500

    updated = client.put_accession(accession)

    assert updated.cutTestSeedsFilled == 500
    assert updated.cutTestSeedsEmpty == 50
    assert updated.cutTestSeedsCompromised == 10

    # (Cut test filled + germinated total) / (cut test total + germination test sown total)
    assert updated.totalViabilityPercent == int((25 + 500) / (300 + 560) * 100)
