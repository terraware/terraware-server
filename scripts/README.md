# Utility and test scripts for terraware-server

## Prerequisites

Python 3.7 or higher.

## Installation

Make a venv and install the dependencies:

```
python3 -m venv .venv
source .venv/bin/activate
pip3 install -r requirements.txt
```

## Creating test accessions

To create 1000 test accessions using a server running on port 8080 on the local host:

```
./create_accessions.py -n 1000
```

If the server is running somewhere else, give its base URL as an argument:

```
./create_accessions.py -n 1000 http://somewhere:12345
```

To see the server's response with additional fields populated, use the `-v` option:

```
./create_accessions.py -n 1 -v
```

## Updating an accession's field values

To set the `seedsCounted` and `processingStartDate` fields on accession ABCDEFG with
the server running on port 8080 on the local host::

```
echo '{"seedsCounted":10, "processingStartDate":"2021-02-03"}' | ./edit_accession.py ABCDEFG
```

## Manipulating the server's clock

To view the current time and date according to a server running on port 8080 on the local host:

```
./clock.py
```

To advance that server's clock by 3 days:

```
./clock.py -d 3
```

## Creating batch withdrawals

```
./withdraw_to_subzones.py -b 306 -p 190
```

and a quick example of repeating this 500 times:

```
for i in {1..500}; do
  ./withdraw_to_subzones.py -b 306 -p 190
done
```

## Generating fake timeseries data for a facility's devices

If you have already created temperature/humidity sensor devices and PV system devices using
the admin UI, you can use a script to fill your database with random timeseries data so you
can test working with it.

By default, the script will create timeseries for all devices with makes/models it knows
about under all the facilities the user has access to. Initially it will create 30 days of
dummy data; if run again, it will add new data to the existing data to fill in the gap
between the previous time it was run and the current time. For dev environments that's
usually reasonable since it means you can run it like:

```
./timeseries.py --session SESSION_COOKIE_VALUE
```

or, if you have a refresh token from Keycloak:

```
./timeseries.py --refresh-token REFRESH_TOKEN_VALUE
```

To simulate a device manager submitting new data periodically, you can run it in a loop
in the shell:

```
while true; do
  ./timeseries.py --session SESSION_COOKIE_VALUE
  sleep 30
done
```

There are additional options to limit the devices and/or facilities it touches; run it
with the `--help` option for details.
