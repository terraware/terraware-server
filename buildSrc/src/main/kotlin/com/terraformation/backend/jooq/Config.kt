package com.terraformation.backend.jooq

val ENUM_TABLES =
    listOf(
        EnumTable(
            "accession_states",
            listOf(
                "accessions\\.state_id",
                ".*\\.accession_state_id",
                "accession_state_history\\.(old|new)_state_id")),
        EnumTable("germination_seed_types", "germination_tests\\.seed_type_id"),
        EnumTable("germination_substrates", "germination_tests\\.substrate_id"),
        EnumTable(
            "germination_test_types",
            listOf(
                "germination_tests\\.test_type",
                "accession_germination_test_types\\.germination_test_type_id")),
        EnumTable("germination_treatments", "germination_tests\\.treatment_id"),
        EnumTable("notification_types", "notifications\\.type_id"),
        EnumTable("processing_methods", "accessions\\.processing_method_id"),
        EnumTable("seed_quantity_units", listOf(".*\\_units_id"), "SeedQuantityUnits"),
        EnumTable("source_plant_origins", ".*\\.source_plant_origin_id"),
        EnumTable("species_endangered_types", ".*\\.species_endangered_type_id"),
        EnumTable("species_rare_types", ".*\\.species_rare_type_id"),
        EnumTable(
            "storage_conditions",
            listOf("accessions\\.target_storage_condition", "storage_locations\\.condition_id")),
        EnumTable("timeseries_types", "timeseries\\.type_id"),
        EnumTable("withdrawal_purposes", "withdrawals\\.purpose_id"))

val ID_WRAPPERS =
    listOf(
        IdWrapper("AccessionId", listOf("accessions\\.id", ".*\\.accession_id")),
        IdWrapper("AccessionPhotoId", listOf("accession_photos\\.id", ".*\\.accession_photo_id")),
        IdWrapper("AppDeviceId", listOf("app_devices\\.id", ".*\\.app_device_id")),
        IdWrapper("BagId", listOf("bags\\.id", ".*\\.bag_id")),
        IdWrapper(
            "CollectorId",
            listOf("collectors\\.id", ".*\\.collector_id", "accessions\\.primary_collector_id")),
        IdWrapper("DeviceId", listOf("devices\\.id", ".*\\.device_id")),
        IdWrapper("FacilityId", listOf("facilities\\.id", ".*\\.facility_id")),
        IdWrapper("GeolocationId", listOf("geolocations\\.id", ".*\\.geolocation_id")),
        IdWrapper("GerminationId", listOf("germinations\\.id", ".*\\.germination_id")),
        IdWrapper(
            "GerminationTestId",
            listOf("germination_tests\\.id", ".*\\.germination_test_id", "germinations\\.test_id")),
        IdWrapper("NotificationId", listOf("notifications\\.id", ".*\\.notification_id")),
        IdWrapper("OrganizationId", listOf("organizations\\.id", ".*\\.organization_id")),
        IdWrapper("ProjectId", listOf("projects\\.id", ".*\\.project_id")),
        IdWrapper("SiteId", listOf("sites\\.id", ".*\\.site_id")),
        IdWrapper("SpeciesId", listOf("species\\.id", ".*\\.species_id")),
        IdWrapper("SpeciesFamilyId", listOf("species_families\\.id", ".*\\.species_family_id")),
        IdWrapper(
            "StorageLocationId", listOf("storage_locations\\.id", ".*\\.storage_location_id")),
        IdWrapper("TimeseriesId", listOf("timeseries\\.id", ".*\\.timeseries_id")),
        IdWrapper("WithdrawalId", listOf("withdrawals\\.id", ".*\\.withdrawal_id")),
    )
