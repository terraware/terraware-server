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
 *
 * If no list of include expressions is specified, the default is to match columns with the singular
 * form of the table name followed by "_id" in any table. For example, if the enum table is
 * `event_types`, the default include expression is `".*\\.event_type_id"`.
 */
val ENUM_TABLES =
    mapOf(
        "accelerator" to
            listOf(
                EnumTable("activity_statuses"),
                EnumTable("activity_types"),
                EnumTable("activity_media_types", isLocalizable = false),
                EnumTable("application_module_statuses"),
                EnumTable("application_statuses"),
                EnumTable("cohort_phases", listOf(".*\\.phase_id")),
                EnumTable("deal_stages", isLocalizable = false),
                EnumTable(
                    "deliverable_categories",
                    additionalColumns =
                        listOf(
                            EnumTableColumnInfo(
                                "internal_interest_id",
                                "InternalInterest",
                                true,
                            )
                        ),
                ),
                EnumTable("deliverable_types"),
                EnumTable("document_stores", isLocalizable = false),
                EnumTable("event_statuses"),
                EnumTable("event_types"),
                EnumTable("internal_interests"),
                EnumTable("metric_components", listOf("[a-z_]+_metrics\\.component_id")),
                EnumTable("metric_types", listOf("[a-z_]+_metrics\\.type_id")),
                EnumTable("pipelines", isLocalizable = false),
                EnumTable("report_frequencies"),
                EnumTable(
                    "report_metric_statuses",
                    listOf(
                        "report_project_metrics\\.status_id",
                        "report_standard_metrics\\.status_id",
                        "report_system_metrics\\.status_id",
                        "funder\\.published_report_project_metrics\\.status_id",
                        "funder\\.published_report_standard_metrics\\.status_id",
                        "funder\\.published_report_system_metrics\\.status_id",
                    ),
                ),
                EnumTable("report_quarters"),
                EnumTable("report_statuses", listOf("reports\\.status_id")),
                EnumTable("score_categories", isLocalizable = false),
                EnumTable("submission_statuses"),
                EnumTable(
                    "system_metrics",
                    listOf("system_metrics\\.id", ".*\\.system_metric_id"),
                    additionalColumns =
                        listOf(
                            EnumTableColumnInfo(
                                "type_id",
                                "MetricType",
                                true,
                            ),
                            EnumTableColumnInfo(
                                "component_id",
                                "MetricComponent",
                                true,
                            ),
                            EnumTableColumnInfo(
                                "description",
                                "String",
                            ),
                            EnumTableColumnInfo(
                                "reference",
                                "String",
                            ),
                            EnumTableColumnInfo(
                                "is_publishable",
                                "Boolean",
                            ),
                        ),
                    isLocalizable = false,
                ),
                EnumTable("vote_options", isLocalizable = false),
            ),
        "docprod" to
            listOf(
                EnumTable("dependency_conditions"),
                EnumTable("document_statuses", listOf("documents\\.status_id")),
                EnumTable("variable_injection_display_styles", listOf(".*\\.display_style_id")),
                EnumTable("variable_table_styles", listOf("variable_tables\\.table_style_id")),
                EnumTable("variable_text_types"),
                EnumTable("variable_types", listOf(".*variable_type_id")),
                EnumTable("variable_usage_types", listOf(".*\\.usage_type_id")),
                EnumTable("variable_workflow_statuses"),
            ),
        "nursery" to
            listOf(
                EnumTable(
                    "batch_quantity_history_types",
                    listOf("batch_quantity_history\\.history_type_id"),
                    isLocalizable = false,
                ),
                EnumTable("batch_substrates", listOf("nursery\\..*\\.substrate_id")),
                EnumTable(
                    "withdrawal_purposes",
                    listOf(
                        "nursery\\.withdrawals\\.purpose_id",
                        "nursery\\.withdrawal_summaries\\.purpose_id",
                    ),
                ),
            ),
        "public" to
            listOf(
                EnumTable(
                    "chat_memory_message_types",
                    listOf("chat_memory_messages\\.message_type_id"),
                    isLocalizable = false,
                ),
                EnumTable("conservation_categories", useIdAsJsonValue = true),
                EnumTable(
                    "device_template_categories",
                    listOf("device_templates\\.category_id"),
                    isLocalizable = false,
                ),
                EnumTable("ecosystem_types"),
                EnumTable("facility_connection_states", listOf("facilities\\.connection_state_id")),
                EnumTable("facility_types", listOf("facilities\\.type_id")),
                EnumTable("global_roles", isLocalizable = false),
                EnumTable("growth_forms"),
                EnumTable("land_use_model_types"),
                EnumTable("managed_location_types", isLocalizable = false),
                EnumTable("mux_asset_statuses", isLocalizable = false),
                EnumTable(
                    "notification_criticalities",
                    listOf(".*\\.notification_criticality_id"),
                    "NotificationCriticality",
                    generateForcedType = false,
                    isLocalizable = false,
                ),
                EnumTable(
                    "notification_types",
                    additionalColumns =
                        listOf(
                            EnumTableColumnInfo(
                                "notification_criticality_id",
                                "NotificationCriticality",
                                true,
                            )
                        ),
                    isLocalizable = false,
                ),
                EnumTable("organization_types", isLocalizable = false),
                EnumTable("plant_material_sourcing_methods"),
                EnumTable(
                    "project_internal_roles",
                    listOf("project_internal_users\\.project_internal_role_id"),
                ),
                EnumTable("regions", listOf("countries\\.region_id")),
                EnumTable("roles"),
                EnumTable("seed_fund_report_statuses", listOf("seed_fund_reports\\.status_id")),
                EnumTable("seed_storage_behaviors"),
                EnumTable("seed_treatments", listOf(".*\\.treatment_id")),
                EnumTable("species_problem_fields", listOf("species_problems\\.field_id")),
                EnumTable("species_native_categories"),
                EnumTable("species_problem_types", listOf("species_problems\\.type_id")),
                EnumTable("successional_groups"),
                EnumTable(
                    "timeseries_types",
                    listOf("timeseries\\.type_id"),
                    isLocalizable = false,
                ),
                EnumTable(
                    "upload_problem_types",
                    listOf("upload_problems\\.type_id"),
                    isLocalizable = false,
                ),
                EnumTable(
                    "upload_statuses",
                    listOf("uploads\\.status_id"),
                    additionalColumns = listOf(EnumTableColumnInfo("finished", "Boolean", false)),
                    isLocalizable = false,
                ),
                EnumTable(
                    "upload_types",
                    listOf("uploads\\.type_id"),
                    additionalColumns = listOf(EnumTableColumnInfo("expire_files", "Boolean")),
                    isLocalizable = false,
                ),
                EnumTable("user_types", isLocalizable = false),
                EnumTable("wood_density_levels"),
            ),
        "seedbank" to
            listOf(
                EnumTable(
                    "accession_quantity_history_types",
                    listOf("accession_quantity_history\\.history_type_id"),
                    isLocalizable = false,
                ),
                EnumTable(
                    "accession_states",
                    listOf(
                        "accessions\\.state_id",
                        ".*\\.accession_state_id",
                        "accession_state_history\\.(old|new)_state_id",
                    ),
                    additionalColumns = listOf(EnumTableColumnInfo("active", "Boolean")),
                ),
                EnumTable("collection_sources"),
                EnumTable("data_sources"),
                EnumTable("seed_quantity_units", listOf(".*\\_units_id"), "SeedQuantityUnits"),
                EnumTable("viability_test_seed_types", listOf("viability_tests\\.seed_type_id")),
                EnumTable("viability_test_substrates", listOf("viability_tests\\.substrate_id")),
                EnumTable("viability_test_types", listOf("viability_tests\\.test_type")),
                EnumTable("withdrawal_purposes", listOf("seedbank\\.withdrawals\\.purpose_id")),
            ),
        "tracking" to
            listOf(
                EnumTable(
                    "biomass_forest_types",
                    listOf("observation_biomass_details\\.forest_type_id"),
                ),
                EnumTable("mangrove_tides", listOf("observation_biomass_details\\.tide_id")),
                EnumTable(
                    "observable_conditions",
                    listOf("observation_plot_conditions\\.condition_id"),
                ),
                EnumTable(
                    "observation_photo_types",
                    listOf("observation_photos\\.type_id"),
                    isLocalizable = false,
                ),
                EnumTable("observation_plot_positions", listOf("tracking\\..*\\.position_id")),
                EnumTable(
                    "observation_plot_statuses",
                    listOf("observation_plots\\.status_id"),
                    isLocalizable = false,
                ),
                EnumTable(
                    "observation_states",
                    listOf("observations\\.state_id"),
                    isLocalizable = false,
                ),
                EnumTable("observation_types", listOf("observations\\.observation_type_id")),
                EnumTable("planting_types"),
                EnumTable(
                    "recorded_plant_statuses",
                    listOf("recorded_plants\\.status_id"),
                    isLocalizable = false,
                ),
                EnumTable(
                    "recorded_species_certainties",
                    listOf("tracking\\..*\\.certainty_id"),
                    "RecordedSpeciesCertainty",
                    isLocalizable = false,
                ),
                EnumTable("tree_growth_forms", listOf("recorded_trees\\.tree_growth_form_id")),
            ),
    )

val ID_WRAPPERS =
    mapOf(
        "accelerator" to
            listOf(
                IdWrapper("ActivityId", listOf("activities\\.id", ".*\\.activity_id")),
                IdWrapper("ApplicationHistoryId", listOf("application_histories\\.id")),
                IdWrapper("ApplicationId", listOf("applications\\.id", ".*\\.application_id")),
                IdWrapper("CohortId", listOf("cohorts\\.id", ".*\\.cohort_id")),
                IdWrapper("CohortModuleId", listOf("cohort_modules\\.id")),
                IdWrapper("DeliverableId", listOf("deliverables\\.id", ".*\\.deliverable_id")),
                IdWrapper("EventId", listOf("events\\.id", ".*\\.event_id")),
                IdWrapper("ModuleId", listOf("modules\\.id", ".*\\.module_id")),
                IdWrapper("ParticipantId", listOf("participants\\.id", ".*\\.participant_id")),
                IdWrapper(
                    "ParticipantProjectSpeciesId",
                    listOf("participant_project_species\\.id"),
                ),
                IdWrapper(
                    "ProjectMetricId",
                    listOf("project_metrics\\.id", ".*\\.project_metric_id"),
                ),
                IdWrapper(
                    "ProjectReportConfigId",
                    listOf("project_report_configs\\.id", ".*\\.config_id"),
                ),
                IdWrapper(
                    "ReportId",
                    listOf(
                        "accelerator\\.reports\\.id",
                        "accelerator\\..*\\.report_id",
                        "funder\\..*\\.report_id",
                    ),
                ),
                IdWrapper(
                    "StandardMetricId",
                    listOf("standard_metrics\\.id", ".*\\.standard_metric_id"),
                ),
                IdWrapper("SubmissionDocumentId", listOf("submission_documents\\.id")),
                IdWrapper("SubmissionId", listOf("submissions\\.id", ".*\\.submission_id")),
                IdWrapper("SubmissionSnapshotId", listOf("submission_snapshots\\.id")),
            ),
        "docprod" to
            listOf(
                IdWrapper("DocumentId", listOf("documents\\.id", ".*\\.document_id")),
                IdWrapper("DocumentSavedVersionId", listOf("document_saved_versions\\.id")),
                IdWrapper(
                    "DocumentTemplateId",
                    listOf("document_templates\\.id", ".*\\.document_template_id"),
                ),
                IdWrapper("VariableId", listOf("variables\\.id", ".*variable_id")),
                IdWrapper(
                    "VariableManifestId",
                    listOf("variable_manifests\\.id", ".*\\.variable_manifest_id"),
                ),
                IdWrapper(
                    "VariableSectionDefaultValueId",
                    listOf("variable_section_default_values\\.id"),
                ),
                IdWrapper(
                    "VariableSelectOptionId",
                    listOf(
                        "variable_select_options\\.id",
                        "variable_select_option_values\\.option_id",
                    ),
                ),
                IdWrapper("VariableValueCitationId", listOf("variable_value_citations\\.id")),
                IdWrapper(
                    "VariableValueId",
                    listOf(
                        "variable_values\\.id",
                        ".*variable_value_id",
                        ".*\\.table_row_value_id",
                    ),
                ),
                IdWrapper("VariableValueTableRowId", listOf("variable_value_table_rows\\.id")),
                IdWrapper("VariableWorkflowHistoryId", listOf("variable_workflow_history\\.id")),
            ),
        "funder" to
            listOf(
                IdWrapper(
                    "FundingEntityId",
                    listOf("funding_entities\\.id", ".*\\.funding_entity_id"),
                ),
            ),
        "nursery" to
            listOf(
                IdWrapper(
                    "BatchDetailsHistoryId",
                    listOf("batch_details_history\\.id", ".*\\.batch_details_history_id"),
                ),
                IdWrapper(
                    "BatchId",
                    listOf(
                        "batches\\.id",
                        "batch_withdrawals\\.destination_batch_id",
                        ".*\\.batch_id",
                        ".*\\.initial_batch_id",
                    ),
                ),
                IdWrapper("BatchPhotoId", listOf("batch_photos\\.id")),
                IdWrapper("BatchQuantityHistoryId", listOf("batch_quantity_history\\.id")),
                IdWrapper(
                    "WithdrawalId",
                    listOf(
                        "nursery\\.withdrawals\\.id",
                        "nursery\\.withdrawal_summaries\\.id",
                        "nursery\\..*\\.withdrawal_id",
                        "nursery\\..*\\..*_withdrawal_id",
                        "tracking\\..*\\.withdrawal_id",
                    ),
                ),
            ),
        "public" to
            listOf(
                IdWrapper("AutomationId", listOf("automations\\.id")),
                IdWrapper("BalenaDeviceId", listOf("device_managers\\.balena_id")),
                IdWrapper("ChatMemoryMessageId", listOf("chat_memory_messages\\.id")),
                IdWrapper(
                    "DeviceId",
                    listOf("devices\\.id", "devices\\.parent_id", ".*\\.device_id"),
                ),
                IdWrapper("DeviceManagerId", listOf("device_managers\\.id")),
                IdWrapper("DeviceTemplateId", listOf("device_templates\\.id")),
                IdWrapper("DisclaimerId", listOf("disclaimers\\.id", ".*\\.disclaimer_id")),
                IdWrapper(
                    "FacilityId",
                    listOf("facilities\\.id", ".*\\.destination_facility_id", ".*\\.facility_id"),
                ),
                IdWrapper("FileId", listOf("files\\.id", ".*\\.file_id")),
                IdWrapper("GbifNameId", listOf("gbif_names\\.id", ".*\\.gbif_name_id")),
                IdWrapper(
                    "GbifTaxonId",
                    listOf("gbif_taxa\\.id", "gbif_.*\\.taxon_id", "gbif_.*\\..*_usage_id"),
                ),
                IdWrapper("InternalTagId", listOf("internal_tags\\.id", ".*\\.internal_tag_id")),
                IdWrapper("NotificationId", listOf("notifications\\.id", ".*\\.notification_id")),
                IdWrapper("OrganizationId", listOf("organizations\\.id", ".*\\.organization_id")),
                IdWrapper("ProjectId", listOf("projects\\.id", ".*\\.project_id")),
                IdWrapper(
                    "SeedFundReportId",
                    listOf("public\\.seed_fund_reports\\.id", "public\\..*\\.report_id"),
                ),
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
                    ),
                ),
            ),
        "seedbank" to
            listOf(
                IdWrapper(
                    "AccessionId",
                    listOf("accessions\\.id", ".*\\.accession_id", ".*\\.seed_accession_id"),
                ),
                IdWrapper("AccessionQuantityHistoryId", listOf("accession_quantity_history\\.id")),
                IdWrapper("BagId", listOf("bags\\.id", ".*\\.bag_id")),
                IdWrapper("GeolocationId", listOf("geolocations\\.id", ".*\\.geolocation_id")),
                IdWrapper(
                    "ViabilityTestId",
                    listOf(
                        "viability_tests\\.id",
                        ".*\\.viability_test_id",
                        "viability_test_results\\.test_id",
                    ),
                ),
                IdWrapper("ViabilityTestResultId", listOf("viability_test_results\\.id")),
                IdWrapper(
                    "WithdrawalId",
                    listOf("seedbank\\.withdrawals\\.id", "seedbank\\..*\\.withdrawal_id"),
                ),
            ),
        "tracking" to
            listOf(
                IdWrapper(
                    "BiomassSpeciesId",
                    listOf("observation_biomass_species\\.id", ".*\\.biomass_species_id"),
                ),
                IdWrapper("DeliveryId", listOf("deliveries\\.id", ".*\\.delivery_id")),
                IdWrapper("DraftPlantingSiteId", listOf("draft_planting_sites\\.id")),
                IdWrapper(
                    "MonitoringPlotHistoryId",
                    listOf("monitoring_plot_histories\\.id", ".*\\.monitoring_plot_history_id"),
                ),
                IdWrapper("MonitoringPlotId", listOf("monitoring_plots\\.id", ".*\\..*_plot_id")),
                IdWrapper("ObservationId", listOf("observations\\.id", ".*\\.observation_id")),
                IdWrapper(
                    "ObservationTypeId",
                    listOf("observation_types\\.id", ".*\\.observation_type_id"),
                ),
                IdWrapper("ObservedPlotCoordinatesId", listOf("observed_plot_coordinates\\.id")),
                IdWrapper("PlantingId", listOf("plantings\\.id")),
                IdWrapper("PlantingSeasonId", listOf("planting_seasons\\.id")),
                IdWrapper(
                    "PlantingSiteHistoryId",
                    listOf("planting_site_histories\\.id", ".*\\.planting_site_history_id"),
                ),
                IdWrapper(
                    "PlantingSiteId",
                    listOf(
                        "planting_sites\\.id",
                        "planting_site_summaries\\.id",
                        ".*\\.planting_site_id",
                    ),
                ),
                IdWrapper("PlantingSiteNotificationId", listOf("planting_site_notifications\\.id")),
                IdWrapper(
                    "PlantingSubzoneHistoryId",
                    listOf("planting_subzone_histories\\.id", ".*\\.planting_subzone_history_id"),
                ),
                IdWrapper(
                    "PlantingSubzoneId",
                    listOf("planting_subzones\\.id", ".*\\.planting_subzone_id"),
                ),
                IdWrapper(
                    "PlantingZoneHistoryId",
                    listOf("planting_zone_histories\\.id", ".*\\.planting_zone_history_id"),
                ),
                IdWrapper("PlantingZoneId", listOf("planting_zones\\.id", ".*\\.planting_zone_id")),
                IdWrapper("RecordedPlantId", listOf("recorded_plants\\.id")),
                IdWrapper("RecordedTreeId", listOf("recorded_trees\\.id")),
            ),
    )

/**
 * Defines the synthetic data types that are embedded in the column lists of tables. We mostly use
 * this to represent compound primary keys.
 *
 * See https://www.jooq.org/doc/latest/manual/code-generation/codegen-embeddable-types/
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
            .withName("cohort_module_id")
            .withTables("accelerator.cohort_modules")
            .withColumns("cohort_id", "module_id"),
        EmbeddableDefinitionType()
            .withName("facility_inventory_id")
            .withTables("nursery.facility_inventories")
            .withColumns("facility_id", "species_id"),
        EmbeddableDefinitionType()
            .withName("nursery_species_project_id")
            .withTables("nursery.species_projects")
            .withColumns("species_id", "project_id"),
        EmbeddableDefinitionType()
            .withName("observation_biomass_quadrat_species_id")
            .withTables("tracking.observation_biomass_quadrat_species")
            .withColumns(
                "observation_id",
                "monitoring_plot_id",
                "position_id",
                "biomass_species_id",
            ),
        EmbeddableDefinitionType()
            .withName("observation_plot_id")
            .withTables(
                listOf(
                        "observation_biomass_details",
                        "observation_biomass_quadrat_details",
                        "observation_biomass_quadrat_species",
                        "observation_biomass_species",
                        "observation_plot_conditions",
                        "observation_plots",
                        "recorded_trees",
                    )
                    .joinToString("|", prefix = "tracking.")
            )
            .withColumns("observation_id", "monitoring_plot_id"),
        EmbeddableDefinitionType()
            .withName("organization_internal_tag_id")
            .withTables("public.organization_internal_tags")
            .withColumns("organization_id", "internal_tag_id"),
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
            .withName("project_deliverable_id")
            .withTables("project_deliverables")
            .withColumns("project_id", "deliverable_id"),
        EmbeddableDefinitionType()
            .withName("project_internal_user_id")
            .withTables("project_internal_users")
            .withColumns("project_id", "user_id"),
        EmbeddableDefinitionType()
            .withName("project_land_use_model_type_id")
            .withTables("project_land_use_model_types")
            .withColumns("project_id", "land_use_model_type_id"),
        EmbeddableDefinitionType()
            .withName("project_variable_id")
            .withTables("project_variables")
            .withColumns("project_id", "stable_id"),
        EmbeddableDefinitionType()
            .withName("species_ecosystem_id")
            .withTables("public.species_ecosystem_types")
            .withColumns("species_id", "ecosystem_type_id"),
        EmbeddableDefinitionType()
            .withName("species_growth_form_id")
            .withTables("public.species_growth_forms")
            .withColumns("species_id", "growth_form_id"),
        EmbeddableDefinitionType()
            .withName("species_successional_group_id")
            .withTables("public.species_successional_groups")
            .withColumns("species_id", "successional_group_id"),
        EmbeddableDefinitionType()
            .withName("species_plant_material_sourcing_method_id")
            .withTables("public.species_plant_material_sourcing_methods")
            .withColumns("species_id", "plant_material_sourcing_method_id"),
        EmbeddableDefinitionType()
            .withName("variable_manifest_entry_id")
            .withTables("variable_manifest_entries")
            .withColumns("variable_id", "variable_manifest_id"),
        EmbeddableDefinitionType()
            .withName("variable_owner_id")
            .withTables("variable_owners")
            .withColumns("variable_id", "project_id"),
    )
