#!/usr/bin/env python3
import argparse
from datetime import date, datetime, timedelta
import dateutil
from example_values import TREE_SPECIES, FIRST_NAMES
import json
from pprint import pprint
import random
from random import randint
import requests
import sys
import time
from typing import Dict, Iterable, List, Optional


def has_value(dict_elem) -> bool:
    return dict_elem[1] is not None


def remove_none(dictionary):
    return dict(filter(has_value, dictionary.items()))


def generate_device_info() -> Optional[Dict]:
    if randint(0, 3) == 0:
        return None

    return {
        "appBuild": random.choice(["1.0", "1.2", None]),
        "appName": "Seed Collector",
        "brand": random.choice(["Google", "Huawei", "Samsung"]),
        "model": "model",
        "name": "example device",
        "osType": "Android",
        "osVersion": "7.1.1",
        "uniqueId": random.choice(["uid1", "uid2", "uid3"]),
    }


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


def generate_staff_responsible() -> Optional[str]:
    return random.choice(FIRST_NAMES)


def generate_germination_test(received_date) -> Dict:
    start_date = received_date + timedelta(days=randint(0, 2))
    germination_count = randint(0, 3)

    seeds_sown = randint(10, 500)
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
            f"Location {randint(1,100)}",
            "West edge of the woods",
            "Right next to the seed bank",
            "Down the road a bit",
        ]
    )


def generate_founder_id() -> Optional[str]:
    # No clue what this will actually look like, so just generate a number
    return str(randint(100000, 999999)) if randint(0, 4) > 0 else None


def generate_accession() -> Dict:
    primary_collector = generate_staff_responsible()
    secondary_collectors = (
        [generate_staff_responsible()] if randint(0, 4) == 0 else None
    )
    if secondary_collectors and secondary_collectors[0] == primary_collector:
        secondary_collectors = None

    bag_numbers = generate_bag_numbers()
    geolocations = (
        list([generate_geolocation() for x in bag_numbers]) if bag_numbers else None
    )
    germination_test_types = [generate_test_type()]

    species = generate_species()
    family = generate_family(species)

    collected_date = generate_recent_date()
    received_date = (
        collected_date + timedelta(days=randint(0, 3)) if collected_date else None
    )
    germination_tests = (
        [generate_germination_test(received_date)] if received_date else None
    )

    return {
        "bagNumbers": bag_numbers,
        "collectedDate": str(collected_date) if collected_date else None,
        "deviceInfo": generate_device_info(),
        "endangered": randint(0, 1) == 1,
        "environmentalNotes": generate_notes(),
        "family": family,
        "fieldNotes": generate_notes(),
        "founderId": generate_founder_id(),
        "geolocations": geolocations,
        "germinationTestTypes": germination_test_types,
        "germinationTests": germination_tests,
        "landowner": generate_staff_responsible(),
        "numberOfTrees": randint(1, 10),
        "primaryCollector": primary_collector,
        "rare": randint(0, 1) == 1,
        "receivedDate": str(received_date) if received_date else None,
        "secondaryCollectors": secondary_collectors,
        "siteLocation": generate_site_location(),
        "species": species,
    }


def create_accession(server: str) -> Dict:
    payload = generate_accession()

    return requests.post(f"{server}/api/v1/seedbank/accession", json=payload).json()[
        "accession"
    ]


def main():
    parser = argparse.ArgumentParser("Generate dummy accession data")
    parser.add_argument(
        "--number", "-n", type=int, default=10, help="Number of accessions to create."
    )
    parser.add_argument(
        "--verbose",
        "-v",
        action="store_true",
        help="Show populated accession data as returned by the server.",
    )
    parser.add_argument("server", nargs="?", default="http://localhost:8080")
    args = parser.parse_args()

    for n in range(0, args.number):
        accession = create_accession(args.server)
        if args.verbose:
            pprint(accession)
        else:
            print(accession["accessionNumber"])


if __name__ == "__main__":
    main()
