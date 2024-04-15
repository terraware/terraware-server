-- Schema documentation. The comments here are included in the generated HTML documentation that is
-- produced by SchemaDocsGenerator.
--
-- This migration is run by Flyway whenever it changes. It runs after all the numbered migrations,
-- so it's fine to add comments here in the same commit where you're creating new tables or columns.
--
-- Comments can use Markdown syntax.
--
-- Put "(Enum)" at the beginnings of comments on tables that define a fixed set of values.

COMMENT ON TABLE seedbank.accession_collectors IS 'Names of people who collected each accession.';

COMMENT ON TABLE seedbank.accession_photos IS 'Linking table between `accessions` and `files`.';

COMMENT ON TABLE seedbank.accession_quantity_history IS 'Historical record of changes to remaining quantities of accessions.';

COMMENT ON TABLE seedbank.accession_quantity_history_types IS '(Enum) Types of operations that can result in changes to remaining quantities of accessions.';

COMMENT ON TABLE seedbank.accession_state_history IS 'Historical record of when accessions moved to different states. A row is inserted here for every state transition.';
COMMENT ON COLUMN seedbank.accession_state_history.old_state_id IS 'Null if this is the initial state for a new accession.';

COMMENT ON TABLE seedbank.accession_states IS '(Enum) Available states an accession can be in. Each state represents a step in the seed management workflow.';

COMMENT ON TABLE seedbank.accessions IS 'Information about batches of seeds. An accession is a batch of seeds of the same species collected in the same time and place by the same people.';
COMMENT ON COLUMN seedbank.accessions.latest_observed_quantity IS 'Most recent remaining quantity as observed by the user.';
COMMENT ON COLUMN seedbank.accessions.latest_observed_units_id IS 'Measurement units of `observed_quantity`.';
COMMENT ON COLUMN seedbank.accessions.latest_observed_time IS 'Time of most recent change to observed quantity.';
COMMENT ON COLUMN seedbank.accessions.number IS 'Displayed as the accession number to the user.';
COMMENT ON COLUMN seedbank.accessions.total_viability_percent IS 'Percentage of viable seeds across all tests.';
COMMENT ON COLUMN seedbank.accessions.total_withdrawn_count IS 'Total number of seeds withdrawn. May be an estimate if withdrawals were measured by weight.';
COMMENT ON COLUMN seedbank.accessions.total_withdrawn_weight_quantity IS 'Total weight of seeds withdrawn. May be an estimate if withdrawals were measured by seed count.';
COMMENT ON COLUMN seedbank.accessions.total_withdrawn_weight_units_id IS 'Measurement units of `total_withdrawn_weight_quantity`.';

COMMENT ON TABLE app_versions IS 'Minimum and recommended versions for Terraware mobile apps.';

COMMENT ON TABLE automations IS 'Configuration of automatic processes run by the device manager.';

COMMENT ON TABLE seedbank.bags IS 'Individual bags of seeds that are part of an accession. An accession can consist of multiple bags.';

COMMENT ON TABLE seedbank.collection_sources IS '(Enum) Types of source plants that seeds can be collected from.';

COMMENT ON TABLE conservation_categories IS '(Enum) IUCN conservation category codes.';

COMMENT ON TABLE countries IS 'Country information per ISO-3166.';
COMMENT ON COLUMN countries.code IS 'ISO-3166 alpha-2 country code.';
COMMENT ON COLUMN countries.name IS 'Name of country in US English.';

COMMENT ON TABLE country_subdivisions IS 'Country subdivision (state, province, region, etc.) information per ISO-3166-2.';
COMMENT ON COLUMN country_subdivisions.code IS 'Full ISO-3166-2 subdivision code including country code prefix.';
COMMENT ON COLUMN country_subdivisions.country_code IS 'ISO-3166 alpha-2 country code.';
COMMENT ON COLUMN country_subdivisions.name IS 'Name of subdivision in US English.';

COMMENT ON TABLE seedbank.data_sources IS '(Enum) Original sources of data, e.g., manual entry via web app.';

COMMENT ON TABLE device_managers IS 'Information about device managers. This is a combination of information from the Balena API and locally-generated values.';
COMMENT ON COLUMN device_managers.balena_id IS 'Balena-assigned device identifier.';
COMMENT ON COLUMN device_managers.balena_modified_time IS 'Last modification timestamp from Balena. This is distinct from `refreshed_time`, which is updated locally.';
COMMENT ON COLUMN device_managers.created_time IS 'When this device manager was added to the local database. The Balena device may have been created earlier.';
COMMENT ON COLUMN device_managers.sensor_kit_id IS 'ID code that is physically printed on the sensor kit and set as a tag value in the Balena device configuration.';
COMMENT ON COLUMN device_managers.update_progress IS 'Percent complete of software download and installation (0-100). Null if no software update is in progress.';

COMMENT ON TABLE device_template_categories IS '(Enum) User-facing categories of device templates; used to show templates for a particular class of devices where the physical device type may differ from one entry to the next.';

COMMENT ON TABLE device_templates IS 'Canned device configurations for use in cases where we want to show a list of possible devices to the user and create the selected device with the correct settings so that the device manager can talk to it.';

COMMENT ON TABLE devices IS 'Hardware devices managed by the device manager at a facility.';

COMMENT ON TABLE ecosystem_types IS '(Enum) Types of ecosystems in which plants can be found. Based on the World Wildlife Federation''s "Terrestrial Ecoregions of the World" report.';

COMMENT ON TABLE facilities IS 'Physical locations at a site. For example, each seed bank and each nursery is a facility.';
COMMENT ON COLUMN facilities.idle_after_time IS 'Time at which the facility will be considered idle if no timeseries data is received. Null if the timeseries has already been marked as idle or if no timeseries data has ever been received from the facility.';
COMMENT ON COLUMN facilities.idle_since_time IS 'Time at which the facility became idle. Null if the facility is not currently considered idle.';
COMMENT ON COLUMN facilities.last_notification_date IS 'Local date on which facility-related notifications were last generated.';
COMMENT ON COLUMN facilities.last_timeseries_time IS 'When the most recent timeseries data was received from the facility.';
COMMENT ON COLUMN facilities.max_idle_minutes IS 'Send an alert if this many minutes pass without new timeseries data from a facility''s device manager.';
COMMENT ON COLUMN facilities.next_notification_time IS 'Time at which the server should next generate notifications for the facility if any are needed.';

COMMENT ON TABLE facility_connection_states IS '(Enum) Progress of the configuration of a device manager for a facility.';

COMMENT ON TABLE facility_types IS '(Enum) Types of facilities that can be represented in the data model.';

COMMENT ON TABLE files IS 'Generic information about individual files. Files are associated with application entities using linking tables such as `accession_photos`.';

COMMENT ON TABLE flyway_schema_history IS 'Tracks which database migrations have already been applied. Used by the Flyway library, not by application.';

COMMENT ON TABLE gbif_distributions IS 'Information about geographic distribution of species and their conservation statuses.';

COMMENT ON TABLE gbif_name_words IS 'Inverted index of lower-cased words from species and family names in the GBIF backbone dataset. Used to support fast per-word prefix searches.';

COMMENT ON TABLE gbif_names IS 'Scientific and vernacular names from the GBIF backbone dataset. Names are not required to be unique.';

COMMENT ON TABLE gbif_taxa IS 'Taxonomic data about species and families. A subset of the GBIF backbone dataset.';

COMMENT ON TABLE gbif_vernacular_names IS 'Vernacular names for species and families. Part of the GBIF backbone dataset.';

COMMENT ON TABLE seedbank.geolocations IS 'Locations where seeds were collected.';

COMMENT ON TABLE global_roles IS '(Enum) System-wide roles that can be assigned to users. Global roles are not tied to organizations. These are generally for system or business administration; most users have no global roles.';

COMMENT ON TABLE growth_forms IS '(Enum) What physical form a particular species takes. For example, "Tree" or "Shrub."';

COMMENT ON TABLE identifier_sequences IS 'Current state for generating user-facing identifiers (accession number, etc.) for each organization.';

COMMENT ON TABLE internal_tags IS 'Internal (non-user-facing) tags. Low-numbered tags are defined by the system; the rest may be edited by super admins.';

COMMENT ON TABLE land_use_model_types IS '(Enum) Types of ways a project''s land can be used.';

COMMENT ON TABLE managed_location_types IS '(Enum) Type of managed location for business analytics purposes.';

COMMENT ON COLLATION natural_numeric IS 'Collation that sorts strings that contain numbers in numeric order, e.g., `a2` comes before `a10`.';

COMMENT ON TABLE notification_criticalities IS '(Enum) Criticality information of notifications in the application.';
COMMENT ON TABLE notification_types IS '(Enum) Types of notifications in the application.';
COMMENT ON TABLE notifications IS 'Notifications for application users.';

COMMENT ON TABLE organization_internal_tags IS 'Which internal (non-user-facing) tags apply to which organizations.';

COMMENT ON TABLE organization_managed_location_types IS 'Per-organization information about managed location types for business analytics purposes.';

COMMENT ON TABLE organization_report_settings IS 'Organization-level settings for quarterly reports. Project-level settings are in `project_report_settings`.';

COMMENT ON TABLE organization_types IS '(Enum) Type of forestry organization for business analytics purposes.';

COMMENT ON TABLE organization_users IS 'Organization membership and role information.';

COMMENT ON TABLE organizations IS 'Top-level information about organizations.';
COMMENT ON COLUMN organizations.id IS 'Unique numeric identifier of the organization.';
COMMENT ON COLUMN organizations.organization_type_details IS 'User provided information on the organization when type is Other, limited to 100 characters.';
COMMENT ON COLUMN organizations.website IS 'Website information for the organization with no formatting restrictions.';

COMMENT ON TABLE project_land_use_model_types IS 'Which projects have which types of land use models.';

COMMENT ON TABLE project_report_settings IS 'Which projects require reports to be submitted each quarter. Organization-level settings are in `organization_report_settings`.';

COMMENT ON TABLE projects IS 'Distinguishes among an organization''s projects.';

COMMENT ON TABLE regions IS '(Enum) Parts of the world where countries are located.';

COMMENT ON TABLE report_files IS 'Linking table between `reports` and `files` for non-photo files.';

COMMENT ON TABLE report_photos IS 'Linking table between `reports` and `files` for photos.';

COMMENT ON TABLE report_statuses IS '(Enum) Describes where in the workflow each partner report is.';

COMMENT ON TABLE reports IS 'Partner-submitted reports about their organizations and projects.';
COMMENT ON COLUMN reports.project_id IS 'If this report is for a specific project and the project still exists, the project ID. If the project has been deleted, this will be null but `project_name` will still be populated.';
COMMENT ON COLUMN reports.project_name IS 'If this report is for a specific project, the name of the project as of the time the report was submitted.';

COMMENT ON TABLE roles IS '(Enum) Roles a user is allowed to have in an organization.';

COMMENT ON TABLE seedbank.seed_quantity_units IS '(Enum) Available units in which seeds can be measured. For weight-based units, includes unit conversion information.';

COMMENT ON TABLE seed_storage_behaviors IS '(Enum) How seeds of a particular species behave in storage.';

COMMENT ON TABLE seed_treatments IS '(Enum) Techniques that can be used to treat seeds before testing them for viability.';

COMMENT ON TABLE spatial_ref_sys IS '(Enum) Metadata about spatial reference (coordinate) systems. Managed by the PostGIS extension, not the application.';

COMMENT ON TABLE species IS 'Per-organization information about species.';
COMMENT ON COLUMN species.checked_time IS 'If non-null, when the species was checked for possible suggested edits. If null, the species has not been checked yet.';

COMMENT ON TABLE species_ecosystem_types IS 'Ecosystems where each species can be found.';

COMMENT ON TABLE species_problem_fields IS '(Enum) Species fields that can be scanned for problems.';

COMMENT ON TABLE species_problem_types IS '(Enum) Specific types of problems that can be detected in species data.';

COMMENT ON TABLE species_problems IS 'Problems found in species data. Rows are deleted from this table when the problem is marked as ignored by the user or the user accepts the suggested fix.';

COMMENT ON TABLE spring_session IS 'Active login sessions. Used by Spring Session, not the application.';

COMMENT ON TABLE spring_session_attributes IS 'Data associated with a login session. Used by Spring Session, not the application.';

COMMENT ON TABLE sub_locations IS 'The available locations where seeds can be stored at a seed bank facility or seedlings can be stored at a nursery facility.';
COMMENT ON COLUMN sub_locations.name IS 'E.g., Freezer 1, Freezer 2';

COMMENT ON TABLE test_clock IS 'User-adjustable clock for test environments. Not used in production.';
COMMENT ON COLUMN test_clock.fake_time IS 'What time the server should believe it was at the time the row was written.';
COMMENT ON COLUMN test_clock.real_time IS 'What time it was in the real world when the row was written.';

COMMENT ON TABLE time_zones IS '(Enum) Valid time zone names. This is populated with the list of names from the IANA time zone database.';

COMMENT ON TABLE thumbnails IS 'Information about scaled-down versions of photos.';

COMMENT ON TABLE timeseries IS 'Properties of a series of values collected from a device. Each device metric is represented as a timeseries.';
COMMENT ON COLUMN timeseries.decimal_places IS 'For numeric timeseries, the number of digits after the decimal point to display.';
COMMENT ON COLUMN timeseries.units IS 'For numeric timeseries, the units represented by the values; unit names should be short (possibly abbreviations) and in the default language of the site.';

COMMENT ON TABLE timeseries_types IS '(Enum) Data formats of the values of a timeseries.';

COMMENT ON TABLE timeseries_values IS 'Individual data points on a timeseries. For example, each time the temperature is read from a thermometer, the reading is inserted here.';

COMMENT ON TABLE upload_problem_types IS '(Enum) Specific types of problems encountered while processing a user-uploaded file.';

COMMENT ON TABLE upload_problems IS 'Details about problems (validation failures, etc.) in user-uploaded files.';
COMMENT ON COLUMN upload_problems.position IS 'Where in the uploaded file the problem appears, or null if it is a problem with the file as a whole. This may be a byte offset, a line number, or a record number depending on the type of file.';
COMMENT ON COLUMN upload_problems.field IS 'If the problem pertains to a specific field, its name. Null if the problem affects an entire record or the entire file.';

COMMENT ON TABLE upload_statuses IS '(Enum) Available statuses of user-uploaded files. Uploads progress through these statuses as the system processes the files.';
COMMENT ON COLUMN upload_statuses.finished IS 'If true, this status means that the system is finished processing the file.';

COMMENT ON TABLE upload_types IS '(Enum) Types of user-uploaded files whose progress can be tracked in the uploads table.';
COMMENT ON COLUMN upload_types.expire_files IS 'Old rows are automatically deleted from the uploads table. If this value is true, files will also be removed from the file store for old uploads of this type.';

COMMENT ON TABLE uploads IS 'Information about the status of files uploaded by users. This is used to track the progress of file processing such as importing datafiles; contents of this table may expire and be deleted after a certain amount of time.';

COMMENT ON TABLE user_global_roles IS 'Which users have which global roles.';

COMMENT ON TABLE user_preferences IS 'Client-defined preferences that should persist across browser sessions.';
COMMENT ON COLUMN user_preferences.organization_id IS 'If null, preferences are global to the user. Otherwise, they are specific to the user and the organization.';

COMMENT ON TABLE user_types IS '(Enum) Types of users. Most users are of type 1, "Individual."';

COMMENT ON TABLE users IS 'User identities. A user can be associated with organizations via `organization_users`.';
COMMENT ON COLUMN users.auth_id IS 'Unique identifier of the user in the authentication system. Currently, this is a Keycloak user ID.';
COMMENT ON COLUMN users.email_notifications_enabled IS 'If true, the user wants to receive notifications via email.';
COMMENT ON COLUMN users.last_activity_time IS 'When the user most recently interacted with the system.';

COMMENT ON TABLE seedbank.viability_test_results IS 'Result from a viability test of a batch of seeds. Viability tests can have multiple germinations, e.g., if different seeds germinate on different days.';

COMMENT ON TABLE seedbank.viability_test_seed_types IS '(Enum) Types of seeds that can be tested for viability. This refers to how the seeds were stored, not the physical characteristics of the seeds themselves.';

COMMENT ON TABLE seedbank.viability_test_substrates IS '(Enum) Types of substrate that can be used to test seeds for viability.';

COMMENT ON TABLE seedbank.viability_test_types IS '(Enum) Types of tests that can be performed on seeds to check for viability.';

COMMENT ON TABLE seedbank.viability_tests IS 'Information about a single batch of seeds being tested for viability. This is the information about the test itself; the results are represented in the `viability_test_results` table.';

COMMENT ON TABLE seedbank.withdrawal_purposes IS '(Enum) Reasons that someone can withdraw seeds from a seed bank.';

COMMENT ON TABLE seedbank.withdrawals IS 'Information about seeds that have been withdrawn from a seed bank. Each time someone withdraws seeds, a new row is inserted here.';


COMMENT ON TABLE nursery.batch_details_history IS 'Record of changes of user-editable attributes of each nursery batch.';
COMMENT ON COLUMN nursery.batch_details_history.project_name IS 'Name of project as of the time the batch was edited. Not updated if project is later renamed.';

COMMENT ON TABLE nursery.batch_details_history_sub_locations IS 'Record of changes to sub-locations of each nursery batch.';
COMMENT ON COLUMN nursery.batch_details_history_sub_locations.sub_location_name IS 'Name of sub-location as of the time the batch was edited. Not updated if sub-location is later renamed.';

COMMENT ON TABLE nursery.batch_photos IS 'Information about photos of batches.';
COMMENT ON COLUMN nursery.batch_photos.file_id IS 'File ID if the photo exists. Null if the photo has been deleted.';

COMMENT ON TABLE nursery.batch_quantity_history IS 'Record of changes of seedling quantities in each nursery batch.';
COMMENT ON COLUMN nursery.batch_quantity_history.batch_id IS 'Which batch''s quantities were changed.';
COMMENT ON COLUMN nursery.batch_quantity_history.created_by IS 'Which user triggered the change in quantities. "Created" here refers to the history row, not the batch.';
COMMENT ON COLUMN nursery.batch_quantity_history.created_time IS 'When the change in quantities happened. "Created" here refers to the history row, not the batch.';
COMMENT ON COLUMN nursery.batch_quantity_history.germinating_quantity IS 'New number of germinating seedlings in the batch.';
COMMENT ON COLUMN nursery.batch_quantity_history.history_type_id IS 'Type of operation that resulted in the change in quantities.';
COMMENT ON COLUMN nursery.batch_quantity_history.not_ready_quantity IS 'New number of not-ready-for-planting seedlings in the batch.';
COMMENT ON COLUMN nursery.batch_quantity_history.ready_quantity IS 'New number of ready-for-planting seedlings in the batch.';
COMMENT ON COLUMN nursery.batch_quantity_history.withdrawal_id IS 'If this change in quantity was due to a withdrawal from the batch, the withdrawal''s ID.';

COMMENT ON TABLE nursery.batch_quantity_history_types IS '(Enum) Types of operations that can result in changes to remaining quantities of seedling batches.';

COMMENT ON TABLE nursery.batch_sub_locations IS 'Which batches are stored in which sub-locations.';

COMMENT ON TABLE nursery.batch_substrates IS '(Enum) Substrates in which seedlings can be planted in a nursery.';

COMMENT ON TABLE nursery.batch_withdrawals IS 'Number of seedlings withdrawn from each originating batch as part of a withdrawal.';
COMMENT ON COLUMN nursery.batch_withdrawals.batch_id IS 'The batch from which the seedlings were withdrawn, also referred to as the originating batch.';
COMMENT ON COLUMN nursery.batch_withdrawals.destination_batch_id IS 'If the withdrawal was a nursery transfer, the batch that was created as a result. A withdrawal can have more than one originating batch; if they are of the same species, only one destination batch will be created and there will be multiple rows with the same `destination_batch_id`. May be null if the batch was subsequently deleted.';
COMMENT ON COLUMN nursery.batch_withdrawals.germinating_quantity_withdrawn IS 'Number of germinating seedlings that were withdrawn from this batch. This is not necessarily the total number of seedlings in the withdrawal as a whole since a withdrawal can come from multiple batches.';
COMMENT ON COLUMN nursery.batch_withdrawals.not_ready_quantity_withdrawn IS 'Number of not-ready-for-planting seedlings that were withdrawn from this batch. This is not necessarily the total number of seedlings in the withdrawal as a whole since a withdrawal can come from multiple batches.';
COMMENT ON COLUMN nursery.batch_withdrawals.ready_quantity_withdrawn IS 'Number of ready-for-planting seedlings that were withdrawn from this batch. This is not necessarily the total number of seedlings in the withdrawal as a whole since a withdrawal can come from multiple batches.';
COMMENT ON COLUMN nursery.batch_withdrawals.withdrawal_id IS 'The withdrawal that removed seedlings from this batch. A withdrawal can come from multiple batches, in which case there will be more than one `batch_withdrawals` row with the same withdrawal ID.';

COMMENT ON TABLE nursery.batches IS 'Information about batches of seedlings at nurseries.';
COMMENT ON COLUMN nursery.batches.accession_id IS 'If the batch was created by a nursery transfer from a seed bank, the originating accession ID.';
COMMENT ON COLUMN nursery.batches.added_date IS 'User-supplied date the batch was added to the nursery''s inventory.';
COMMENT ON COLUMN nursery.batches.batch_number IS 'User-friendly unique (per organization) identifier for the batch. Not used internally or in API; "id" is the internal identifier.';
COMMENT ON COLUMN nursery.batches.created_by IS 'Which user initially created the batch.';
COMMENT ON COLUMN nursery.batches.created_time IS 'When the batch was initially created.';
COMMENT ON COLUMN nursery.batches.facility_id IS 'Which nursery contains the batch. Facility must be of type "Nursery" and under the same organization as the species ID (enforced in application code).';
COMMENT ON COLUMN nursery.batches.germinating_quantity IS 'Number of germinating seedlings currently available in inventory. Withdrawals cause this to decrease.';
COMMENT ON COLUMN nursery.batches.id IS 'Globally-unique internal identifier for the batch. Not typically presented to end users; "batch_number" is the user-facing identifier.';
COMMENT ON COLUMN nursery.batches.latest_observed_germinating_quantity IS 'Latest user-observed number of germinating seedlings currently available in inventory. Withdrawals do not cause this to decrease.';
COMMENT ON COLUMN nursery.batches.latest_observed_not_ready_quantity IS 'Latest user-observed number of not-ready-for-planting seedlings currently available in inventory. Withdrawals do not cause this to decrease.';
COMMENT ON COLUMN nursery.batches.latest_observed_ready_quantity IS 'Latest user-observed number of ready-for-planting seedlings currently available in inventory. Withdrawals do not cause this to decrease.';
COMMENT ON COLUMN nursery.batches.latest_observed_time IS 'When the latest user observation of seedling quantities took place.';
COMMENT ON COLUMN nursery.batches.modified_by IS 'Which user most recently modified the batch, either directly or by creating a withdrawal.';
COMMENT ON COLUMN nursery.batches.modified_time IS 'When the batch was most recently modified, either directly or by creating a withdrawal.';
COMMENT ON COLUMN nursery.batches.notes IS 'User-supplied freeform notes about batch.';
COMMENT ON COLUMN nursery.batches.not_ready_quantity IS 'Number of not-ready-for-planting seedlings currently available in inventory. Withdrawals cause this to decrease.';
COMMENT ON COLUMN nursery.batches.organization_id IS 'Which organization owns the nursery where this batch is located.';
COMMENT ON COLUMN nursery.batches.ready_by_date IS 'User-supplied estimate of when the batch will be ready for planting.';
COMMENT ON COLUMN nursery.batches.ready_quantity IS 'Number of ready-for-planting seedlings currently available in inventory. Withdrawals cause this to decrease.';
COMMENT ON COLUMN nursery.batches.species_id IS 'Species of the batch''s plants. Must be under the same organization as the facility ID (enforced in application code).';
COMMENT ON COLUMN nursery.batches.total_germinated IS 'Total number of seedlings that have moved from Germinating to Not Ready status over the lifetime of the batch. This is the numerator for the germination rate calculation.';
COMMENT ON COLUMN nursery.batches.total_germination_candidates IS 'Total number of seedlings that have been candidates for moving from Germinating to Not Ready status. This includes seedlings that are already germinated and germinating seedlings that were withdrawn as Dead, but does not include germinating seedlings that were withdrawn for other reasons. This is the denominator for the germination rate calculation.';
COMMENT ON COLUMN nursery.batches.total_loss_candidates IS 'Total number of non-germinating (Not Ready and Ready) seedlings that have been candidates for being withdrawn as dead. This includes seedlings that are still in the batch, seedlings that were withdrawn for outplanting, and seedlings that were already withdrawn as dead, but does not include germinating seedlings or seedlings that were withdrawn for other reasons. This is the denominator for the loss rate calculation.';
COMMENT ON COLUMN nursery.batches.total_lost IS 'Total number of non-germinating (Not Ready and Ready) seedlings that have been withdrawn as Dead. This is the numerator for the loss rate calculation.';
COMMENT ON COLUMN nursery.batches.version IS 'Increases by 1 each time the batch is modified. Used to detect when clients have stale data about batches.';

COMMENT ON VIEW nursery.species_projects IS 'Which species have active batches associated with which projects.';

COMMENT ON TABLE nursery.withdrawal_photos IS 'Linking table between `withdrawals` and `files`.';

COMMENT ON TABLE nursery.withdrawal_purposes IS '(Enum) Reasons that someone can withdraw seedlings from a nursery.';

COMMENT ON TABLE nursery.withdrawals IS 'Top-level information about a withdrawal from a nursery. Does not contain withdrawal quantities; those are in the `batch_withdrawals` table.';
COMMENT ON COLUMN nursery.withdrawals.created_by IS 'Which user created the withdrawal.';
COMMENT ON COLUMN nursery.withdrawals.created_time IS 'When the withdrawal was created.';
COMMENT ON COLUMN nursery.withdrawals.destination_facility_id IS 'If the withdrawal was a nursery transfer, the facility where the seedlings were sent. May be null if the facility was subsequently deleted.';
COMMENT ON COLUMN nursery.withdrawals.facility_id IS 'Nursery from which the seedlings were withdrawn.';
COMMENT ON COLUMN nursery.withdrawals.modified_by IS 'Which user most recently modified the withdrawal.';
COMMENT ON COLUMN nursery.withdrawals.modified_time IS 'When the withdrawal was most recently modified.';
COMMENT ON COLUMN nursery.withdrawals.notes IS 'User-supplied freeform text describing the withdrawal.';
COMMENT ON COLUMN nursery.withdrawals.purpose_id IS 'Purpose of the withdrawal (nursery transfer, dead seedlings, etc.)';
COMMENT ON COLUMN nursery.withdrawals.withdrawn_date IS 'User-supplied date when the seedlings were withdrawn.';

COMMENT ON VIEW nursery.withdrawal_summaries IS 'Withdrawal information including aggregated and calculated values that need to be made available as filter and sort keys.';


COMMENT ON TABLE tracking.deliveries IS 'Incoming deliveries of new seedlings to a planting site. Mostly exists to link plantings and nursery withdrawals.';
COMMENT ON COLUMN tracking.deliveries.created_by IS 'Which user created the delivery.';
COMMENT ON COLUMN tracking.deliveries.created_time IS 'When the delivery was created.';
COMMENT ON COLUMN tracking.deliveries.modified_by IS 'Which user most recently modified the delivery.';
COMMENT ON COLUMN tracking.deliveries.modified_time IS 'When the delivery was most recently modified.';
COMMENT ON COLUMN tracking.deliveries.planting_site_id IS 'Which planting site received the delivery.';
COMMENT ON COLUMN tracking.deliveries.reassigned_by IS 'Which user recorded the reassignment of plants in this delivery. Null if this delivery has no reassignment.';
COMMENT ON COLUMN tracking.deliveries.reassigned_time IS 'When the reassignment was recorded. Null if this delivery has no reassignment.';
COMMENT ON COLUMN tracking.deliveries.withdrawal_id IS 'Which nursery withdrawal the plants came from.';

COMMENT ON TABLE tracking.draft_planting_sites IS 'Details of planting sites that are in the process of being defined.';
COMMENT ON COLUMN tracking.draft_planting_sites.data IS 'Client-defined state of the definition of the planting site. This may include a mix of map data and application state and is treated as opaque by the server.';
COMMENT ON COLUMN tracking.draft_planting_sites.num_planting_subzones is 'Number of planting subzones defined so far.';
COMMENT ON COLUMN tracking.draft_planting_sites.num_planting_zones is 'Number of planting zones defined so far.';

COMMENT ON TABLE tracking.monitoring_plots IS 'Regions within planting subzones that can be comprehensively surveyed in order to extrapolate results for the entire zone. Any monitoring plot in a subzone is expected to have roughly the same number of plants of the same species as any other monitoring plot in the same subzone.';
COMMENT ON COLUMN tracking.monitoring_plots.boundary IS 'Boundary of the monitoring plot. Coordinates always use SRID 4326 (WGS 84 latitude/longitude).';
COMMENT ON COLUMN tracking.monitoring_plots.created_by IS 'Which user created the monitoring plot.';
COMMENT ON COLUMN tracking.monitoring_plots.created_time IS 'When the monitoring plot was originally created.';
COMMENT ON COLUMN tracking.monitoring_plots.modified_by IS 'Which user most recently modified the monitoring plot.';
COMMENT ON COLUMN tracking.monitoring_plots.modified_time IS 'When the monitoring plot was most recently modified.';
COMMENT ON COLUMN tracking.monitoring_plots.permanent_cluster IS 'If this plot is a candidate to be a permanent monitoring plot, its position in the randomized list of plots for the planting zone. Starts at 1 for each planting zone. There are always 4 plots with a given sequence number in a given zone. If null, this plot is not part of a 4-plot cluster but may still be chosen as a temporary monitoring plot.';
COMMENT ON COLUMN tracking.monitoring_plots.permanent_cluster_subplot IS 'If this plot is a candidate to be a permanent monitoring plot, its ordinal position from 1 to 4 in the 4-plot cluster. 1=southwest, 2=southeast, 3=northeast, 4=northwest.';
COMMENT ON COLUMN tracking.monitoring_plots.planting_subzone_id IS 'Which planting subzone this monitoring plot is part of.';

COMMENT ON TABLE tracking.observable_conditions IS '(Enum) Conditions that can be observed in a monitoring plot.';

COMMENT ON TABLE tracking.observation_photos IS 'Observation-specific details about a photo of a monitoring plot. Generic metadata is in the `files` table.';

COMMENT ON TABLE tracking.observation_plot_conditions IS 'List of conditions observed in each monitoring plot.';

COMMENT ON TABLE tracking.observation_plot_positions IS '(Enum) Positions in a monitoring plot where users can take photos or record coordinates.';

COMMENT ON TABLE tracking.observation_plots IS 'Information about monitoring plots that are required to be surveyed as part of observations. This is not populated until the scheduled start time of the observation.';
COMMENT ON COLUMN tracking.observation_plots.completed_time IS 'Server-generated completion date and time. This is the time the observation was submitted to the server, not the time it was performed in the field.';
COMMENT ON COLUMN tracking.observation_plots.is_permanent IS 'If true, this plot was selected for observation as part of a permanent monitoring plot cluster. If false, this plot was selected as a temporary monitoring plot.';
COMMENT ON COLUMN tracking.observation_plots.observed_time IS 'Client-supplied observation date and time. This is the time the observation was performed in the field, not the time it was submitted to the server.';

COMMENT ON TABLE tracking.observation_states IS '(Enum) Where in the observation lifecycle a particular observation is.';

COMMENT ON TABLE tracking.observations IS 'Scheduled observations of planting sites. This table may contain rows describing future observations as well as current and past ones.';
COMMENT ON COLUMN tracking.observations.completed_time IS 'Server-generated date and time the final piece of data for the observation was received.';
COMMENT ON COLUMN tracking.observations.end_date IS 'Last day of the observation. This is typically the last day of the same month as `start_date`.';
COMMENT ON COLUMN tracking.observations.start_date IS 'First day of the observation. This is either the first day of the month following the end of the planting season, or 6 months after that day.';
COMMENT ON COLUMN tracking.observations.upcoming_notification_sent_time IS 'When the notification that the observation is starting in 1 month was sent. Null if the notification has not been sent yet.';

COMMENT ON TABLE tracking.observed_plot_coordinates IS 'Observed GPS coordinates in monitoring plots. Does not include photo coordinates or coordinates of recorded plants.';

COMMENT ON TABLE tracking.observed_plot_species_totals IS 'Aggregated per-monitoring-plot, per-species totals of plants recorded during observations.';
COMMENT ON COLUMN tracking.observed_plot_species_totals.cumulative_dead IS 'If this is a permanent monitoring plot, total number of dead plants observed in all observations including the current one.';
COMMENT ON COLUMN tracking.observed_plot_species_totals.mortality_rate IS 'If this is a permanent monitoring plot, percentage of plants of the species observed in this plot, in either this observation or in previous ones, that were dead. Null if this is not a permanent monitoring plot in the current observation.';
COMMENT ON COLUMN tracking.observed_plot_species_totals.permanent_live IS 'If this is a permanent monitoring plot, the number of live and existing plants observed. 0 otherwise.';

COMMENT ON TABLE tracking.observed_site_species_totals IS 'Aggregated per-planting-site, per-species totals of plants recorded during observations.';
COMMENT ON COLUMN tracking.observed_site_species_totals.cumulative_dead IS 'Total number of dead plants of the species observed, both in this observation and in all previous ones, in plots that are included as permanent plots in this observation.';
COMMENT ON COLUMN tracking.observed_site_species_totals.mortality_rate IS 'Percentage of plants of the species observed in permanent monitoring plots in the planting site, in either this observation or in previous ones, that were dead.';
COMMENT ON COLUMN tracking.observed_site_species_totals.permanent_live IS 'The number of live and existing plants observed in permanent monitoring plots.';

COMMENT ON TABLE tracking.observed_zone_species_totals IS 'Aggregated per-planting-zone, per-species totals of plants recorded during observations.';
COMMENT ON COLUMN tracking.observed_zone_species_totals.cumulative_dead IS 'Total number of dead plants of the species observed, both in this observation and in all previous ones, in plots in this zone that are included as permanent plots in this observation.';
COMMENT ON COLUMN tracking.observed_zone_species_totals.mortality_rate IS 'Percentage of plants of the species observed in permanent monitoring plots in the planting zone, in either the current observation or in previous ones, that were dead.';
COMMENT ON COLUMN tracking.observed_zone_species_totals.permanent_live IS 'The number of live and existing plants observed in permanent monitoring plots.';

COMMENT ON TABLE tracking.planting_seasons IS 'Start and end dates of planting seasons for planting sites.';
COMMENT ON COLUMN tracking.planting_seasons.end_date IS 'What day the planting season ends. This is the last day of the season, not the day after the season, that is, if the planting season is the month of January, this will be January 31, not February 1.';
COMMENT ON COLUMN tracking.planting_seasons.end_time IS 'When the planting season will be finished. This is midnight on the day after `end_date` in the planting site''s time zone.';
COMMENT ON COLUMN tracking.planting_seasons.is_active IS 'True if the planting season is currently in progress. Only one planting season can be active at a time for a planting site.';
COMMENT ON COLUMN tracking.planting_seasons.start_date IS 'What day the planting season starts.';
COMMENT ON COLUMN tracking.planting_seasons.start_time IS 'When the planting season will start. This is midnight on `start_date` in the planting site''s time zone.';

COMMENT ON TABLE tracking.planting_site_notifications IS 'Tracks which notifications have already been sent regarding planting sites.';
COMMENT ON COLUMN tracking.planting_site_notifications.notification_number IS 'Number of notifications of this type that have been sent, including this one. 1 for initial notification, 2 for reminder, 3 for second reminder, etc.';

COMMENT ON TABLE tracking.planting_site_populations IS 'Total number of plants of each species in each planting site.';

COMMENT ON TABLE tracking.planting_sites IS 'Top-level information about entire planting sites. Every planting site has at least one planting zone.';
COMMENT ON COLUMN tracking.planting_sites.boundary IS 'Boundary of the entire planting site. Planting zones will generally fall inside this boundary. This will typically be a single polygon but may be multiple polygons if a planting site has several disjoint areas. Coordinates always use SRID 4326 (WGS 84 latitude/longitude).';
COMMENT ON COLUMN tracking.planting_sites.exclusion IS 'Optional area to exclude from a site. No monitoring plots will be located in this area.';
COMMENT ON COLUMN tracking.planting_sites.created_by IS 'Which user created the planting site.';
COMMENT ON COLUMN tracking.planting_sites.created_time IS 'When the planting site was originally created.';
COMMENT ON COLUMN tracking.planting_sites.description IS 'Optional user-supplied description of the planting site.';
COMMENT ON COLUMN tracking.planting_sites.grid_origin IS 'Coordinates of the origin point of the grid of monitoring plots. Monitoring plot corners have X and Y coordinates that are multiples of 25 meters from the origin point.';
COMMENT ON COLUMN tracking.planting_sites.modified_by IS 'Which user most recently modified the planting site.';
COMMENT ON COLUMN tracking.planting_sites.modified_time IS 'When the planting site was most recently modified.';
COMMENT ON COLUMN tracking.planting_sites.name IS 'Short name of this planting site. Must be unique within the organization.';
COMMENT ON COLUMN tracking.planting_sites.organization_id IS 'Which organization owns this planting site.';

COMMENT ON TABLE tracking.planting_types IS '(Enum) Type of planting associated with a delivery. Different planting types distinguish reassignments from initial plantings.';

COMMENT ON TABLE tracking.planting_subzones IS 'Regions within planting zones that are a convenient size for a planting operation. Typically <10Ha.';
COMMENT ON COLUMN tracking.planting_subzones.boundary IS 'Boundary of the subzone. Coordinates always use SRID 4326 (WGS 84 latitude/longitude).';
COMMENT ON COLUMN tracking.planting_subzones.created_by IS 'Which user created the subzone.';
COMMENT ON COLUMN tracking.planting_subzones.created_time IS 'When the subzone was originally created.';
COMMENT ON COLUMN tracking.planting_subzones.modified_by IS 'Which user most recently modified the subzone.';
COMMENT ON COLUMN tracking.planting_subzones.modified_time IS 'When the subzone was most recently modified.';
COMMENT ON COLUMN tracking.planting_subzones.name IS 'Short name of this planting subzone. This is often just a single letter and number. Must be unique within a planting zone.';
COMMENT ON COLUMN tracking.planting_subzones.planting_site_id IS 'Which planting site this subzone is part of. This is the same as the planting site ID of this subzone''s planting zone, but is duplicated here so it can be used as the target of a foreign key constraint.';
COMMENT ON COLUMN tracking.planting_subzones.planting_zone_id IS 'Which planting zone this subzone is part of.';

COMMENT ON TABLE tracking.planting_subzone_populations IS 'Total number of plants of each species in each subzone.';

COMMENT ON TABLE tracking.planting_zones IS 'Regions within planting sites that have a consistent set of conditions such that survey results from any part of the zone can be extrapolated to the entire zone. Planting zones are subdivided into plots. Every planting zone has at least one plot.';
COMMENT ON COLUMN tracking.planting_zones.boundary IS 'Boundary of the planting zone. This area is further subdivided into plots. This will typically be a single polygon but may be multiple polygons if a planting zone has several disjoint areas. Coordinates always use SRID 4326 (WGS 84 latitude/longitude).';
COMMENT ON COLUMN tracking.planting_zones.created_by IS 'Which user created the planting zone.';
COMMENT ON COLUMN tracking.planting_zones.created_time IS 'When the planting zone was originally created.';
COMMENT ON COLUMN tracking.planting_zones.extra_permanent_clusters IS 'Number of clusters to add to observation in addition to the number that is derived from the statistical formula. Typically this is due to additional area being added to a zone after initial creation. This is included in the value of `num_permanent_clusters`, that is, it is an input to the calculation of that column''s value.';
COMMENT ON COLUMN tracking.planting_zones.modified_by IS 'Which user most recently modified the planting zone.';
COMMENT ON COLUMN tracking.planting_zones.modified_time IS 'When the planting zone was most recently modified.';
COMMENT ON COLUMN tracking.planting_zones.name IS 'Short name of this planting zone. This is often just a single letter. Must be unique within a planting site.';
COMMENT ON COLUMN tracking.planting_zones.num_permanent_clusters IS 'Number of permanent clusters to assign to the next observation. This is typically derived from a statistical formula and from `extra_permanent_clusters`.';
COMMENT ON COLUMN tracking.planting_zones.planting_site_id IS 'Which planting site this zone is part of.';

COMMENT ON TABLE tracking.planting_zone_populations IS 'Total number of plants of each species in each zone.';

COMMENT ON TABLE tracking.plantings IS 'Details about plants that were planted or reassigned as part of a delivery. There is one plantings row per species in a delivery.';
COMMENT ON COLUMN tracking.plantings.created_by IS 'Which user created the planting.';
COMMENT ON COLUMN tracking.plantings.created_time IS 'When the planting was created. Note that plantings are never updated, so there is no modified time.';
COMMENT ON COLUMN tracking.plantings.delivery_id IS 'Which delivery this planting is part of.';
COMMENT ON COLUMN tracking.plantings.notes IS 'Notes about this specific planting. In the initial version of the web app, the user can only enter per-planting notes for reassignments, not for initial deliveries.';
COMMENT ON COLUMN tracking.plantings.num_plants IS 'Number of plants that were planted (if the number is positive) or reassigned (if the number is negative).';
COMMENT ON COLUMN tracking.plantings.planting_site_id IS 'Which planting site has the planting. Must be the same as the planting site ID of the delivery. This identifies the site as a whole; in addition, there may be a plot ID.';
COMMENT ON COLUMN tracking.plantings.planting_type_id IS 'Whether this is the plant assignment from the initial delivery or an adjustment from a reassignment.';
COMMENT ON COLUMN tracking.plantings.planting_subzone_id IS 'Which plot this planting affected, if any. Must be a plot at the planting site referenced by `planting_site_id`. Null if the planting site does not have plot information. For reassignments, this is the original plot if `num_plants` is negative, or the new plot if `num_plants` is positive.';
COMMENT ON COLUMN tracking.plantings.species_id IS 'Which species was planted.';

COMMENT ON TABLE tracking.recorded_plant_statuses IS '(Enum) Possible statuses of a plant recorded during observation of a monitoring plot.';

COMMENT ON TABLE tracking.recorded_plants IS 'Information about individual plants observed in monitoring plots.';
COMMENT ON COLUMN tracking.recorded_plants.species_id IS 'If certainty is "Known," the ID of the plant''s species. Null for other certainty values.';
COMMENT ON COLUMN tracking.recorded_plants.species_name IS 'If certainty is "Other," the user-supplied name of the plant''s species. Null for other certainty values.';

COMMENT ON TABLE tracking.recorded_species_certainties IS '(Enum) Levels of certainty about the identity of a species recorded in a monitoring plot observation.';

COMMENT ON CONSTRAINT num_plants_sign_consistent_with_type ON tracking.plantings IS 'If the planting represents the "from" side of a reassignment or an undo of a withdrawal, the number of plants must be negative. Otherwise it must be positive.';

COMMENT ON TABLE accelerator.cohorts IS 'Accelerator cohort details.';

COMMENT ON TABLE accelerator.cohort_modules IS 'Which modules are assigned to which cohorts.';

COMMENT ON TABLE accelerator.cohort_phases IS '(Enum) Available cohort phases';

COMMENT ON TABLE accelerator.deal_stages IS '(Enum) Stages in the deal workflow that a project progresses through.';

COMMENT ON TABLE accelerator.default_voters IS 'Users to automatically be assigned as voters on accelerator projects.';

COMMENT ON TABLE accelerator.deliverable_categories IS '(Enum) High-level groups for organizing deliverables.';

COMMENT ON TABLE accelerator.deliverable_documents IS 'Information about expected deliverables of type Document that isn''t relevant for other deliverable types.';

COMMENT ON TABLE accelerator.deliverable_types IS '(Enum) Types of deliverables for an accelerator module.';

COMMENT ON TABLE accelerator.deliverables IS 'Information about expected deliverables. This describes what we request from users; the data we get back from users in response is recorded in `project_deliverables` and its child tables.';
COMMENT ON COLUMN accelerator.deliverables.position IS 'Which position this deliverable appears in the module''s list of deliverables, starting with 1.';
COMMENT ON COLUMN accelerator.deliverables.is_sensitive IS 'If true, the data users provide in response to this deliverable will be visible to a smaller subset of accelerator admins. Secure documents are saved to a different document store than non-secure ones.';

COMMENT ON TABLE accelerator.document_stores IS '(Enum) Locations where uploaded documents are stored.';

COMMENT ON TABLE accelerator.event_projects IS 'Projects that are participants of an event.';

COMMENT ON TABLE accelerator.event_types IS '(Enum) Types of events for an accelerator module';

COMMENT ON TABLE accelerator.events IS 'Events with meeting links and time within an acclerator module.';

COMMENT ON TABLE accelerator.modules IS 'Possible steps in the workflow of a cohort phase.';

COMMENT ON TABLE accelerator.participants IS 'Accelerator participant details.';

COMMENT ON TABLE accelerator.pipelines IS '(Enum) Deal pipelines for accelerator projects.';

COMMENT ON TABLE accelerator.project_accelerator_details IS 'Details about projects that are only relevant for accelerator applicants. The values here are for internal use, not exposed to end users.';
COMMENT ON COLUMN accelerator.project_accelerator_details.file_naming IS 'Identifier that is included in generated filenames. This is often, but not necessarily, the same as the project name.';

COMMENT ON TABLE accelerator.project_scores IS 'Scores assigned to project by scorers.';
COMMENT ON COLUMN accelerator.project_scores.score IS 'Integer score between -2 to 2. The score can be null to represent not yet scored. ';

COMMENT ON TABLE accelerator.project_votes IS 'Vote selected by voters.';
COMMENT ON COLUMN accelerator.project_votes.vote_option_id IS 'Vote option can be Yes/No/Conditional. The vote can be null to represent not yet voted. ';

COMMENT ON TABLE accelerator.project_vote_decisions IS 'Calculated vote decisions for project.';

COMMENT ON TABLE accelerator.score_categories IS '(Enum) Project score categories.';

COMMENT ON TABLE accelerator.submission_documents IS 'Information about documents uploaded by users to satisfy deliverables. A deliverable can have multiple documents.';
COMMENT ON COLUMN accelerator.submission_documents.name IS 'System-generated filename. The file is stored using this name in the document store. This includes several elements such as the date and description.';
COMMENT ON COLUMN accelerator.submission_documents.location IS 'Location of file in the document store identified by `document_store_id`. This is used by the system to generate download links and includes whatever information is needed to generate a link for a given document store; if the document store supports permalinks then this may be a simple URL.';
COMMENT ON COLUMN accelerator.submission_documents.original_name IS 'Original filename as supplied by the client when the document was uploaded. Not required to be unique since the user can upload revised versions of documents.';

COMMENT ON TABLE accelerator.submission_statuses IS '(Enum) Statuses of submissions of deliverables by specific projects.';

COMMENT ON TABLE accelerator.submissions IS 'Information about the current states of the information supplied by specific projects in response to deliverables.';

COMMENT ON TABLE accelerator.vote_options IS '(Enum) Available vote options.';

-- When adding new tables, put them in alphabetical (ASCII) order.
