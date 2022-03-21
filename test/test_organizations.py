from util import expect_error


def test_create_organization(organization_id):
    assert organization_id is not None


def test_create_organization_with_empty_name(client):
    with expect_error():
        client.post("/api/v1/organizations", {"name": ""})


def test_list_organizations(client, organization_id):
    response = client.get("/api/v1/organizations")
    assert organization_id in [item.id for item in response.organizations]


def test_get_organization(client, organization_id, organization_name):
    response = client.get(f"/api/v1/organizations/{organization_id}")
    assert response.organization.name == organization_name
