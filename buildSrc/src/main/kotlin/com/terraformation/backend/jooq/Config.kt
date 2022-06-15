package com.terraformation.backend.jooq

import org.jooq.meta.jaxb.EmbeddableDefinitionType

/**
 * See the definition of EnumTable for general information. There are roughly two ways to specify
 * items in the includeExpression list. Both require use of regex.
 *
 * 1. By naming specific tables. E.g. The table "facilities" has a column "type_id", which is a
 * foreign key referencing the "facility_types" table.
 * 2. With a regular expression that may match multiple tables. E.g. Since "source_plant_origin_id"
 * is a very specific name, we can safely include any table that has a column named
 * "source_plant_origin_id". We are guaranteed that such a column will be a foreign key reference
 * into the "source_plant_origins" table.
 */
val ENUM_TABLES =
    listOf(
        EnumTable("accession_notification_types", "accession_notifications\\.type_id"),
        EnumTable(
            "accession_states",
            listOf(
                "accessions\\.state_id",
                ".*\\.accession_state_id",
                "accession_state_history\\.(old|new)_state_id")),
        EnumTable(
            "device_template_categories",
            listOf("device_templates\\.category_id"),
            "DeviceTemplateCategory"),
        EnumTable("facility_connection_states", "facilities\\.connection_state_id"),
        EnumTable("facility_types", "facilities\\.type_id"),
        EnumTable("germination_seed_types", "germination_tests\\.seed_type_id"),
        EnumTable("germination_substrates", "germination_tests\\.substrate_id"),
        EnumTable(
            "germination_test_types",
            listOf(
                "germination_tests\\.test_type",
                "accession_germination_test_types\\.germination_test_type_id")),
        EnumTable("germination_treatments", "germination_tests\\.treatment_id"),
        EnumTable("growth_forms", listOf("growth_forms\\.id", ".*\\.growth_form_id")),
        EnumTable(
            "notification_criticalities",
            listOf(".*\\.notification_criticality_id"),
            "NotificationCriticality"),
        EnumTable(
            "notification_types",
            listOf(".*\\.notification_type_id"),
            additionalColumns =
                listOf(
                    EnumTableColumnInfo(
                        "notification_criticality_id",
                        "NotificationCriticality",
                        true,
                    ))),
        EnumTable("processing_methods", "accessions\\.processing_method_id"),
        EnumTable("project_statuses", listOf("projects\\.status_id"), "ProjectStatus"),
        EnumTable("project_types", ".*\\.project_type_id"),
        EnumTable("rare_types", ".*\\.rare_type_id"),
        EnumTable("seed_quantity_units", listOf(".*\\_units_id"), "SeedQuantityUnits"),
        EnumTable(
            "seed_storage_behaviors",
            listOf("seed_storage_behaviors\\.id", ".*\\.seed_storage_behavior_id")),
        EnumTable("source_plant_origins", ".*\\.source_plant_origin_id"),
        EnumTable("species_endangered_types", ".*\\.species_endangered_type_id"),
        EnumTable("species_problem_fields", "species_problems\\.field_id"),
        EnumTable("species_problem_types", "species_problems\\.type_id"),
        EnumTable(
            "storage_conditions",
            listOf("accessions\\.target_storage_condition", "storage_locations\\.condition_id")),
        EnumTable("timeseries_types", "timeseries\\.type_id"),
        EnumTable("upload_problem_types", "upload_problems\\.type_id"),
        EnumTable(
            "upload_statuses",
            listOf("uploads\\.status_id"),
            "UploadStatus",
            listOf(EnumTableColumnInfo("finished", "Boolean", false))),
        EnumTable(
            "upload_types",
            "uploads\\.type_id",
            additionalColumns = listOf(EnumTableColumnInfo("expire_files", "Boolean", false))),
        EnumTable("user_types", ".*\\.user_type_id"),
        EnumTable("withdrawal_purposes", "withdrawals\\.purpose_id"))

val ID_WRAPPERS =
    listOf(
        IdWrapper("AccessionId", listOf("accessions\\.id", ".*\\.accession_id")),
        IdWrapper("AccessionSecondaryCollectorId", listOf("accession_secondary_collectors\\.id")),
        IdWrapper("AccessionNotificationId", listOf("accession_notifications\\.id")),
        IdWrapper("AppDeviceId", listOf("app_devices\\.id", ".*\\.app_device_id")),
        IdWrapper("AutomationId", listOf("automations\\.id")),
        IdWrapper("BagId", listOf("bags\\.id", ".*\\.bag_id")),
        IdWrapper("BalenaDeviceId", listOf("device_managers\\.balena_id")),
        IdWrapper("DeviceId", listOf("devices\\.id", "devices\\.parent_id", ".*\\.device_id")),
        IdWrapper("DeviceManagerId", listOf("device_managers\\.id")),
        IdWrapper("DeviceTemplateId", listOf("device_templates\\.id")),
        IdWrapper("FacilityId", listOf("facilities\\.id", ".*\\.facility_id")),
        IdWrapper("GbifNameId", listOf("gbif_names\\.id", ".*\\.gbif_name_id")),
        IdWrapper(
            "GbifTaxonId", listOf("gbif_taxa\\.id", "gbif_.*\\.taxon_id", "gbif_.*\\..*_usage_id")),
        IdWrapper("GeolocationId", listOf("geolocations\\.id", ".*\\.geolocation_id")),
        IdWrapper("GerminationId", listOf("germinations\\.id", ".*\\.germination_id")),
        IdWrapper(
            "GerminationTestId",
            listOf("germination_tests\\.id", ".*\\.germination_test_id", "germinations\\.test_id")),
        IdWrapper("NotificationId", listOf("notifications\\.id", ".*\\.notification_id")),
        IdWrapper("OrganizationId", listOf("organizations\\.id", ".*\\.organization_id")),
        IdWrapper("PhotoId", listOf("photos\\.id", ".*\\.photo_id")),
        IdWrapper("ProjectId", listOf("projects\\.id", ".*\\.project_id")),
        IdWrapper("SiteId", listOf("sites\\.id", ".*\\.site_id")),
        IdWrapper("SpeciesId", listOf("species\\.id", ".*\\.species_id")),
        IdWrapper("SpeciesProblemId", listOf("species_problems\\.id")),
        IdWrapper(
            "StorageLocationId", listOf("storage_locations\\.id", ".*\\.storage_location_id")),
        IdWrapper("ThumbnailId", listOf("thumbnails\\.id")),
        IdWrapper("TimeseriesId", listOf("timeseries\\.id", ".*\\.timeseries_id")),
        IdWrapper("UploadId", listOf("uploads\\.id", ".*\\.upload_id")),
        IdWrapper("UploadProblemId", listOf("upload_problems\\.id")),
        IdWrapper(
            "UserId",
            listOf(
                "users\\.id",
                ".*\\.user_id",
                ".*\\.created_by",
                ".*\\.deleted_by",
                ".*\\.modified_by")),
        IdWrapper("WithdrawalId", listOf("withdrawals\\.id", ".*\\.withdrawal_id")),
    )

/**
 * Defines the synthetic data types that are embedded in the column lists of tables. We mostly use
 * this to represent compound primary keys.
 *
 * @see https://www.jooq.org/doc/latest/manual/code-generation/codegen-embeddable-types/
 */
val EMBEDDABLES =
    listOf(
        EmbeddableDefinitionType()
            .withName("organization_user_id")
            .withTables("organization_users")
            .withColumns("organization_id", "user_id"),
        EmbeddableDefinitionType()
            .withName("project_user_id")
            .withTables("project_users")
            .withColumns("project_id", "user_id"),
    )
