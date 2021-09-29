import argparse
import os
import json
import datetime
import shapefile
import sys
from typing import Optional
from sridentify import Sridentify
from pypika import CustomFunction, Table, Query

# SRID to use when inserting into the database; input that uses a different SRID
# will be converted to this one at insert time.
db_srid = 3857

ST_GeomFromGeoJSON = CustomFunction("ST_GeomFromGeoJSON", ["json"])
ST_Force3D = CustomFunction("ST_Force3D", ["geom"])
ST_Transform = CustomFunction("ST_Transform", ["geom", "srid"])
CurrVal = CustomFunction("CURRVAL", ["sequence_name"])
Now = CustomFunction("NOW")


def print_sql(query: Query):
    print(str(query) + ";")


def insert_layer(site_id: int, layer_type_id: int):
    q = (
        Query.into(Table("layers"))
        .columns(
            "site_id",
            "layer_type_id",
            "proposed",
            "hidden",
            "deleted",
            "created_time",
            "modified_time",
        )
        .insert(
            site_id,
            layer_type_id,
            False,
            False,
            False,
            Now(),
            Now(),
        )
    )

    print_sql(q)


def import_shapefile(
    file_name: str, layer_id: Optional[int] = None, create_plants: bool = False
):
    # get SRID from PRJ file
    # alternative: https://gis.stackexchange.com/questions/7608/shapefile-prj-to-postgis-srid-lookup-table/7615#7615
    prj_file_name = file_name.replace(".shp", ".prj")
    ident = Sridentify()
    ident.from_file(prj_file_name)
    srid = ident.get_epsg()

    if not layer_id:
        layer_id = CurrVal("layers_id_seq")

    # read the shape file
    sf = shapefile.Reader(file_name)
    feature_count = len(sf.shapeRecords())
    print(
        "        features: %d, shape type: %s, srid: %s, fields: %d"
        % (feature_count, sf.shapeTypeName, srid, len(sf.fields)),
        file=sys.stderr,
    )
    print("        bbox: %s" % sf.bbox, file=sys.stderr)

    for shapeRecord in sf.shapeRecords():

        # prepare geojson value
        geojson = {
            **shapeRecord.shape.__geo_interface__,
            "crs": {"type": "name", "properties": {"name": f"EPSG:{srid}"}},
        }

        # prepare PostGIS data transformations
        geom = ST_Force3D(ST_GeomFromGeoJSON(json.dumps(geojson)))
        if srid != db_srid:
            geom = ST_Transform(geom, db_srid)

        # prepare query string
        q = (
            Query.into(Table("features"))
            .columns("layer_id", "geom", "created_time", "modified_time")
            .insert(layer_id, geom, Now(), Now())
        )

        print_sql(q)

        # add plant record
        if create_plants:
            q = (
                Query.into(Table("plants"))
                .columns("feature_id", "created_time", "modified_time")
                .insert(CurrVal("features_id_seq"), Now(), Now())
            )

            print_sql(q)


def main():
    parser = argparse.ArgumentParser(
        description="Generates SQL to insert a shapefile into a Terraware database."
    )

    parser.add_argument(
        "--layer-type-id",
        "-t",
        type=int,
        help="Create a layer of this type; must be a valid ID from layer_types table.",
    )
    parser.add_argument(
        "--layer-id",
        "-l",
        type=int,
        help="ID of existing layer to insert new features into.",
    )
    parser.add_argument(
        "--features-only",
        "-F",
        type=bool,
        help="Only create features. Default is to also create a plant for each feature.",
    )
    parser.add_argument(
        "--site-id",
        "-s",
        type=int,
        help="ID of site to associate new layer with. Must be set if --layer-type-id is set.",
    )
    parser.add_argument(
        "filename",
        help="Path to .shp file. There must be a corresponding .prj file in the same directory.",
    )
    args = parser.parse_args()

    if args.layer_type_id:
        if args.site_id:
            insert_layer(args.site_id, args.layer_type_id)
        else:
            raise Exception("Site ID must be specified if creating a new layer")

    if not args.layer_type_id and not args.layer_id:
        raise Exception("Must specify a layer ID or a layer type ID + site ID")

    import_shapefile(
        file_name=args.filename,
        layer_id=args.layer_id,
        create_plants=not args.features_only,
    )


if __name__ == "__main__":
    main()
