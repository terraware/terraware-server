package com.terraformation.backend.jooq

/**
 * See the definition of EnumTable for general information. There are roughly two ways to
 * specify items in the includeExpression list. Both require use of regex.
 *
 * 1. By naming specific tables. E.g. The table "facilities" has a column "type_id",
 *    which is a foreign key referencing the "facility_types" table.
 * 2. With a regular expression that may match multiple tables. E.g. Since
 *    "source_plant_origin_id" is a very specific name, we can safely include any table
 *    that has a column named "source_plant_origin_id". We are guaranteed that such a column
 *    will be a foreign key reference into the "source_plant_origins" table.
*/
val ENUM_TABLES =
    listOf(
        EnumTable(
            "accession_states",
            listOf(
                "accessions\\.state_id",
                ".*\\.accession_state_id",
                "accession_state_history\\.(old|new)_state_id")),
        EnumTable("facility_types", "facilities\\.type_id"),
        EnumTable("germination_seed_types", "germination_tests\\.seed_type_id"),
        EnumTable("germination_substrates", "germination_tests\\.substrate_id"),
        EnumTable(
            "germination_test_types",
            listOf(
                "germination_tests\\.test_type",
                "accession_germination_test_types\\.germination_test_type_id")),
        EnumTable("germination_treatments", "germination_tests\\.treatment_id"),
        EnumTable("layer_types", ".*\\.layer_type_id"),
        EnumTable("notification_types", "notifications\\.type_id"),
        EnumTable("health_states", listOf("health_states\\.id", ".*\\.health_state_id")),
        EnumTable("processing_methods", "accessions\\.processing_method_id"),
        EnumTable("seed_quantity_units", listOf(".*\\_units_id"), "SeedQuantityUnits"),
        EnumTable("source_plant_origins", ".*\\.source_plant_origin_id"),
        EnumTable("species_endangered_types", ".*\\.species_endangered_type_id"),
        EnumTable("species_rare_types", ".*\\.species_rare_type_id"),
        EnumTable(
            "storage_conditions",
            listOf("accessions\\.target_storage_condition", "storage_locations\\.condition_id")),
        EnumTable("timeseries_types", "timeseries\\.type_id"),
        EnumTable("user_types", ".*\\.user_type_id"),
        EnumTable("withdrawal_purposes", "withdrawals\\.purpose_id"))

val ID_WRAPPERS =
    listOf(
        IdWrapper("AccessionId", listOf("accessions\\.id", ".*\\.accession_id")),
        IdWrapper("AppDeviceId", listOf("app_devices\\.id", ".*\\.app_device_id")),
        IdWrapper("BagId", listOf("bags\\.id", ".*\\.bag_id")),
        IdWrapper(
            "CollectorId",
            listOf("collectors\\.id", ".*\\.collector_id", "accessions\\.primary_collector_id")),
        IdWrapper("DeviceId", listOf("devices\\.id", ".*\\.device_id")),
        IdWrapper("FacilityId", listOf("facilities\\.id", ".*\\.facility_id")),
        IdWrapper("FeatureId", listOf("features\\.id", ".*\\.feature_id")),
        IdWrapper("GeolocationId", listOf("geolocations\\.id", ".*\\.geolocation_id")),
        IdWrapper("GerminationId", listOf("germinations\\.id", ".*\\.germination_id")),
        IdWrapper(
            "GerminationTestId",
            listOf("germination_tests\\.id", ".*\\.germination_test_id", "germinations\\.test_id")),
        IdWrapper("LayerId", listOf("layers\\.id", ".*\\.layer_id")),
        IdWrapper("NotificationId", listOf("notifications\\.id", ".*\\.notification_id")),
        IdWrapper("OrganizationId", listOf("organizations\\.id", ".*\\.organization_id")),
        IdWrapper("PhotoId", listOf("photos\\.id", ".*\\.photo_id")),
        IdWrapper("PlantObservationId", listOf("plant_observations\\.id", ".*\\.plant_observation_id")),
        IdWrapper("ProjectId", listOf("projects\\.id", ".*\\.project_id")),
        IdWrapper("SiteId", listOf("sites\\.id", ".*\\.site_id")),
        IdWrapper("SpeciesFamilyId", listOf("species_families\\.id", ".*\\.species_family_id")),
        IdWrapper("SpeciesId", listOf("species\\.id", ".*\\.species_id")),
        IdWrapper(
            "StorageLocationId", listOf("storage_locations\\.id", ".*\\.storage_location_id")),
        IdWrapper("ThumbnailId", listOf("thumbnail\\.id")),
        IdWrapper("TimeseriesId", listOf("timeseries\\.id", ".*\\.timeseries_id")),
        IdWrapper("UserId", listOf("users\\.id", ".*\\.user_id")),
        IdWrapper("WithdrawalId", listOf("withdrawals\\.id", ".*\\.withdrawal_id")),
    )
