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

COMMENT ON TABLE growth_forms IS '(Enum) What physical form a particular species takes. For example, "Tree" or "Shrub."';

COMMENT ON TABLE identifier_sequences IS 'Current state for generating user-facing identifiers (accession number, etc.) for each organization.';

COMMENT ON TABLE internal_tags IS 'Internal (non-user-facing) tags. Low-numbered tags are defined by the system; the rest may be edited by super admins.';

COMMENT ON COLLATION natural_numeric IS 'Collation that sorts strings that contain numbers in numeric order, e.g., `a2` comes before `a10`.';

COMMENT ON TABLE notification_criticalities IS '(Enum) Criticality information of notifications in the application.';
COMMENT ON TABLE notification_types IS '(Enum) Types of notifications in the application.';
COMMENT ON TABLE notifications IS 'Notifications for application users.';

COMMENT ON TABLE organization_internal_tags IS 'Which internal (non-user-facing) tags apply to which organizations.';

COMMENT ON TABLE organization_users IS 'Organization membership and role information.';

COMMENT ON TABLE organizations IS 'Top-level information about organizations.';
COMMENT ON COLUMN organizations.id IS 'Unique numeric identifier of the organization.';

COMMENT ON TABLE report_files IS 'Linking table between `reports` and `files` for non-photo files.';

COMMENT ON TABLE report_photos IS 'Linking table between `reports` and `files` for photos.';

COMMENT ON TABLE report_statuses IS '(Enum) Describes where in the workflow each partner report is.';

COMMENT ON TABLE reports IS 'Partner-submitted reports about their projects.';

COMMENT ON TABLE roles IS '(Enum) Roles a user is allowed to have in an organization.';

COMMENT ON TABLE seedbank.seed_quantity_units IS '(Enum) Available units in which seeds can be measured. For weight-based units, includes unit conversion information.';

COMMENT ON TABLE seed_storage_behaviors IS '(Enum) How seeds of a particular species behave in storage.';

COMMENT ON TABLE spatial_ref_sys IS '(Enum) Metadata about spatial reference (coordinate) systems. Managed by the PostGIS extension, not the application.';

COMMENT ON TABLE species IS 'Per-organization information about species.';
COMMENT ON COLUMN species.checked_time IS 'If non-null, when the species was checked for possible suggested edits. If null, the species has not been checked yet.';

COMMENT ON TABLE species_ecosystem_types IS 'Ecosystems where each species can be found.';

COMMENT ON TABLE species_problem_fields IS '(Enum) Species fields that can be scanned for problems.';

COMMENT ON TABLE species_problem_types IS '(Enum) Specific types of problems that can be detected in species data.';

COMMENT ON TABLE species_problems IS 'Problems found in species data. Rows are deleted from this table when the problem is marked as ignored by the user or the user accepts the suggested fix.';

COMMENT ON TABLE spring_session IS 'Active login sessions. Used by Spring Session, not the application.';

COMMENT ON TABLE spring_session_attributes IS 'Data associated with a login session. Used by Spring Session, not the application.';

COMMENT ON TABLE seedbank.storage_locations IS 'The available locations where seeds can be stored at a seed bank facility.';
COMMENT ON COLUMN seedbank.storage_locations.name IS 'E.g., Freezer 1, Freezer 2';

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

COMMENT ON TABLE seedbank.viability_test_treatments IS '(Enum) Techniques that can be used to treat seeds before testing them for viability.';

COMMENT ON TABLE seedbank.viability_test_types IS '(Enum) Types of tests that can be performed on seeds to check for viability.';

COMMENT ON TABLE seedbank.viability_tests IS 'Information about a single batch of seeds being tested for viability. This is the information about the test itself; the results are represented in the `viability_test_results` table.';

COMMENT ON TABLE seedbank.withdrawal_purposes IS '(Enum) Reasons that someone can withdraw seeds from a seed bank.';

COMMENT ON TABLE seedbank.withdrawals IS 'Information about seeds that have been withdrawn from a seed bank. Each time someone withdraws seeds, a new row is inserted here.';


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
COMMENT ON COLUMN nursery.batches.version IS 'Increases by 1 each time the batch is modified. Used to detect when clients have stale data about batches.';

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

COMMENT ON TABLE tracking.observation_photo_positions IS '(Enum) Positions in a monitoring plot from which users are asked to take photos.';

COMMENT ON TABLE tracking.observation_photos IS 'Observation-specific details about a photo of a monitoring plot. Generic metadata is in the `files` table.';

COMMENT ON TABLE tracking.observation_plot_conditions IS 'List of conditions observed in each monitoring plot.';

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

COMMENT ON TABLE tracking.planting_site_populations IS 'Total number of plants of each species in each planting site.';

COMMENT ON TABLE tracking.planting_sites IS 'Top-level information about entire planting sites. Every planting site has at least one planting zone.';
COMMENT ON COLUMN tracking.planting_sites.boundary IS 'Boundary of the entire planting site. Planting zones will generally fall inside this boundary. This will typically be a single polygon but may be multiple polygons if a planting site has several disjoint areas. Coordinates always use SRID 4326 (WGS 84 latitude/longitude).';
COMMENT ON COLUMN tracking.planting_sites.created_by IS 'Which user created the planting site.';
COMMENT ON COLUMN tracking.planting_sites.created_time IS 'When the planting site was originally created.';
COMMENT ON COLUMN tracking.planting_sites.description IS 'Optional user-supplied description of the planting site.';
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
COMMENT ON COLUMN tracking.planting_zones.modified_by IS 'Which user most recently modified the planting zone.';
COMMENT ON COLUMN tracking.planting_zones.modified_time IS 'When the planting zone was most recently modified.';
COMMENT ON COLUMN tracking.planting_zones.name IS 'Short name of this planting zone. This is often just a single letter. Must be unique within a planting site.';
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

COMMENT ON CONSTRAINT num_plants_positive_unless_reassignment_from ON tracking.plantings IS 'If the planting represents the "from" side of a reassignment, the number of plants must be negative. Otherwise it must be positive.';

-- When adding new tables, put them in alphabetical (ASCII) order.
