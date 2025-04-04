#!/usr/bin/env python3
import argparse
import random
import time
from datetime import datetime, timezone
from math import floor

from client import add_terraware_args, client_from_args


def isoformat(timestamp: int) -> str:
    return (
        datetime.fromtimestamp(timestamp, timezone.utc)
        .isoformat()
        .replace("+00:00", "Z")
    )


def random_decimal(min_val, max_val, precision=1):
    """Generate a random decimal with specified precision."""
    value = random.uniform(min_val, max_val)
    return round(value, precision)


def get_random_species(species_ids):
    """Helper function to randomly get a species ID or name.

    Returns a tuple of (species_id, species_name) with one being None
    based on a random choice.
    """
    if random.randint(1, 10) > 2:  # 80% chance of known species
        species_id = random.choice(species_ids)
        species_name = None
    else:
        species_id = None
        species_name = f"Unknown Species {random.randint(1, 10)}"

    return species_id, species_name


def generate_species_payload(species_ids):
    """Generate a random species payload."""
    species_id, species_name = get_random_species(species_ids)

    return {
        "commonName": None,
        "isInvasive": random.choice([True, False]),
        "isThreatened": random.choice([True, False]),
        "scientificName": species_name,
        "speciesId": species_id,
    }


def generate_quadrat_species_payload(species_ids):
    """Generate a random quadrat species payload."""
    species_id, species_name = get_random_species(species_ids)

    return {
        "abundancePercent": random.randint(5, 95),
        "speciesId": species_id,
        "speciesName": species_name,
    }


def generate_tree_payload(species_ids):
    """Generate a random tree payload."""
    tree_type = random.choice(["shrub", "tree"])
    species_id, species_name = get_random_species(species_ids)

    if tree_type == "shrub":
        return {
            "growthForm": "shrub",
            "description": f"Shrub {random.randint(1, 100)}",
            "isDead": random.choices([True, False], weights=[1, 9])[
                0
            ],  # 10% chance of being dead
            "speciesId": species_id,
            "speciesName": species_name,
            "shrubDiameter": random.randint(5, 50),
        }
    else:
        num_trunks = random.choices([1, random.randint(2, 5)], weights=[8, 2])[
            0
        ]  # 20% chance of multiple trunks
        return {
            "growthForm": "tree",
            "speciesId": species_id,
            "speciesName": species_name,
            "trunks": [
                {
                    "diameterAtBreastHeight": random_decimal(5, 100, 1),
                    "height": random_decimal(1, 20, 1),
                    "pointOfMeasurement": random_decimal(
                        1.3, 1.5, 1
                    ),  # Usually around 1.3-1.5m
                    "description": f"Trunk {i+1}",
                    "isDead": random.choices([True, False], weights=[1, 9])[
                        0
                    ],  # 10% chance of being dead
                }
                for i in range(num_trunks)
            ],
        }


def generate_biomass_observation_payload(planting_site_id, forest_type, species_ids):
    """Generate a random biomass observation payload."""
    # Random timestamp within the last day
    current_time = int(time.time())
    observed_time = isoformat(current_time - random.randint(0, 86400))

    # Generate master list of species entries for the observation
    num_species = random.randint(5, 15)  # Increased to ensure we have enough species

    # Create a set of unique species identifiers to avoid duplicates
    unique_species_keys = set()
    master_species_list = []

    # Generate the master species list with unique entries
    while len(master_species_list) < num_species:
        species_entry = generate_species_payload(species_ids)

        # Create a unique key based on either ID or name
        key = (
            species_entry["speciesId"]
            if species_entry["speciesId"] is not None
            else species_entry["scientificName"]
        )

        # Only add if this is a new species
        if key is not None and key not in unique_species_keys:
            unique_species_keys.add(key)
            master_species_list.append(species_entry)

    # Create lookup dictionaries for the species data (might be useful for future extensions)
    species_by_id = {
        s["speciesId"]: s for s in master_species_list if s["speciesId"] is not None
    }
    species_by_name = {
        s["scientificName"]: s
        for s in master_species_list
        if s["scientificName"] is not None
    }

    # Generate quadrats (usually at 4 positions: NW, NE, SW, SE)
    quadrats = []
    for position in [
        "NorthwestCorner",
        "NortheastCorner",
        "SoutheastCorner",
        "SouthwestCorner",
    ]:
        if random.random() < 0.9:  # 90% chance of including each position
            # Maximum number of species is the smaller of 5 or the total number in the master list
            max_species = min(5, len(master_species_list))
            num_quadrat_species = random.randint(1, max_species)

            # Randomly select species from the master list (without replacement for this quadrat)
            selected_species_entries = random.sample(
                master_species_list, num_quadrat_species
            )
            quadrat_species = []

            # Create the quadrat species entries
            for species_entry in selected_species_entries:
                # Only include either speciesId or speciesName (not both)
                if species_entry["speciesId"] is not None:
                    quadrat_species.append(
                        {
                            "abundancePercent": random.randint(5, 95),
                            "speciesId": species_entry["speciesId"],
                            "speciesName": None,
                        }
                    )
                else:
                    quadrat_species.append(
                        {
                            "abundancePercent": random.randint(5, 95),
                            "speciesId": None,
                            "speciesName": species_entry["scientificName"],
                        }
                    )

            quadrats.append(
                {
                    "description": f"Quadrat at {position}",
                    "position": position,
                    "species": quadrat_species,
                }
            )

    # Generate trees
    num_trees = random.randint(3, 15)
    trees = []

    for _ in range(num_trees):
        tree_type = random.choice(["shrub", "tree"])
        species_entry = random.choice(master_species_list)

        # Only include either speciesId or speciesName (not both)
        if species_entry["speciesId"] is not None:
            species_id = species_entry["speciesId"]
            species_name = None
        else:
            species_id = None
            species_name = species_entry["scientificName"]

        if tree_type == "shrub":
            trees.append(
                {
                    "growthForm": "shrub",
                    "description": f"Shrub {random.randint(1, 100)}",
                    "isDead": random.choices([True, False], weights=[1, 9])[
                        0
                    ],  # 10% chance of being dead
                    "speciesId": species_id,
                    "speciesName": species_name,
                    "shrubDiameter": random.randint(5, 50),
                }
            )
        else:
            num_trunks = random.choices([1, random.randint(2, 5)], weights=[8, 2])[
                0
            ]  # 20% chance of multiple trunks
            trees.append(
                {
                    "growthForm": "tree",
                    "speciesId": species_id,
                    "speciesName": species_name,
                    "trunks": [
                        {
                            "diameterAtBreastHeight": random_decimal(5, 100, 1),
                            "height": random_decimal(1, 20, 1),
                            "pointOfMeasurement": random_decimal(
                                1.3, 1.5, 1
                            ),  # Usually around 1.3-1.5m
                            "description": f"Trunk {i+1}",
                            "isDead": random.choices([True, False], weights=[1, 9])[
                                0
                            ],  # 10% chance of being dead
                        }
                        for i in range(num_trunks)
                    ],
                }
            )

    # Observable conditions from the enum
    observable_conditions = [
        "AnimalDamage",
        "FastGrowth",
        "FavorableWeather",
        "Fungus",
        "Pests",
        "SeedProduction",
        "UnfavorableWeather",
    ]

    # Base payload
    payload = {
        "biomassMeasurements": {
            "description": f"Observation {datetime.now().strftime('%Y-%m-%d')}",
            "forestType": forest_type,
            "herbaceousCoverPercent": random.randint(10, 90),
            "smallTreeCountLow": random.randint(5, 20),
            "smallTreeCountHigh": random.randint(25, 50),
            "soilAssessment": random.choice(
                [
                    "Healthy with good moisture",
                    "Dry and compacted",
                    "Rich in organic matter",
                    "Sandy with poor nutrients",
                    "Clay-like with moderate drainage",
                ]
            ),
            "quadrats": quadrats,
            "species": master_species_list,
            "trees": trees,
        },
        "observationType": "Biomass Measurements",
        "plantingSiteId": planting_site_id,
        "observedTime": observed_time,
        "swCorner": {
            "type": "Point",
            "coordinates": [random.uniform(-180, 180), random.uniform(-90, 90)],
        },
        "conditions": random.sample(observable_conditions, k=random.randint(1, 3)),
        "notes": f"Biomass observation on {datetime.now().strftime('%Y-%m-%d')}",
    }

    # Add mangrove-specific fields if needed
    if forest_type == "Mangrove":
        payload["biomassMeasurements"].update(
            {
                "ph": random_decimal(6.0, 8.5, 1),
                "salinity": random_decimal(15, 35, 1),
                "tide": random.choice(["High", "Low"]),
                "tideTime": observed_time,
                "waterDepth": random.randint(10, 100),
            }
        )

    return payload


def main():
    parser = argparse.ArgumentParser("Complete ad-hoc biomass observations")
    parser.add_argument(
        "--organization",
        "-O",
        type=int,
        help="Choose from this organization. Default is the current user's lowest-numbered organization.",
    )
    parser.add_argument(
        "--site",
        "-s",
        type=int,
        help="Record results for this planting site. Default is to pick the first available planting site.",
    )
    parser.add_argument(
        "--forest-type",
        "-f",
        choices=["Mangrove", "Terrestrial"],
        default="Terrestrial",
        help="The type of forest to record data for. Default is Terrestrial.",
    )
    add_terraware_args(parser)
    args = parser.parse_args()

    client = client_from_args(args)

    if args.site:
        planting_site_id = args.site
    else:
        organization_id = args.organization or client.get_default_organization_id(
            require_admin=False
        )
        sites = client.get(
            "/api/v1/tracking/sites?organizationId=" + str(organization_id)
        )["sites"]
        if not sites:
            raise Exception(f"Organization {organization_id} has no planting sites")
        planting_site_id = sites[0]["id"]
        print(f"Using planting site {planting_site_id}")

    # Get the organization's species list to use for species selection
    organization_id = client.get_planting_site(planting_site_id)["organizationId"]
    species_ids = [species["id"] for species in client.list_species(organization_id)]

    if not species_ids:
        raise Exception(f"Organization {organization_id} has no species defined")

    # Generate and complete the biomass observation
    payload = generate_biomass_observation_payload(
        planting_site_id, args.forest_type, species_ids
    )
    result = client.complete_ad_hoc_observation(payload)

    print(
        f"Created and completed biomass observation {result['observationId']} with plot {result['plotId']}"
    )


if __name__ == "__main__":
    main()
