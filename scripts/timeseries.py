#!/usr/bin/env python3
import argparse
from datetime import datetime, timezone
import itertools
import json
import os
import random
import requests
import sys
import time
from typing import Optional

# Default to 30 days of data for new timeseries.
DEFAULT_SECONDS = 30 * 24 * 60 * 60
DEFAULT_URL = "http://localhost:8080"


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


class TerrawareClient:
    def __init__(
        self,
        bearer: Optional[str] = None,
        session: Optional[str] = None,
        base_url: Optional[str] = None,
    ):
        if bearer:
            self.auth_header = {"Authorization": f"Bearer {bearer}"}
        else:
            self.auth_header = {"Cookie": f"SESSION={session}"}
        self.base_url = (base_url or DEFAULT_URL).rstrip("/")

    def _add_auth_header(self, kwargs):
        """Add an authentication header to the keyword arguments of a requests API call."""
        existing_headers = kwargs.get("headers", {})
        return {**kwargs, "headers": {**self.auth_header, **existing_headers}}

    def get(self, url, **kwargs):
        kwargs_with_auth = self._add_auth_header(kwargs)
        r = requests.get(self.base_url + url, **kwargs_with_auth)
        r.raise_for_status()
        return r.json()

    def post(self, url, **kwargs):
        kwargs_with_auth = self._add_auth_header(kwargs)
        r = requests.post(self.base_url + url, **kwargs_with_auth)
        r.raise_for_status()
        return r.json()

    def list_facilities(self):
        return self.get("/api/v1/facilities")

    def list_devices(self, facility_id):
        return self.get(f"/api/v1/facilities/{facility_id}/devices")

    def get_device(self, device_id):
        return self.get(f"/api/v1/devices/{device_id}")

    def create_timeseries(self, payload):
        return self.post("/api/v1/timeseries/create", json=payload)

    def record_values(self, payload):
        return self.post("/api/v1/timeseries/values", json=payload)

    def list_timeseries(self, device_id):
        return self.get(f"/api/v1/timeseries?deviceId={device_id}")


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
        for timeseries in client.list_timeseries(device_id)["timeseries"]
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
        for ts in client.list_timeseries(device["id"])["timeseries"]
        if "latestValue" in ts
    }


def main():
    parser = argparse.ArgumentParser(description="Generate dummy timeseries data.")
    parser.add_argument(
        "--bearer",
        help="Bearer token to use (session cookie is ignored if this is set)",
    )
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
        "--session",
        help="Session cookie to use instead of canned one from test database",
    )
    parser.add_argument(
        "--url",
        "-u",
        default=DEFAULT_URL,
        help="Base URL of terraware-server. Default is http://localhost:8080.",
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Print payload contents."
    )

    args = parser.parse_args()

    if not args.bearer and not args.session:
        print(
            "Must specify either --bearer or --session to authenticate to server",
            file=sys.stderr,
        )
        sys.exit(1)

    client = TerrawareClient(args.bearer, args.session, args.url)

    if args.device:
        devices = [client.get_device(id)["device"] for id in args.device]
    else:
        if args.facility:
            facilities = args.facility
        else:
            facilities = [
                facility["id"] for facility in client.list_facilities()["facilities"]
            ]

        devices = [
            device
            for facility_id in facilities
            for device in client.list_devices(facility_id)["devices"]
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
