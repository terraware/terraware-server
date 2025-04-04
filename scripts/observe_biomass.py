#!/usr/bin/env python3
import argparse
import random
import time
from datetime import datetime, timezone

from client import add_terraware_args, client_from_args


def isoformat(timestamp: int) -> str:
    """Convert a Unix timestamp to ISO 8601 format with Z suffix."""
    return (
        datetime.fromtimestamp(timestamp, timezone.utc)
        .isoformat()
        .replace("+00:00", "Z")
    )


def random_decimal(min_val, max_val, precision=1):
    """Generate a random decimal with specified precision."""
    return round(random.uniform(min_val, max_val), precision)


def generate_species_entry(species_ids):
    """Generate a random species entry with either ID or name."""
    if random.randint(1, 10) > 2:  # 80% chance of known species
        return {
            "commonName": None,
            "isInvasive": random.choice([True, False]),
            "isThreatened": random.choice([True, False]),
            "scientificName": None,
            "speciesId": random.choice(species_ids),
        }
    else:
        return {
            "commonName": None,
            "isInvasive": random.choice([True, False]),
            "isThreatened": random.choice([True, False]),
            "scientificName": f"Unknown Species {random.randint(1, 10)}",
            "speciesId": None,
        }


def get_species_refs(species_entry):
    """Extract speciesId and speciesName from a species entry."""
    if species_entry["speciesId"] is not None:
        return {"speciesId": species_entry["speciesId"], "speciesName": None}
    else:
        return {"speciesId": None, "speciesName": species_entry["scientificName"]}


def generate_biomass_observation_payload(planting_site_id, forest_type, species_ids):
    """Generate a random biomass observation payload."""
    # Create timestamp for the observation
    current_time = int(time.time())
    observed_time = isoformat(current_time - random.randint(0, 86400))

    # Generate unique master species list
    num_species = random.randint(5, 15)
    unique_species_keys = set()
    master_species_list = []

    while len(master_species_list) < num_species:
        species_entry = generate_species_entry(species_ids)
        key = (
            species_entry["speciesId"]
            if species_entry["speciesId"] is not None
            else species_entry["scientificName"]
        )

        if key is not None and key not in unique_species_keys:
            unique_species_keys.add(key)
            master_species_list.append(species_entry)

    # Generate quadrats
    quadrats = []
    positions = [
        "NorthwestCorner",
        "NortheastCorner",
        "SoutheastCorner",
        "SouthwestCorner",
    ]

    for position in positions:
        if random.random() < 0.9:  # 90% chance of including each position
            # Select a random subset of species for this quadrat
            max_species = min(5, len(master_species_list))
            num_quadrat_species = random.randint(1, max_species)
            selected_species = random.sample(master_species_list, num_quadrat_species)

            # Create species entries for the quadrat
            quadrat_species = [
                {"abundancePercent": random.randint(5, 95), **get_species_refs(species)}
                for species in selected_species
            ]

            quadrats.append(
                {
                    "description": f"Quadrat at {position}",
                    "position": position,
                    "species": quadrat_species,
                }
            )

    # Generate trees
    trees = []
    num_trees = random.randint(3, 15)

    for _ in range(num_trees):
        species = random.choice(master_species_list)
        species_refs = get_species_refs(species)

        if random.choice(["shrub", "tree"]) == "shrub":
            trees.append(
                {
                    "growthForm": "shrub",
                    "description": f"Shrub {random.randint(1, 100)}",
                    "isDead": random.choices([True, False], weights=[1, 9])[0],
                    "shrubDiameter": random.randint(5, 50),
                    **species_refs,
                }
            )
        else:
            num_trunks = random.choices([1, random.randint(2, 5)], weights=[8, 2])[0]
            trunks = [
                {
                    "diameterAtBreastHeight": random_decimal(5, 100),
                    "height": random_decimal(1, 20),
                    "pointOfMeasurement": random_decimal(1.3, 1.5),
                    "description": f"Trunk {i+1}",
                    "isDead": random.choices([True, False], weights=[1, 9])[0],
                }
                for i in range(num_trunks)
            ]

            trees.append({"growthForm": "tree", "trunks": trunks, **species_refs})

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

    # Create the payload
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
                "ph": random_decimal(6.0, 8.5),
                "salinity": random_decimal(15, 35),
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
