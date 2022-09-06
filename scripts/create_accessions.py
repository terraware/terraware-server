#!/usr/bin/env python3
import argparse
from datetime import date, datetime, timedelta
from example_values import TREE_SPECIES, FIRST_NAMES
import json
import random
from random import randint
from typing import Dict, Iterable, List, Optional
from client import TerrawareClient, add_terraware_args, client_from_args


def has_value(dict_elem) -> bool:
    return dict_elem[1] is not None


def remove_none(dictionary):
    return dict(filter(has_value, dictionary.items()))


def generate_endangered() -> Optional[str]:
    return random.choice([None, "Yes", "No", "Unsure"])


def generate_rare() -> Optional[str]:
    return random.choice([None, "Yes", "No", "Unsure"])


def generate_species() -> Optional[str]:
    return random.choice(TREE_SPECIES + [None])


def generate_family(species) -> Optional[str]:
    if species is not None and randint(0, 5) > 0:
        return species.split(" ")[0]
    else:
        return None


def generate_notes() -> Optional[str]:
    very = " ".join(["very" for how_very in range(0, randint(10, 200))])
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


def generate_germination(recording_date, max_seeds_germinated: float) -> Dict:
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


def generate_staff_responsible() -> Optional[str]:
    return random.choice(FIRST_NAMES)


def generate_viability_test(received_date, remaining_quantity: Dict) -> Dict:
    if remaining_quantity["units"] == "Seeds":
        seeds_sown = randint(1, remaining_quantity["quantity"])
        remaining = {}
    else:
        seeds_sown = randint(10, 500)
        remaining = {
            "remainingQuantity": {
                "quantity": randint(1, remaining_quantity["quantity"]),
                "units": remaining_quantity["units"],
            }
        }

    start_date = received_date + timedelta(days=randint(0, 2))
    germination_count = randint(0, 3)

    germinations = []
    germination_date = start_date

    for i in range(0, germination_count):
        germinations.append(
            generate_germination(germination_date, seeds_sown / germination_count)
        )
        germination_date += timedelta(days=randint(1, 3))

    return {
        "testType": "Lab",
        "startDate": str(start_date) if start_date else None,
        "seedType": "Fresh",
        "substrate": "Nursery Media",
        "treatment": "Soak",
        "notes": generate_notes(),
        "staffResponsible": generate_staff_responsible(),
        "seedsSown": seeds_sown,
        "germinations": germinations or None,
        **remaining,
    }


def generate_test_type() -> str:
    return random.choice(["Lab", "Nursery"])


def generate_geolocation() -> Dict:
    return {
        "latitude": float(randint(100, 110)),
        "longitude": float(randint(50, 60)),
        "accuracy": float(random.choice([50, 75, 100])),
    }


def generate_bag_numbers() -> Optional[List[str]]:
    if randint(0, 5) > 0:
        return list(
            [str(randint(100000000, 999999999)) for x in range(0, randint(1, 10))]
        )
    else:
        return None


def generate_site_location() -> Optional[str]:
    return random.choice(
        [
            None,
            f"Location {randint(1, 100)}",
            "West edge of the woods",
            "Right next to the seed bank",
            "Down the road a bit",
        ]
    )


def generate_source_plant_origin() -> Optional[str]:
    return random.choice([None, "Wild", "Outplant"])


def generate_founder_id() -> Optional[str]:
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


def generate_initial_quantity() -> Dict:
    quantity = generate_quantity()
    return {
        "processingMethod": "Count" if quantity["units"] == "Seeds" else "Weight",
        "initialQuantity": generate_quantity(unit_type),
    }


def generate_accession(facility_id: int) -> Dict:
    num_collectors = randint(1, 3) if randint(0, 2) == 0 else 0
    collectors = [generate_staff_responsible() for n in range(num_collectors)] or None

    bag_numbers = generate_bag_numbers()
    geolocations = (
        list([generate_geolocation() for x in bag_numbers]) if bag_numbers else None
    )
    viability_test_types = [generate_test_type()]

    species = generate_species()
    family = generate_family(species)

    collected_date = generate_recent_date()
    received_date = (
        collected_date + timedelta(days=randint(0, 3)) if collected_date else None
    )

    return {
        "bagNumbers": bag_numbers,
        "collectedDate": str(collected_date) if collected_date else None,
        "collectors": collectors,
        "endangered": generate_endangered(),
        "environmentalNotes": generate_notes(),
        "facilityId": facility_id,
        "family": family,
        "fieldNotes": generate_notes(),
        "founderId": generate_founder_id(),
        "geolocations": geolocations,
        "landowner": generate_staff_responsible(),
        "numberOfTrees": randint(1, 10),
        "rare": generate_rare(),
        "receivedDate": str(received_date) if received_date else None,
        "siteLocation": generate_site_location(),
        "source": generate_source(),
        "sourcePlantOrigin": generate_source_plant_origin(),
        "species": species,
        "viabilityTestTypes": viability_test_types,
    }


def generate_accession_v2(facility_id: int, species_ids: List[int]) -> Dict:
    num_collectors = randint(1, 3) if randint(0, 2) == 0 else 0
    collectors = [generate_staff_responsible() for n in range(num_collectors)] or None

    bag_numbers = generate_bag_numbers()
    geolocations = (
        list([generate_geolocation() for x in bag_numbers]) if bag_numbers else None
    )
    viability_test_types = [generate_test_type()]

    species = generate_species()
    family = generate_family(species)

    collected_date = generate_recent_date()
    received_date = (
        collected_date + timedelta(days=randint(0, 3)) if collected_date else None
    )

    plants_collected_from_min = randint(1, 10)
    plants_collected_from_max = randint(plants_collected_from_min, 10)

    species_id = random.choice(species_ids) if randint(1, 5) > 1 else None

    return {
        "bagNumbers": bag_numbers,
        "collectedDate": str(collected_date) if collected_date else None,
        "collectionSiteCoordinates": geolocations,
        "collectionSiteLandowner": generate_staff_responsible(),
        "collectionSiteName": generate_site_location(),
        "collectionSiteNotes": generate_notes(),
        "collectors": collectors,
        "facilityId": facility_id,
        "founderId": generate_founder_id(),
        "plantsCollectedFromMax": plants_collected_from_max,
        "plantsCollectedFromMin": plants_collected_from_min,
        "receivedDate": str(received_date) if received_date else None,
        "source": generate_source(),
        "speciesId": species_id,
        "viabilityTestTypes": viability_test_types,
    }


def generate_accession_update(accession: Dict) -> Dict:
    quantity_fields = generate_initial_quantity()

    received_date = (
        date.fromisoformat(accession["receivedDate"])
        if "receivedDate" in accession
        else None
    )
    viability_tests = (
        [generate_viability_test(received_date, quantity_fields["initialQuantity"])]
        if received_date
        else None
    )

    return {**accession, **quantity_fields, "viabilityTests": viability_tests}


def generate_accession_update_v2(accession: Dict) -> Dict:
    remaining_quantity = generate_quantity()

    received_date = (
        date.fromisoformat(accession["receivedDate"])
        if "receivedDate" in accession
        else None
    )
    viability_tests = (
        [generate_viability_test(received_date, remaining_quantity)]
        if received_date
        else None
    )

    return {
        **accession,
        "remainingQuantity": remaining_quantity,
        "viabilityTests": viability_tests,
    }


def create_accession(
    client: TerrawareClient, facility_id: int, species_ids: List[int]
) -> Dict:
    create_payload = generate_accession(facility_id)

    try:
        initial = client.create_accession(create_payload, version=1)
    except Exception as ex:
        print("Unable to create accession. Payload:")
        print(json.dumps(create_payload, indent=2))
        raise ex

    accession_id = initial["id"]

    client.check_in_accession(accession_id)

    update_payload = generate_accession_update(initial)

    try:
        updated = client.update_accession(accession_id, update_payload, version=1)
    except Exception as ex:
        print(f"Unable to update accession {accession_id}. Payload:")
        print(json.dumps(update_payload, indent=2))
        raise ex

    return updated


def create_accession_v2(
    client: TerrawareClient, facility_id: int, species_ids: List[int]
) -> Dict:
    create_payload = generate_accession_v2(facility_id, species_ids)

    try:
        initial = client.create_accession(create_payload, version=2)
    except Exception as ex:
        print("Unable to create accession. Payload:")
        print(json.dumps(create_payload, indent=2))
        raise ex

    accession_id = initial["id"]

    client.check_in_accession(accession_id)

    update_payload = generate_accession_update_v2(initial)

    try:
        updated = client.update_accession(accession_id, update_payload, version=2)
    except Exception as ex:
        print(f"Unable to update accession {accession_id}. Payload:")
        print(json.dumps(update_payload, indent=2))
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
    parser.add_argument(
        "--version", "-V", type=int, help="API version to use (default 1)", default=1
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

    for n in range(0, args.number):
        if args.version == 2:
            accession = create_accession_v2(client, facility_id, species_ids)
        else:
            accession = create_accession(client, facility_id, species_ids)
        if args.verbose:
            print(json.dumps(accession, indent=2))
        else:
            print(f"{accession['id']} {accession['accessionNumber']}")


if __name__ == "__main__":
    main()
