#!/usr/bin/env python3
import argparse
from datetime import datetime, timezone
import json
import random
import sys
import time
from client import add_terraware_args, client_from_args

# Default to 30 days of data for new timeseries.
DEFAULT_SECONDS = 30 * 24 * 60 * 60


timeseries_config = {
    ("OmniSense", "S-11"): {
        "interval": 300,
        "timeseries": {
            "temperature": {"min": -10, "max": 30},
            "humidity": {"min": 0, "max": 30},
        },
    },
    ("Blue Ion", "LV"): {
        "interval": 30,
        "timeseries": {
            "system_current": {"min": 60, "max": 120},
            "system_power": {"min": 100, "max": 5000},
            "system_voltage": {"min": 53, "max": 56},
            "relative_state_of_charge": {"min": 80, "max": 100},
            "state_of_health": {"min": 90, "max": 100},
        },
    },
    ("Blue Ion", "LX-HV"): {
        "interval": 30,
        "timeseries": {
            "BMU Status": {"min": 19070977, "max": 19070977},
            "current": {"min": -0.5, "max": 0.5},
            "Cycle Count": {"min": 313, "max": 313},
            "dc_voltage": {"min": 530, "max": 540},
            "relative_state_of_charge": {"min": 80, "max": 100},
            "state_of_health": {"min": 90, "max": 100},
            "system_power": {"min": 100, "max": 5000},
        },
    },
    ("Victron", "Cerbo GX"): {
        "interval": 30,
        "timeseries": {
            "relative_state_of_charge": {"min": 80, "max": 100},
            "system_power": {"min": 100, "max": 5000},
        },
    },
}


def parse_iso_datetime(iso_datetime: str) -> int:
    # Python doesn't like "Z" suffix
    iso_with_offset = iso_datetime.replace("Z", "+00:00")
    return int(datetime.fromisoformat(iso_with_offset).timestamp())


def isoformat(timestamp: int) -> str:
    return (
        datetime.fromtimestamp(timestamp, timezone.utc)
        .isoformat()
        .replace("+00:00", "Z")
    )


def values_for_time_range(
    start_time: int,
    end_time: int,
    interval: int,
    min_value: float,
    max_value: float,
    size: int = 1000,
):
    multiple = max_value - min_value

    values = []
    for timestamp in range(start_time, end_time, interval):
        value = str(random.random() * multiple + min_value)
        values.append(
            {
                "timestamp": isoformat(timestamp),
                "value": value,
            }
        )
        if len(values) >= size:
            yield values
            values = []

    if values:
        yield values


def timeseries_values_payload(
    device,
    name: str,
    start_time: int,
    end_time: int,
    interval: int,
    min_value: float,
    max_value: float,
):
    return [
        {"deviceId": device["id"], "timeseriesName": name, "values": values}
        for values in values_for_time_range(
            start_time, end_time, interval, min_value, max_value
        )
    ]


def record_values_payloads(device, config, latest_times, default_start_time, end_time):
    for name, params in config["timeseries"].items():
        # If we're adding to existing values, use the next timestamp after the most
        # recent one.
        if name in latest_times:
            timeseries_start_time = latest_times[name] + config["interval"]
        else:
            timeseries_start_time = default_start_time

        if timeseries_start_time <= end_time:
            for element in timeseries_values_payload(
                device,
                name,
                timeseries_start_time,
                end_time,
                config["interval"],
                params["min"],
                params["max"],
            ):
                yield {"timeseries": [element]}


def create_missing_timeseries(client, device_id, config, dry_run, verbose):
    """Create any timeseries that don't currently exist on the server."""
    existing_timeseries = {
        timeseries["timeseriesName"]: timeseries
        for timeseries in client.list_timeseries(device_id)
    }

    timeseries_to_create = [
        {
            "deviceId": device_id,
            "timeseriesName": name,
            "type": "Numeric",
            "decimalPlaces": 2,
        }
        for name, params in config["timeseries"].items()
        if name not in existing_timeseries
    ]

    if timeseries_to_create:
        if verbose:
            names = " ".join(
                [element["timeseriesName"] for element in timeseries_to_create]
            )
            print(f"Create timeseries: {names}")
        if not dry_run:
            client.create_timeseries({"timeseries": timeseries_to_create})


def get_latest_value_times(client, device):
    return {
        ts["timeseriesName"]: parse_iso_datetime(ts["latestValue"]["timestamp"])
        for ts in client.list_timeseries(device["id"])
        if "latestValue" in ts
    }


def main():
    parser = argparse.ArgumentParser(description="Generate dummy timeseries data.")
    parser.add_argument(
        "--device",
        "-d",
        type=int,
        nargs="*",
        help="Generate timeseries for this device. May be specified multiple times. "
        + "Default is to generate for all devices at selected facility.",
    )
    parser.add_argument(
        "--dry-run",
        "-n",
        action="store_true",
        help="Just print the list of values; don't submit it to the server.",
    )
    parser.add_argument(
        "--facility",
        "-f",
        type=int,
        nargs="*",
        help="Generate timeseries for devices at this facility. Ignored if --device "
        + "is specified. May be specified multiple times. Default is to scan all "
        + "facilities accessible by the user.",
    )
    parser.add_argument(
        "--ignore-existing",
        "-i",
        action="store_true",
        help="Ignore existing values and generate a full set of data. Default is "
        + "to only create values newer than the existing values.",
    )
    parser.add_argument(
        "--seconds",
        "-s",
        type=int,
        default=DEFAULT_SECONDS,
        help="Generate this many seconds of initial data for new timeseries. "
        + "Default is 30 days.",
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Print payload contents."
    )
    add_terraware_args(parser)

    args = parser.parse_args()

    client = client_from_args(args)

    if args.device:
        devices = [client.get_device(id) for id in args.device]
    else:
        if args.facility:
            facilities = args.facility
        else:
            facilities = [facility["id"] for facility in client.list_facilities()]

        devices = [
            device
            for facility_id in facilities
            for device in client.list_devices(facility_id)
        ]

    end_time = int(time.time())
    start_time = end_time - args.seconds

    for device in devices:
        config = timeseries_config.get((device["make"], device["model"]))
        if not config:
            if args.verbose:
                print(f"Skipping unknown device {device['make']} {device['model']}")
            continue

        if args.verbose:
            print(f"Device {device['id']} ({device['make']} {device['model']})")

        create_missing_timeseries(
            client, device["id"], config, args.dry_run, args.verbose
        )

        if args.ignore_existing:
            latest_times = {}
        else:
            latest_times = get_latest_value_times(client, device)

        for payload in record_values_payloads(
            device, config, latest_times, start_time, end_time
        ):
            if args.verbose:
                for ts in payload["timeseries"]:
                    print(
                        f"Timeseries {ts['timeseriesName']}: {len(ts['values'])} values"
                    )
            if not args.dry_run:
                response = client.record_values(payload)
                if args.verbose:
                    print(f"Response: {json.dumps(response)}")


if __name__ == "__main__":
    main()
