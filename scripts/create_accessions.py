#!/usr/bin/env python3
import argparse
from datetime import date, timedelta
from example_values import FIRST_NAMES
import json
import random
from random import randint
from typing import Dict, List, Optional
from client import TerrawareClient, add_terraware_args, client_from_args


def generate_notes() -> Optional[str]:
    very = " ".join(["very" for _ in range(0, randint(10, 200))])
    return random.choice(
        [
            None,
            None,
            None,
            "Some notes to start things off",
            "These seeds are fantastic",
            "These seeds are beautiful",
            "These seeds make me want to sing",
            "Rats were eating these seeds",
            f"This is a {very} long note.",
        ]
    )


def generate_recent_date() -> Optional[date]:
    if randint(0, 5) == 0:
        return None
    return date.today() - timedelta(days=randint(0, 90))


def generate_test_result(recording_date, max_seeds_germinated: float) -> Dict:
    return {
        "recordingDate": str(recording_date),
        "seedsGerminated": randint(0, int(max_seeds_germinated)),
    }


def generate_source() -> Optional[str]:
    return random.choice(
        [
            None,
            "Web",
            "Seed Collector App",
        ]
    )


def generate_person_name() -> str:
    return random.choice(FIRST_NAMES)


def generate_viability_test_v2(received_date, remaining_quantity: Dict) -> Dict:
    if remaining_quantity["units"] == "Seeds":
        seeds_tested = randint(1, remaining_quantity["quantity"])
    else:
        seeds_tested = randint(10, 500)

    test_type_substrates = {
        "Lab": [
            "Agar",
            "Paper",
            "Sand",
            "Nursery Media",
            "Other",
        ],
        "Nursery": [
            "Media Mix",
            "Soil",
            "Sand",
            "Moss",
            "Perlite/Vermiculite",
            "Other",
        ],
    }

    test_type = random.choice(list(test_type_substrates.keys()))
    seed_type = random.choice([None, "Fresh", "Stored"])
    substrate = random.choice(test_type_substrates[test_type])
    treatment = random.choice(
        [None, "Chemical", "Light", "Other", "Scarify", "Soak", "Stratification"]
    )

    start_date = received_date + timedelta(days=randint(0, 2))
    germination_count = randint(0, 3)

    test_results = []
    recording_date = start_date

    for _ in range(0, germination_count):
        test_results.append(
            generate_test_result(recording_date, seeds_tested / germination_count)
        )
        recording_date += timedelta(days=randint(1, 3))

    return {
        "notes": generate_notes(),
        "seedsTested": seeds_tested,
        "seedType": seed_type,
        "startDate": str(start_date) if start_date else None,
        "substrate": substrate,
        "testResults": test_results or None,
        "testType": test_type,
        "treatment": treatment,
    }


def generate_coordinates() -> Dict:
    return {
        "latitude": float(randint(100, 110)),
        "longitude": float(randint(50, 60)),
        "accuracy": float(random.choice([50, 75, 100])),
    }


def generate_bag_numbers() -> Optional[List[str]]:
    if randint(0, 5) > 0:
        return list(
            [str(randint(100000000, 999999999)) for _ in range(0, randint(1, 10))]
        )
    else:
        return None


def generate_collection_site_name() -> Optional[str]:
    return random.choice(
        [
            None,
            f"Location {randint(1, 100)}",
            "West edge of the woods",
            "Right next to the seed bank",
            "Down the road a bit",
        ]
    )


def generate_plant_id() -> Optional[str]:
    # No clue what this will actually look like, so just generate a number
    return str(randint(100000, 999999)) if randint(0, 4) > 0 else None


def generate_quantity(unit_type: Optional[str] = None) -> Dict:
    if unit_type is None:
        unit_type = random.choice(["Count", "Weight"])

    if unit_type == "Weight":
        return {
            "quantity": randint(1, 100),
            "units": random.choice(
                ["Grams", "Kilograms", "Pounds", "Milligrams", "Ounces"]
            ),
        }
    else:
        return {"quantity": randint(1, 100), "units": "Seeds"}


def generate_collectors() -> Optional[List[str]]:
    num_collectors = randint(1, 3) if randint(0, 2) == 0 else 0
    return [generate_person_name() for _ in range(num_collectors)] or None


def generate_accession(facility_id: int, species_ids: List[int]) -> Dict:
    bag_numbers = generate_bag_numbers()
    geolocations = (
        list([generate_coordinates() for _ in bag_numbers]) if bag_numbers else None
    )

    collected_date = generate_recent_date()
    received_date = (
        collected_date + timedelta(days=randint(0, 3)) if collected_date else None
    )

    plants_collected_from = randint(1, 10)

    species_id = random.choice(species_ids) if randint(1, 5) > 1 else None

    return {
        "bagNumbers": bag_numbers,
        "collectedDate": str(collected_date) if collected_date else None,
        "collectionSiteCoordinates": geolocations,
        "collectionSiteLandowner": generate_person_name(),
        "collectionSiteName": generate_collection_site_name(),
        "collectionSiteNotes": generate_notes(),
        "collectors": generate_collectors(),
        "facilityId": facility_id,
        "founderId": generate_plant_id(),
        "plantsCollectedFrom": plants_collected_from,
        "receivedDate": str(received_date) if received_date else None,
        "source": generate_source(),
        "speciesId": species_id,
    }


def generate_accession_update(accession: Dict) -> Dict:
    remaining_quantity = generate_quantity()

    # If receivedDate is set, we will be creating a viability test, so we'll need
    # subset weight/count if the remaining quantity is weight-based.
    if randint(0, 3) == 0 or (
        "receivedDate" in accession and remaining_quantity["units"] != "Seeds"
    ):
        subset_fields = {
            "subsetCount": randint(1, 20),
            "subsetWeight": generate_quantity("Weight"),
        }
    else:
        subset_fields = {}

    return {
        **accession,
        **subset_fields,
        "remainingQuantity": remaining_quantity,
    }


def create_accession(
    client: TerrawareClient, facility_id: int, species_ids: List[int]
) -> Dict:
    create_payload = generate_accession(facility_id, species_ids)

    try:
        initial = client.create_accession(create_payload)
    except Exception as ex:
        print("Unable to create accession. Payload:")
        print(json.dumps(create_payload, indent=2))
        raise ex

    accession_id = initial["id"]

    client.check_in_accession(accession_id)

    update_payload = generate_accession_update(initial)

    try:
        updated = client.update_accession(accession_id, update_payload)
    except Exception as ex:
        print(f"Unable to update accession {accession_id}. Payload:")
        print(json.dumps(update_payload, indent=2))
        raise ex

    if "receivedDate" in updated:
        received_date = date.fromisoformat(updated["receivedDate"])
        viability_test_payload = generate_viability_test_v2(
            received_date, updated["remainingQuantity"]
        )

        try:
            updated = client.create_viability_test(accession_id, viability_test_payload)
        except Exception as ex:
            print(
                f"Unable to create viability test for accession {accession_id}. Payload:"
            )
            print(json.dumps(viability_test_payload, indent=2))
            raise ex

    return updated


def main():
    parser = argparse.ArgumentParser("Generate dummy accession data")
    parser.add_argument(
        "--facility",
        "-f",
        type=int,
        help="Generate accessions at this facility. Default is to pick the first seed bank "
        + "facility accessible by the user.",
    )
    parser.add_argument(
        "--number", "-n", type=int, default=10, help="Number of accessions to create."
    )
    parser.add_argument(
        "--verbose",
        "-v",
        action="store_true",
        help="Show populated accession data as returned by the server.",
    )
    add_terraware_args(parser)
    args = parser.parse_args()

    client = client_from_args(args)

    if args.facility:
        facility_id = args.facility
    else:
        facility_id = [
            entry["id"]
            for entry in client.list_facilities()
            if entry["type"] == "Seed Bank"
        ][0]

    organization_id = client.get_facility(facility_id)["organizationId"]

    species_ids = [species["id"] for species in client.list_species(organization_id)]

    for _ in range(0, args.number):
        accession = create_accession(client, facility_id, species_ids)
        if args.verbose:
            print(json.dumps(accession, indent=2))
        else:
            print(f"{accession['id']} {accession['accessionNumber']}")


if __name__ == "__main__":
    main()
