def test_set_storage_details(client, accession):
    accession.storageStartDate = "2021-02-04"
    accession.storagePackets = 5
    accession.storageLocation = "Refrigerator 1"
    accession.storageNotes = "Storage Notes"
    accession.storageStaffResponsible = "Storage Staff"

    updated = client.put_accession(accession)

    assert updated.storageLocation == "Refrigerator 1"
    assert updated.storagePackets == 5
    assert updated.storageStartDate == "2021-02-04"
    assert updated.storageNotes == "Storage Notes"
    assert updated.storageStaffResponsible == "Storage Staff"
    assert updated.state == "In Storage"
