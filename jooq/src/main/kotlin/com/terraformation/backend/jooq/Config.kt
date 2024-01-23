package com.terraformation.backend.jooq

import org.jooq.meta.jaxb.EmbeddableDefinitionType

/**
 * See the definition of EnumTable for general information. There are roughly two ways to specify
 * items in the includeExpression list. Both require use of regex.
 * 1. By naming specific tables. E.g. The table "facilities" has a column "type_id", which is a
 *    foreign key referencing the "facility_types" table.
 * 2. With a regular expression that may match multiple tables. E.g. Since "source_plant_origin_id"
 *    is a very specific name, we can safely include any table that has a column named
 *    "source_plant_origin_id". We are guaranteed that such a column will be a foreign key reference
 *    into the "source_plant_origins" table.
 */
val ENUM_TABLES =
    mapOf(
        "nursery" to
            listOf(
                EnumTable(
                    "batch_quantity_history_types",
                    listOf("batch_quantity_history\\.history_type_id"),
                    isLocalizable = false),
                EnumTable("batch_substrates", listOf("nursery\\..*\\.substrate_id")),
                EnumTable(
                    "withdrawal_purposes",
                    listOf(
                        "nursery\\.withdrawals\\.purpose_id",
                        "nursery\\.withdrawal_summaries\\.purpose_id")),
            ),
        "public" to
            listOf(
                EnumTable(
                    "conservation_categories",
                    listOf(".*\\.conservation_category_id"),
                    "ConservationCategory",
                    useIdAsJsonValue = true),
                EnumTable(
                    "device_template_categories",
                    listOf("device_templates\\.category_id"),
                    "DeviceTemplateCategory",
                    isLocalizable = false),
                EnumTable("ecosystem_types", listOf(".*\\.ecosystem_type_id")),
                EnumTable("facility_connection_states", listOf("facilities\\.connection_state_id")),
                EnumTable("facility_types", listOf("facilities\\.type_id")),
                EnumTable("global_roles", listOf(".*\\.global_role_id"), isLocalizable = false, useIdAsJsonValue = true),
                EnumTable("growth_forms", listOf("growth_forms\\.id", ".*\\.growth_form_id")),
                EnumTable(
                    "managed_location_types",
                    listOf("managed_location_types\\.id", ".*\\.managed_location_type_id"),
                    isLocalizable = false),
                EnumTable(
                    "notification_criticalities",
                    listOf(".*\\.notification_criticality_id"),
                    "NotificationCriticality",
                    generateForcedType = false,
                    isLocalizable = false),
                EnumTable(
                    "notification_types",
                    listOf(".*\\.notification_type_id"),
                    additionalColumns =
                        listOf(
                            EnumTableColumnInfo(
                                "notification_criticality_id",
                                "NotificationCriticality",
                                true,
                            )),
                    isLocalizable = false),
                EnumTable(
                    "organization_types",
                    listOf("organization_types\\.id", ".*\\.organization_type_id"),
                    isLocalizable = false),
                EnumTable("report_statuses", listOf("reports\\.status_id"), "ReportStatus"),
                EnumTable("roles", listOf(".*\\.role_id")),
                EnumTable(
                    "seed_storage_behaviors",
                    listOf("seed_storage_behaviors\\.id", ".*\\.seed_storage_behavior_id")),
                EnumTable("seed_treatments", listOf(".*\\.treatment_id")),
                EnumTable("species_problem_fields", listOf("species_problems\\.field_id")),
                EnumTable("species_problem_types", listOf("species_problems\\.type_id")),
                EnumTable(
                    "timeseries_types", listOf("timeseries\\.type_id"), isLocalizable = false),
                EnumTable(
                    "upload_problem_types",
                    listOf("upload_problems\\.type_id"),
                    isLocalizable = false),
                EnumTable(
                    "upload_statuses",
                    listOf("uploads\\.status_id"),
                    "UploadStatus",
                    listOf(EnumTableColumnInfo("finished", "Boolean", false)),
                    isLocalizable = false),
                EnumTable(
                    "upload_types",
                    listOf("uploads\\.type_id"),
                    additionalColumns = listOf(EnumTableColumnInfo("expire_files", "Boolean")),
                    isLocalizable = false),
                EnumTable("user_types", listOf(".*\\.user_type_id"), isLocalizable = false),
            ),
        "seedbank" to
            listOf(
                EnumTable(
                    "accession_quantity_history_types",
                    listOf("accession_quantity_history\\.history_type_id"),
                    isLocalizable = false),
                EnumTable(
                    "accession_states",
                    listOf(
                        "accessions\\.state_id",
                        ".*\\.accession_state_id",
                        "accession_state_history\\.(old|new)_state_id"),
                    additionalColumns = listOf(EnumTableColumnInfo("active", "Boolean"))),
                EnumTable("collection_sources", listOf(".*\\.collection_source_id")),
                EnumTable("data_sources", listOf(".*\\.data_source_id")),
                EnumTable("seed_quantity_units", listOf(".*\\_units_id"), "SeedQuantityUnits"),
                EnumTable("viability_test_seed_types", listOf("viability_tests\\.seed_type_id")),
                EnumTable("viability_test_substrates", listOf("viability_tests\\.substrate_id")),
                EnumTable("viability_test_types", listOf("viability_tests\\.test_type")),
                EnumTable("withdrawal_purposes", listOf("seedbank\\.withdrawals\\.purpose_id")),
            ),
        "tracking" to
            listOf(
                EnumTable(
                    "observable_conditions",
                    listOf("observation_plot_conditions\\.condition_id"),
                    isLocalizable = false),
                EnumTable(
                    "observation_plot_positions",
                    listOf(
                        "observation_photos\\.position_id",
                        "observed_plot_coordinates\\.position_id"),
                    isLocalizable = false),
                EnumTable(
                    "observation_states", listOf("observations\\.state_id"), isLocalizable = false),
                EnumTable("planting_types", listOf(".*\\.planting_type_id")),
                EnumTable(
                    "recorded_plant_statuses",
                    listOf("recorded_plants\\.status_id"),
                    "RecordedPlantStatus",
                    isLocalizable = false),
                EnumTable(
                    "recorded_species_certainties",
                    listOf("tracking\\..*\\.certainty_id"),
                    "RecordedSpeciesCertainty",
                    isLocalizable = false),
            ),
    )

val ID_WRAPPERS =
    mapOf(
        "nursery" to
            listOf(
                IdWrapper(
                    "BatchDetailsHistoryId",
                    listOf("batch_details_history\\.id", ".*\\.batch_details_history_id")),
                IdWrapper(
                    "BatchId",
                    listOf(
                        "batches\\.id",
                        "batch_summaries\\.id",
                        "batch_withdrawals\\.destination_batch_id",
                        ".*\\.batch_id",
                        ".*\\.initial_batch_id")),
                IdWrapper("BatchPhotoId", listOf("batch_photos\\.id")),
                IdWrapper("BatchQuantityHistoryId", listOf("batch_quantity_history\\.id")),
                IdWrapper(
                    "WithdrawalId",
                    listOf(
                        "nursery\\.withdrawals\\.id",
                        "nursery\\.withdrawal_summaries\\.id",
                        "nursery\\..*\\.withdrawal_id",
                        "tracking\\..*\\.withdrawal_id")),
            ),
        "public" to
            listOf(
                IdWrapper("AutomationId", listOf("automations\\.id")),
                IdWrapper("BalenaDeviceId", listOf("device_managers\\.balena_id")),
                IdWrapper(
                    "DeviceId", listOf("devices\\.id", "devices\\.parent_id", ".*\\.device_id")),
                IdWrapper("DeviceManagerId", listOf("device_managers\\.id")),
                IdWrapper("DeviceTemplateId", listOf("device_templates\\.id")),
                IdWrapper(
                    "FacilityId",
                    listOf("facilities\\.id", ".*\\.destination_facility_id", ".*\\.facility_id")),
                IdWrapper("FileId", listOf("files\\.id", ".*\\.file_id")),
                IdWrapper("GbifNameId", listOf("gbif_names\\.id", ".*\\.gbif_name_id")),
                IdWrapper(
                    "GbifTaxonId",
                    listOf("gbif_taxa\\.id", "gbif_.*\\.taxon_id", "gbif_.*\\..*_usage_id")),
                IdWrapper("InternalTagId", listOf("internal_tags\\.id", ".*\\.internal_tag_id")),
                IdWrapper("NotificationId", listOf("notifications\\.id", ".*\\.notification_id")),
                IdWrapper("OrganizationId", listOf("organizations\\.id", ".*\\.organization_id")),
                IdWrapper("ParticipantId", listOf("participants\\.id", ".*\\.participant_id")),
                IdWrapper("ProjectId", listOf("projects\\.id", ".*\\.project_id")),
                IdWrapper("ReportId", listOf("reports\\.id", ".*\\.report_id")),
                IdWrapper("SpeciesId", listOf("species\\.id", ".*\\.species_id")),
                IdWrapper("SpeciesProblemId", listOf("species_problems\\.id")),
                IdWrapper("SubLocationId", listOf("sub_locations\\.id", ".*\\.sub_location_id")),
                IdWrapper("ThumbnailId", listOf("thumbnails\\.id")),
                IdWrapper("TimeseriesId", listOf("timeseries\\.id", ".*\\.timeseries_id")),
                IdWrapper("UploadId", listOf("uploads\\.id", ".*\\.upload_id")),
                IdWrapper("UploadProblemId", listOf("upload_problems\\.id")),
                IdWrapper(
                    "UserId",
                    listOf(
                        "users\\.id",
                        ".*\\.user_id",
                        ".*\\.[a-z_]+_by",
                    )),
            ),
        "seedbank" to
            listOf(
                IdWrapper(
                    "AccessionId",
                    listOf("accessions\\.id", ".*\\.accession_id", ".*\\.seed_accession_id")),
                IdWrapper("AccessionQuantityHistoryId", listOf("accession_quantity_history\\.id")),
                IdWrapper("BagId", listOf("bags\\.id", ".*\\.bag_id")),
                IdWrapper("GeolocationId", listOf("geolocations\\.id", ".*\\.geolocation_id")),
                IdWrapper(
                    "ViabilityTestId",
                    listOf(
                        "viability_tests\\.id",
                        ".*\\.viability_test_id",
                        "viability_test_results\\.test_id")),
                IdWrapper("ViabilityTestResultId", listOf("viability_test_results\\.id")),
                IdWrapper(
                    "WithdrawalId",
                    listOf("seedbank\\.withdrawals\\.id", "seedbank\\..*\\.withdrawal_id")),
            ),
        "tracking" to
            listOf(
                IdWrapper("DeliveryId", listOf("deliveries\\.id", ".*\\.delivery_id")),
                IdWrapper(
                    "MonitoringPlotId", listOf("monitoring_plots\\.id", ".*\\.monitoring_plot_id")),
                IdWrapper("ObservationId", listOf("observations\\.id", ".*\\.observation_id")),
                IdWrapper("ObservedPlotCoordinatesId", listOf("observed_plot_coordinates\\.id")),
                IdWrapper("PlantingId", listOf("plantings\\.id")),
                IdWrapper("PlantingSeasonId", listOf("planting_seasons\\.id")),
                IdWrapper(
                    "PlantingSiteId",
                    listOf(
                        "planting_sites\\.id",
                        "planting_site_summaries\\.id",
                        ".*\\.planting_site_id")),
                IdWrapper("PlantingSiteNotificationId", listOf("planting_site_notifications\\.id")),
                IdWrapper("PlantingZoneId", listOf("planting_zones\\.id", ".*\\.planting_zone_id")),
                IdWrapper(
                    "PlantingSubzoneId",
                    listOf("planting_subzones\\.id", ".*\\.planting_subzone_id")),
                IdWrapper("RecordedPlantId", listOf("recorded_plants\\.id")),
            ),
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
            .withName("accession_collector_id")
            .withTables("seedbank.accession_collectors")
            .withColumns("accession_id", "position"),
        EmbeddableDefinitionType()
            .withName("batch_sub_location_id")
            .withTables("nursery.batch_sub_locations")
            .withColumns("batch_id", "sub_location_id"),
        EmbeddableDefinitionType()
            .withName("batch_withdrawal_id")
            .withTables("nursery.batch_withdrawals")
            .withColumns("batch_id", "withdrawal_id"),
        EmbeddableDefinitionType()
            .withName("facility_inventory_id")
            .withTables("nursery.facility_inventories")
            .withColumns("facility_id", "species_id"),
        EmbeddableDefinitionType()
            .withName("nursery_species_project_id")
            .withTables("nursery.species_projects")
            .withColumns("species_id", "project_id"),
        EmbeddableDefinitionType()
            .withName("organization_user_id")
            .withTables("public.organization_users")
            .withColumns("organization_id", "user_id"),
        EmbeddableDefinitionType()
            .withName("planting_site_population_id")
            .withTables("tracking.planting_site_populations")
            .withColumns("planting_site_id", "species_id"),
        EmbeddableDefinitionType()
            .withName("planting_subzone_population_id")
            .withTables("tracking.planting_subzone_populations")
            .withColumns("planting_subzone_id", "species_id"),
        EmbeddableDefinitionType()
            .withName("species_ecosystem_id")
            .withTables("public.species_ecosystem_types")
            .withColumns("species_id", "ecosystem_type_id"),
    )
