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

COMMENT ON TABLE asset_statuses IS '(Enum) Possible statuses of externally-generated assets as they''re processed.';

COMMENT ON TABLE automations IS 'Configuration of automatic processes run by the device manager.';

COMMENT ON TABLE seedbank.bags IS 'Individual bags of seeds that are part of an accession. An accession can consist of multiple bags.';

COMMENT ON TABLE birdnet_results IS 'Results of BirdNET detection pipeline from video files.';

COMMENT ON TABLE chat_memory_conversations IS 'Information about Ask Terraware conversations.';

COMMENT ON TABLE chat_memory_message_types IS '(Enum) Message types that can be stored as part of the memory of an LLM chat conversation.';

COMMENT ON TABLE chat_memory_messages IS 'Messages from Ask Terraware conversations. Both questions (prompts to the LLM) and answers (responses from the LLM) are recorded.';

COMMENT ON TABLE seedbank.collection_sources IS '(Enum) Types of source plants that seeds can be collected from.';

COMMENT ON TABLE conservation_categories IS '(Enum) IUCN conservation category codes.';

COMMENT ON TABLE countries IS 'Country information per ISO-3166.';
COMMENT ON COLUMN countries.code IS 'ISO-3166 alpha-2 country code. We use this code to refer to countries elsewhere in the data model.';
COMMENT ON COLUMN countries.code_alpha3 IS 'ISO-3166 alpha-3 country code. This is used in cases where an external system or user needs an alpha-3 code instad of an alpha-2 code, but is treated purely as descriptive data in the data model.';
COMMENT ON COLUMN countries.eligible IS 'If false, projects in this country are ineligible for the accelerator program.';
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

COMMENT ON TABLE disclaimers IS 'Disclaimers content and effective dates.';

COMMENT ON TABLE ecosystem_types IS '(Enum) Types of ecosystems in which plants can be found. Based on the World Wildlife Federation''s "Terrestrial Ecoregions of the World" report.';

COMMENT ON TABLE event_log IS 'Holds historical information about what operations were performed at what times. Events are JSON-serialized objects that may contain identifiers such as organization IDs; identifiers are not treated as foreign keys, so events may contain references to objects that were subsequently deleted.';
COMMENT ON COLUMN event_log.event_class IS 'Fully-qualified class name of the event whose payload is stored in the payload column. May be a newer version of the event class than was originally written at the time of the operation.';
COMMENT ON COLUMN event_log.original_event_class IS 'If the event has been migrated to a newer version, the fully-qualified class name of the event as it was originally stored.';
COMMENT ON COLUMN event_log.original_payload IS 'If the event has been migrated to a newer version, the JSON-serialized contents of the event as it was originally stored.';
COMMENT ON COLUMN event_log.payload IS 'JSON-serialized contents of the event object of the class specified by event_class.';

COMMENT ON PROCEDURE event_log_create_id_index IS 'Creates an index on the event_log table for a top-level field in the event payload. The field is treated as a BIGINT. Only rows that have a non-null value for the field are indexed. The event class is included as a secondary key to support "all events of type X for entity Y" queries.';

COMMENT ON TABLE facilities IS 'Physical locations at a site. For example, each seed bank and each nursery is a facility.';
COMMENT ON COLUMN facilities.idle_after_time IS 'Time at which the facility will be considered idle if no timeseries data is received. Null if the timeseries has already been marked as idle or if no timeseries data has ever been received from the facility.';
COMMENT ON COLUMN facilities.idle_since_time IS 'Time at which the facility became idle. Null if the facility is not currently considered idle.';
COMMENT ON COLUMN facilities.last_notification_date IS 'Local date on which facility-related notifications were last generated.';
COMMENT ON COLUMN facilities.last_timeseries_time IS 'When the most recent timeseries data was received from the facility.';
COMMENT ON COLUMN facilities.max_idle_minutes IS 'Send an alert if this many minutes pass without new timeseries data from a facility''s device manager.';
COMMENT ON COLUMN facilities.next_notification_time IS 'Time at which the server should next generate notifications for the facility if any are needed.';

COMMENT ON TABLE facility_connection_states IS '(Enum) Progress of the configuration of a device manager for a facility.';

COMMENT ON TABLE facility_types IS '(Enum) Types of facilities that can be represented in the data model.';

COMMENT ON TABLE file_access_tokens IS 'Temporary tokens for unauthenticated access to files from the file store.';

COMMENT ON TABLE files IS 'Generic information about individual files. Files are associated with application entities using linking tables such as `accession_photos`.';
COMMENT ON COLUMN files.captured_local_time IS 'If applicable, the date and time in the local time zone of wherever the file''s data was captured. For example, the time a photo was taken. This may differ from `created_time`, which represents when the file was stored in Terraware. This is typically extracted from the file itself, e.g., EXIF metadata.';
COMMENT ON COLUMN files.geolocation IS 'If applicable, the location where the file was created. For photos, typically extracted from EXIF metadata.';

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

COMMENT ON TABLE mux_assets IS 'Information about Mux (video streaming) assets associated with files.';
COMMENT ON COLUMN mux_assets.asset_id IS 'ID of the Mux asset as a whole. Used when updating or deleting assets.';
COMMENT ON COLUMN mux_assets.created_time IS 'When the file was sent to Mux to create the asset. This can be later than the created time of the original file.';
COMMENT ON COLUMN mux_assets.error_message IS 'If Mux failed to process the asset, the error it encountered.';
COMMENT ON COLUMN mux_assets.playback_id IS 'ID of the Mux playback configuration for the asset. This is passed to the video player to stream a video.';
COMMENT ON COLUMN mux_assets.ready_time IS 'If the asset is ready to play, what time it became ready.';

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

COMMENT ON TABLE plant_material_sourcing_methods IS '(Enum) Sourcing methods for acquiring plant material.';

COMMENT ON TABLE project_internal_users IS 'Which users have which internal roles on a project.';

COMMENT ON CONSTRAINT project_internal_users_role_exclusive ON project_internal_users IS 'Either role_id or role_name is necessary, but cannot include both.';

COMMENT ON TABLE project_land_use_model_types IS 'Which projects have which types of land use models.';

COMMENT ON TABLE project_report_settings IS 'Which projects require reports to be submitted each quarter. Organization-level settings are in `organization_report_settings`.';

COMMENT ON TABLE project_internal_roles IS '(Enum) Roles a user is allowed to have on a project.';

COMMENT ON TABLE projects IS 'Distinguishes among an organization''s projects.';

COMMENT ON TABLE rate_limited_events IS 'Tracks events such as notifications that should have a minimum interval between instances.';
COMMENT ON COLUMN rate_limited_events.event_class IS 'Fully-qualified class name of the event whose rate limit is being tracked.';

COMMENT ON TABLE regions IS '(Enum) Parts of the world where countries are located.';

COMMENT ON TABLE roles IS '(Enum) Roles a user is allowed to have in an organization.';

COMMENT ON TABLE seed_fund_report_files IS 'Linking table between `reports` and `files` for non-photo files.';

COMMENT ON TABLE seed_fund_report_photos IS 'Linking table between `reports` and `files` for photos.';

COMMENT ON TABLE seed_fund_report_statuses IS '(Enum) Describes where in the workflow each partner report is.';

COMMENT ON TABLE seed_fund_reports IS 'Partner-submitted reports about their organizations and projects.';
COMMENT ON COLUMN seed_fund_reports.project_id IS 'If this report is for a specific project and the project still exists, the project ID. If the project has been deleted, this will be null but `project_name` will still be populated.';
COMMENT ON COLUMN seed_fund_reports.project_name IS 'If this report is for a specific project, the name of the project as of the time the report was submitted.';

COMMENT ON TABLE seedbank.seed_quantity_units IS '(Enum) Available units in which seeds can be measured. For weight-based units, includes unit conversion information.';

COMMENT ON TABLE seed_storage_behaviors IS '(Enum) How seeds of a particular species behave in storage.';

COMMENT ON TABLE seed_treatments IS '(Enum) Techniques that can be used to treat seeds before testing them for viability.';

COMMENT ON TABLE spatial_ref_sys IS '(Enum) Metadata about spatial reference (coordinate) systems. Managed by the PostGIS extension, not the application.';

COMMENT ON TABLE species IS 'Per-organization information about species.';
COMMENT ON COLUMN species.checked_time IS 'If non-null, when the species was checked for possible suggested edits. If null, the species has not been checked yet.';

COMMENT ON TABLE species_ecosystem_types IS 'Ecosystems where each species can be found.';

COMMENT ON TABLE species_growth_forms IS 'Growth forms of each species';

COMMENT ON TABLE species_native_categories IS '(Enum) Categories related to native-ness of a species.';

COMMENT ON TABLE species_plant_material_sourcing_methods IS 'Sourcing methods for the plant material used to grow a particular species.';

COMMENT ON TABLE species_problem_fields IS '(Enum) Species fields that can be scanned for problems.';

COMMENT ON TABLE species_successional_groups IS 'The successional groupings that the species is planted in.';

COMMENT ON TABLE species_problem_types IS '(Enum) Specific types of problems that can be detected in species data.';

COMMENT ON TABLE species_problems IS 'Problems found in species data. Rows are deleted from this table when the problem is marked as ignored by the user or the user accepts the suggested fix.';

COMMENT ON TABLE splats IS 'Information about 3D Gaussian splatting models generated from video files.';
COMMENT ON COLUMN splats.camera_position_x IS 'Starting location of the camera (along with y and z).';
COMMENT ON COLUMN splats.origin_position_x IS 'Center point of the splat (along with y and z).';

COMMENT ON TABLE splat_annotations IS 'Annotations that should be displayed inside splat models.';
COMMENT ON COLUMN splat_annotations.label IS 'The text that displays over the annotations while it''s floating in space.';
COMMENT ON COLUMN splat_annotations.title IS 'The text that displays at the top of the annotation box after it is clicked.';
COMMENT ON COLUMN splat_annotations.body_text IS 'The text that displays in the annotation box after it is clicked.';

COMMENT ON TABLE spring_session IS 'Active login sessions. Used by Spring Session, not the application.';

COMMENT ON TABLE spring_session_attributes IS 'Data associated with a login session. Used by Spring Session, not the application.';

COMMENT ON TABLE sub_locations IS 'The available locations where seeds can be stored at a seed bank facility or seedlings can be stored at a nursery facility.';
COMMENT ON COLUMN sub_locations.name IS 'E.g., Freezer 1, Freezer 2';

COMMENT ON TABLE successional_groups IS '(Enum) Successional Groups that a plant may be planted in.';

COMMENT ON TABLE test_clock IS 'User-adjustable clock for test environments. Not used in production.';
COMMENT ON COLUMN test_clock.fake_time IS 'What time the server should believe it was at the time the row was written.';
COMMENT ON COLUMN test_clock.real_time IS 'What time it was in the real world when the row was written.';

COMMENT ON TABLE time_zones IS '(Enum) Valid time zone names. This is populated with the list of names from the IANA time zone database.';

COMMENT ON TABLE thumbnails IS 'Information about server-generated versions of photos and still images of videos.';
COMMENT ON COLUMN thumbnails.is_full_size IS 'True if this thumbnail''s dimensions are the same as the original photo or video.';

COMMENT ON TABLE timeseries IS 'Properties of a series of values collected from a device. Each device metric is represented as a timeseries.';
COMMENT ON COLUMN timeseries.decimal_places IS 'For numeric timeseries, the number of digits after the decimal point to display.';
COMMENT ON COLUMN timeseries.retention_days IS 'If non-null, only retain values for this many days. If null, do not expire old timeseries values.';
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

COMMENT ON TABLE user_disclaimers IS 'Records of user acceptance of disclaimers.';

COMMENT ON TABLE wood_density_levels IS 'The taxonomic level in the at which a wood density measurement is known';

COMMENT ON TABLE vector_store IS 'Content snippets and embeddings for use as context in Ask Terraware LLM prompts. This table is used by the Spring AI vector store library, and its structure needs to match what the library expects.';

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
COMMENT ON COLUMN nursery.batch_quantity_history.hardening_off_quantity IS 'New number of hardening-off seedlings in the batch.';
COMMENT ON COLUMN nursery.batch_quantity_history.history_type_id IS 'Type of operation that resulted in the change in quantities.';
COMMENT ON COLUMN nursery.batch_quantity_history.active_growth_quantity IS 'New number of active-growth-for-planting seedlings in the batch.';
COMMENT ON COLUMN nursery.batch_quantity_history.ready_quantity IS 'New number of ready-for-planting seedlings in the batch.';
COMMENT ON COLUMN nursery.batch_quantity_history.withdrawal_id IS 'If this change in quantity was due to a withdrawal from the batch, the withdrawal''s ID.';

COMMENT ON TABLE nursery.batch_quantity_history_types IS '(Enum) Types of operations that can result in changes to remaining quantities of seedling batches.';

COMMENT ON TABLE nursery.batch_sub_locations IS 'Which batches are stored in which sub-locations.';

COMMENT ON TABLE nursery.batch_substrates IS '(Enum) Substrates in which seedlings can be planted in a nursery.';

COMMENT ON TABLE nursery.batch_withdrawals IS 'Number of seedlings withdrawn from each originating batch as part of a withdrawal.';
COMMENT ON COLUMN nursery.batch_withdrawals.batch_id IS 'The batch from which the seedlings were withdrawn, also referred to as the originating batch.';
COMMENT ON COLUMN nursery.batch_withdrawals.destination_batch_id IS 'If the withdrawal was a nursery transfer, the batch that was created as a result. A withdrawal can have more than one originating batch; if they are of the same species, only one destination batch will be created and there will be multiple rows with the same `destination_batch_id`. May be null if the batch was subsequently deleted.';
COMMENT ON COLUMN nursery.batch_withdrawals.germinating_quantity_withdrawn IS 'Number of germinating seedlings that were withdrawn from this batch. This is not necessarily the total number of seedlings in the withdrawal as a whole since a withdrawal can come from multiple batches.';
COMMENT ON COLUMN nursery.batch_withdrawals.hardening_off_quantity_withdrawn IS 'Number of hardening-off seedlings that were withdrawn from this batch. This is not necessarily the total number of seedlings in the withdrawal as a whole since a withdrawal can come from multiple batches.';
COMMENT ON COLUMN nursery.batch_withdrawals.active_growth_quantity_withdrawn IS 'Number of active-growth-for-planting seedlings that were withdrawn from this batch. This is not necessarily the total number of seedlings in the withdrawal as a whole since a withdrawal can come from multiple batches.';
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
COMMENT ON COLUMN nursery.batches.germination_started_date IS 'Date when sown seeds first started germinating.';
COMMENT ON COLUMN nursery.batches.hardening_off_quantity IS 'Number of hardening-off seedlings currently available in inventory. Withdrawals cause this to decrease.';
COMMENT ON COLUMN nursery.batches.id IS 'Globally-unique internal identifier for the batch. Not typically presented to end users; "batch_number" is the user-facing identifier.';
COMMENT ON COLUMN nursery.batches.latest_observed_germinating_quantity IS 'Latest user-observed number of germinating seedlings currently available in inventory. Withdrawals do not cause this to decrease.';
COMMENT ON COLUMN nursery.batches.latest_observed_hardening_off_quantity IS 'Latest user-observed number of hardening-off seedlings currently available in inventory. Withdrawals do not cause this to decrease.';
COMMENT ON COLUMN nursery.batches.latest_observed_active_growth_quantity IS 'Latest user-observed number of active-growth-for-planting seedlings currently available in inventory. Withdrawals do not cause this to decrease.';
COMMENT ON COLUMN nursery.batches.latest_observed_ready_quantity IS 'Latest user-observed number of ready-for-planting seedlings currently available in inventory. Withdrawals do not cause this to decrease.';
COMMENT ON COLUMN nursery.batches.latest_observed_time IS 'When the latest user observation of seedling quantities took place.';
COMMENT ON COLUMN nursery.batches.modified_by IS 'Which user most recently modified the batch, either directly or by creating a withdrawal.';
COMMENT ON COLUMN nursery.batches.modified_time IS 'When the batch was most recently modified, either directly or by creating a withdrawal.';
COMMENT ON COLUMN nursery.batches.notes IS 'User-supplied freeform notes about batch.';
COMMENT ON COLUMN nursery.batches.active_growth_quantity IS 'Number of active-growth-for-planting seedlings currently available in inventory. Withdrawals cause this to decrease.';
COMMENT ON COLUMN nursery.batches.organization_id IS 'Which organization owns the nursery where this batch is located.';
COMMENT ON COLUMN nursery.batches.ready_by_date IS 'User-supplied estimate of when the batch will be ready for planting.';
COMMENT ON COLUMN nursery.batches.ready_quantity IS 'Number of ready-for-planting seedlings currently available in inventory. Withdrawals cause this to decrease.';
COMMENT ON COLUMN nursery.batches.seeds_sown_date IS 'Date when newly-arrived seeds were first sown.';
COMMENT ON COLUMN nursery.batches.species_id IS 'Species of the batch''s plants. Must be under the same organization as the facility ID (enforced in application code).';
COMMENT ON COLUMN nursery.batches.total_germinated IS 'Total number of seedlings that have moved from Germinating to Active Growth status over the lifetime of the batch. This is the numerator for the germination rate calculation.';
COMMENT ON COLUMN nursery.batches.total_germination_candidates IS 'Total number of seedlings that have been candidates for moving from Germinating to Active Growth status. This includes seedlings that are already germinated and germinating seedlings that were withdrawn as Dead, but does not include germinating seedlings that were withdrawn for other reasons. This is the denominator for the germination rate calculation.';
COMMENT ON COLUMN nursery.batches.total_loss_candidates IS 'Total number of non-germinating (Active Growth and Ready) seedlings that have been candidates for being withdrawn as dead. This includes seedlings that are still in the batch, seedlings that were withdrawn for outplanting, and seedlings that were already withdrawn as dead, but does not include germinating seedlings or seedlings that were withdrawn for other reasons. This is the denominator for the loss rate calculation.';
COMMENT ON COLUMN nursery.batches.total_lost IS 'Total number of non-germinating (Active Growth and Ready) seedlings that have been withdrawn as Dead. This is the numerator for the loss rate calculation.';
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

COMMENT ON TABLE tracking.biomass_forest_types IS '(Enum) Types for forest in a biomass observation.';

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
COMMENT ON COLUMN tracking.draft_planting_sites.num_substrata is 'Number of substrata defined so far.';
COMMENT ON COLUMN tracking.draft_planting_sites.num_strata is 'Number of strata defined so far.';

COMMENT ON TABLE tracking.mangrove_tides IS '(Enum) High/Low tide at a mangrove during an observation.';

COMMENT ON TABLE tracking.monitoring_plot_overlaps IS 'Which monitoring plots overlap with previously-used monitoring plots. A plot may overlap with multiple older or newer plots.';
COMMENT ON COLUMN tracking.monitoring_plot_overlaps.monitoring_plot_id IS 'ID of the newer monitoring plot.';
COMMENT ON COLUMN tracking.monitoring_plot_overlaps.overlaps_plot_id IS 'ID of the older monitoring plot.';

COMMENT ON TABLE tracking.monitoring_plot_histories IS 'Versions of monitoring plots over time. Each time a planting site changes, its monitoring plots are added to this table.';
COMMENT ON COLUMN tracking.monitoring_plot_histories.created_by IS 'Which user created or edited the monitoring plot or its planting site.';
COMMENT ON COLUMN tracking.monitoring_plot_histories.created_time IS 'When the monitoring plot was created, edited, or associated with a new planting site history record. May differ from the time when the substratum or planting site was created or edited since plots can be created when observations start.';

COMMENT ON TABLE tracking.monitoring_plots IS 'Regions within substrata that can be comprehensively surveyed in order to extrapolate results for the entire stratum. Any monitoring plot in a substratum is expected to have roughly the same number of plants of the same species as any other monitoring plot in the same substratum.';
COMMENT ON COLUMN tracking.monitoring_plots.boundary IS 'Boundary of the monitoring plot. Coordinates always use SRID 4326 (WGS 84 latitude/longitude).';
COMMENT ON COLUMN tracking.monitoring_plots.created_by IS 'Which user created the monitoring plot.';
COMMENT ON COLUMN tracking.monitoring_plots.created_time IS 'When the monitoring plot was originally created.';
COMMENT ON COLUMN tracking.monitoring_plots.modified_by IS 'Which user most recently modified the monitoring plot.';
COMMENT ON COLUMN tracking.monitoring_plots.modified_time IS 'When the monitoring plot was most recently modified.';
COMMENT ON COLUMN tracking.monitoring_plots.permanent_index IS 'If this plot is a candidate to be a permanent monitoring plot, its position in the randomized list of plots for the stratum. Starts at 1 for each stratum. If null, this plot is not currently a candidate for selection as a permanent plot but may still be chosen as a temporary plot.';
COMMENT ON COLUMN tracking.monitoring_plots.substratum_id IS 'Which substratum this monitoring plot is currently part of, if any. May be null if the substratum was edited or removed after the plot was created, or if the plot was created outside the site boundary.';
COMMENT ON COLUMN tracking.monitoring_plots.plot_number IS 'User-visible identifier of this plot. Plot numbers are sequential and start at 1 for each organization.';
COMMENT ON COLUMN tracking.monitoring_plots.size_meters IS 'Length in meters of one side of the monitoring plot. Plots are always squares, so for a 30x30m plot, this would be 30.';

COMMENT ON TABLE tracking.observable_conditions IS '(Enum) Conditions that can be observed in a monitoring plot.';

COMMENT ON TABLE tracking.observation_media_files IS 'Observation-specific details about a photo or video of a monitoring plot. Generic metadata is in the `files` table.';
COMMENT ON COLUMN tracking.observation_media_files.is_original IS 'If true, this photo was uploaded as part of the original observation data. If false, this photo was added after the observation.';

COMMENT ON TABLE tracking.observation_media_types IS '(Enum) Types of observation plot media. This refers to the type of thing that''s being depicted, not the content type of the media file.';

COMMENT ON TABLE tracking.observation_plot_conditions IS 'List of conditions observed in each monitoring plot.';

COMMENT ON TABLE tracking.observation_plot_positions IS '(Enum) Positions in a monitoring plot where users can take photos or record coordinates.';

COMMENT ON TABLE tracking.observation_plots IS 'Information about monitoring plots that are required to be surveyed as part of observations. This is not populated until the scheduled start time of the observation.';
COMMENT ON COLUMN tracking.observation_plots.completed_time IS 'Server-generated completion date and time. This is the time the observation was submitted to the server, not the time it was performed in the field.';
COMMENT ON COLUMN tracking.observation_plots.is_permanent IS 'If true, this plot was selected for observation as a permanent monitoring plot. If false, this plot was selected as a temporary monitoring plot.';
COMMENT ON COLUMN tracking.observation_plots.observed_time IS 'Client-supplied observation date and time. This is the time the observation was performed in the field, not the time it was submitted to the server.';

COMMENT ON TABLE tracking.observation_requested_substrata IS 'If an observation should only cover a specific set of substrata, the substratum IDs are stored here. If an observation is of the entire site (the default), there will be no rows for that observation in this table.';

COMMENT ON TABLE tracking.observation_states IS '(Enum) Where in the observation lifecycle a particular observation is.';

COMMENT ON TABLE tracking.observation_types IS '(Enum) Type of observation, currently only used for ad hoc observations.';

COMMENT ON TABLE tracking.observations IS 'Scheduled observations of planting sites. This table may contain rows describing future observations as well as current and past ones.';
COMMENT ON COLUMN tracking.observations.completed_time IS 'Server-generated date and time the final piece of data for the observation was received.';
COMMENT ON COLUMN tracking.observations.end_date IS 'Last day of the observation. This is typically the last day of the same month as `start_date`.';
COMMENT ON COLUMN tracking.observations.planting_site_history_id IS 'Which version of the planting site map was used for the observation. Null for upcoming observations since monitoring plots are only placed on the map when an observation starts.';
COMMENT ON COLUMN tracking.observations.start_date IS 'First day of the observation. This is either the first day of the month following the end of the planting season, or 6 months after that day.';
COMMENT ON COLUMN tracking.observations.upcoming_notification_sent_time IS 'When the notification that the observation is starting in 1 month was sent. Null if the notification has not been sent yet.';

COMMENT ON TABLE tracking.observation_biomass_details IS 'Recorded data for a biomass observation.';
COMMENT ON COLUMN tracking.observation_biomass_details.ph IS 'Acidity of water in pH. Must only exists if forest type is "Mangrove".';
COMMENT ON COLUMN tracking.observation_biomass_details.salinity_ppt IS 'Salinity of water in parts per thousand (ppt). Must be non-null if forest type is "Mangrove".';
COMMENT ON COLUMN tracking.observation_biomass_details.tide_id IS 'High/low tide during observation. Must be non-null if forest type is "Mangrove".';
COMMENT ON COLUMN tracking.observation_biomass_details.tide_time IS 'Time when the tide is recorded. Must be non-null if forest type is "Mangrove".';
COMMENT ON COLUMN tracking.observation_biomass_details.water_depth_cm IS 'Depth of water in centimeters (cm). Must be non-null if forest type is "Mangrove".';

COMMENT ON TABLE tracking.observation_biomass_species IS 'Herbaceous and tree species data for a biomass observation.';
COMMENT ON COLUMN tracking.observation_biomass_species.common_name IS 'The user-supplied common name of the plant''s species. Null if ID is known.';
COMMENT ON COLUMN tracking.observation_biomass_species.scientific_name IS 'The user-supplied scientific name of the plant''s species. Must be provided if ID is null. Null if ID is known.';
COMMENT ON COLUMN tracking.observation_biomass_species.species_id IS 'The ID of the plant''s species, if known.';

COMMENT ON TABLE tracking.observation_biomass_quadrat_details IS 'Details of a biomass observation at each quadrat of a monitoring plot.';

COMMENT ON TABLE tracking.observation_biomass_quadrat_species IS 'Herbaceous species at each quadrat of a monitoring plot of a biomass observation';

COMMENT ON TABLE tracking.observed_plot_coordinates IS 'Observed GPS coordinates in monitoring plots. Does not include photo coordinates or coordinates of recorded plants.';

COMMENT ON TABLE tracking.observed_plot_species_totals IS 'Aggregated per-monitoring-plot, per-species totals of plants recorded during observations.';
COMMENT ON COLUMN tracking.observed_plot_species_totals.permanent_live IS 'If this is a permanent monitoring plot, the number of live and existing plants observed. 0 otherwise.';
COMMENT ON COLUMN tracking.observed_plot_species_totals.survival_rate IS 'If this is a permanent monitoring plot, percentage of plants of the species observed in this plot, in either this observation or in previous ones, that have survived since the t0 point. Null if this is not a permanent monitoring plot in the current observation.';

COMMENT ON TABLE tracking.observation_plot_statuses IS '(Enum) The status of an observation plot.';

COMMENT ON TABLE tracking.observed_site_species_totals IS 'Aggregated per-planting-site, per-species totals of plants recorded during observations.';
COMMENT ON COLUMN tracking.observed_site_species_totals.permanent_live IS 'The number of live and existing plants observed in permanent monitoring plots.';
COMMENT ON COLUMN tracking.observed_site_species_totals.survival_rate IS 'Percentage of plants of the species observed in permanent monitoring plots in the planting site, in either this observation or in previous ones, that have survived since the t0 point.';

COMMENT ON TABLE tracking.observed_substratum_species_totals IS 'Aggregated per-substratum, per-species totals of plants recorded during observations.';
COMMENT ON COLUMN tracking.observed_substratum_species_totals.permanent_live IS 'The number of live and existing plants observed in permanent monitoring plots.';
COMMENT ON COLUMN tracking.observed_substratum_species_totals.survival_rate IS 'Percentage of plants of the species observed in permanent monitoring plots in the substratum, in either the current observation or in previous ones, that have survived since the t0 point.';

COMMENT ON TABLE tracking.observed_stratum_species_totals IS 'Aggregated per-stratum, per-species totals of plants recorded during observations.';
COMMENT ON COLUMN tracking.observed_stratum_species_totals.permanent_live IS 'The number of live and existing plants observed in permanent monitoring plots.';
COMMENT ON COLUMN tracking.observed_stratum_species_totals.survival_rate IS 'Percentage of plants of the species observed in permanent monitoring plots in the stratum, in either the current observation or in previous ones, that have survived since the t0 point.';

COMMENT ON TABLE tracking.planting_seasons IS 'Start and end dates of planting seasons for planting sites.';
COMMENT ON COLUMN tracking.planting_seasons.end_date IS 'What day the planting season ends. This is the last day of the season, not the day after the season, that is, if the planting season is the month of January, this will be January 31, not February 1.';
COMMENT ON COLUMN tracking.planting_seasons.end_time IS 'When the planting season will be finished. This is midnight on the day after `end_date` in the planting site''s time zone.';
COMMENT ON COLUMN tracking.planting_seasons.is_active IS 'True if the planting season is currently in progress. Only one planting season can be active at a time for a planting site.';
COMMENT ON COLUMN tracking.planting_seasons.start_date IS 'What day the planting season starts.';
COMMENT ON COLUMN tracking.planting_seasons.start_time IS 'When the planting season will start. This is midnight on `start_date` in the planting site''s time zone.';

COMMENT ON TABLE tracking.planting_site_histories IS 'Versions of planting site maps over time. Each time a planting site map changes, the new map is inserted into this table and its child tables.';
COMMENT ON COLUMN tracking.planting_site_histories.created_time IS 'When the site map was created or updated. You can determine which map was active for a site at a particular time by looking for the maximum `created_time` less than or equal to the time in question.';

COMMENT ON TABLE tracking.planting_site_notifications IS 'Tracks which notifications have already been sent regarding planting sites.';
COMMENT ON COLUMN tracking.planting_site_notifications.notification_number IS 'Number of notifications of this type that have been sent, including this one. 1 for initial notification, 2 for reminder, 3 for second reminder, etc.';

COMMENT ON TABLE tracking.planting_site_populations IS 'Total number of plants of each species in each planting site.';

COMMENT ON TABLE tracking.planting_sites IS 'Top-level information about entire planting sites. Every planting site has at least one stratum.';
COMMENT ON COLUMN tracking.planting_sites.boundary IS 'Boundary of the entire planting site. strata will generally fall inside this boundary. This will typically be a single polygon but may be multiple polygons if a planting site has several disjoint areas. Coordinates always use SRID 4326 (WGS 84 latitude/longitude).';
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

COMMENT ON TABLE tracking.substratum_histories IS 'Versions of substratum maps over time. Each time a planting site map changes, its substrata'' maps are inserted into this table.';

COMMENT ON TABLE tracking.substratum_populations IS 'Total number of plants of each species in each substratum.';

COMMENT ON TABLE tracking.substrata IS 'Regions within strata that are a convenient size for a planting operation. Typically <10Ha.';
COMMENT ON COLUMN tracking.substrata.boundary IS 'Boundary of the substratum. Coordinates always use SRID 4326 (WGS 84 latitude/longitude).';
COMMENT ON COLUMN tracking.substrata.created_by IS 'Which user created the substratum.';
COMMENT ON COLUMN tracking.substrata.created_time IS 'When the substratum was originally created.';
COMMENT ON COLUMN tracking.substrata.modified_by IS 'Which user most recently modified the substratum.';
COMMENT ON COLUMN tracking.substrata.modified_time IS 'When the substratum was most recently modified.';
COMMENT ON COLUMN tracking.substrata.name IS 'Short name of this substratum. This is often just a single letter and number. Must be unique within a stratum.';
COMMENT ON COLUMN tracking.substrata.observed_time IS 'When an observation of a monitoring plot in the substratum was most recently completed.';
COMMENT ON COLUMN tracking.substrata.planting_site_id IS 'Which planting site this substratum is part of. This is the same as the planting site ID of this substratum''s stratum, but is duplicated here so it can be used as the target of a foreign key constraint.';
COMMENT ON COLUMN tracking.substrata.stratum_id IS 'Which stratum this substratum is part of.';
COMMENT ON COLUMN tracking.substrata.stable_id IS 'Substratum identifier that doesn''t change even if the substratum is renamed or edited. Defaults to the full name.';

COMMENT ON TABLE tracking.stratum_histories IS 'Versions of stratum maps over time. Each time a planting site map changes, its strata'' maps are inserted into this table.';

COMMENT ON TABLE tracking.stratum_populations IS 'Total number of plants of each species in each stratum.';

COMMENT ON TABLE tracking.stratum_t0_temp_densities IS 'Density for a stratum per species, in plants per hectare. Only applies to temporary plots and only if survival_rate_includes_temp_plots is set to true for the stratum''s planting site.';

COMMENT ON TABLE tracking.strata IS 'Regions within planting sites that have a consistent set of conditions such that survey results from any part of the stratum can be extrapolated to the entire stratum. strata are subdivided into plots. Every stratum has at least one plot.';
COMMENT ON COLUMN tracking.strata.boundary IS 'Boundary of the stratum. This area is further subdivided into plots. This will typically be a single polygon but may be multiple polygons if a stratum has several disjoint areas. Coordinates always use SRID 4326 (WGS 84 latitude/longitude).';
COMMENT ON COLUMN tracking.strata.boundary_modified_by IS 'Which user most recently edited the stratum''s boundary.';
COMMENT ON COLUMN tracking.strata.boundary_modified_time IS 'When the stratum''s boundary was most recently modified.';
COMMENT ON COLUMN tracking.strata.created_by IS 'Which user created the stratum.';
COMMENT ON COLUMN tracking.strata.created_time IS 'When the stratum was originally created.';
COMMENT ON COLUMN tracking.strata.modified_by IS 'Which user most recently modified the stratum.';
COMMENT ON COLUMN tracking.strata.modified_time IS 'When the stratum was most recently modified.';
COMMENT ON COLUMN tracking.strata.name IS 'Short name of this stratum. This is often just a single letter. Must be unique within a planting site.';
COMMENT ON COLUMN tracking.strata.num_permanent_plots IS 'Number of permanent plots to assign to the next observation. This is typically derived from a statistical formula.';
COMMENT ON COLUMN tracking.strata.planting_site_id IS 'Which planting site this stratum is part of.';
COMMENT ON COLUMN tracking.strata.stable_id IS 'Stratum identifier that doesn''t change even if the stratum is renamed or edited. Defaults to the stratum name.';

COMMENT ON TABLE tracking.plantings IS 'Details about plants that were planted or reassigned as part of a delivery. There is one plantings row per species in a delivery.';
COMMENT ON COLUMN tracking.plantings.created_by IS 'Which user created the planting.';
COMMENT ON COLUMN tracking.plantings.created_time IS 'When the planting was created. Note that plantings are never updated, so there is no modified time.';
COMMENT ON COLUMN tracking.plantings.delivery_id IS 'Which delivery this planting is part of.';
COMMENT ON COLUMN tracking.plantings.notes IS 'Notes about this specific planting. In the initial version of the web app, the user can only enter per-planting notes for reassignments, not for initial deliveries.';
COMMENT ON COLUMN tracking.plantings.num_plants IS 'Number of plants that were planted (if the number is positive) or reassigned (if the number is negative).';
COMMENT ON COLUMN tracking.plantings.planting_site_id IS 'Which planting site has the planting. Must be the same as the planting site ID of the delivery. This identifies the site as a whole; in addition, there may be a plot ID.';
COMMENT ON COLUMN tracking.plantings.planting_type_id IS 'Whether this is the plant assignment from the initial delivery or an adjustment from a reassignment.';
COMMENT ON COLUMN tracking.plantings.substratum_id IS 'Which plot this planting affected, if any. Must be a plot at the planting site referenced by `planting_site_id`. Null if the planting site does not have plot information. For reassignments, this is the original plot if `num_plants` is negative, or the new plot if `num_plants` is positive.';
COMMENT ON COLUMN tracking.plantings.species_id IS 'Which species was planted.';

COMMENT ON TABLE tracking.plot_t0_densities IS 'Density for a plot per species, in plants per hectare.';

COMMENT ON TABLE tracking.plot_t0_observations IS 'Which observation to use to determine t0 plot density.';

COMMENT ON TABLE tracking.recorded_plant_statuses IS '(Enum) Possible statuses of a plant recorded during observation of a monitoring plot.';

COMMENT ON TABLE tracking.recorded_plants IS 'Information about individual plants observed in monitoring plots.';
COMMENT ON COLUMN tracking.recorded_plants.species_id IS 'If certainty is "Known," the ID of the plant''s species. Null for other certainty values.';
COMMENT ON COLUMN tracking.recorded_plants.species_name IS 'If certainty is "Other," the user-supplied name of the plant''s species. Null for other certainty values.';

COMMENT ON TABLE tracking.recorded_trees IS 'Recorded trees or shrubs of a biomass observation.';
COMMENT ON COLUMN tracking.recorded_trees.tree_number IS 'A unique incremental number per observation starting at 1 for accounting trees at a biomass observation.';
COMMENT ON COLUMN tracking.recorded_trees.tree_number IS 'A unique incremental number starting at 1 for accounting trunks at a biomass observation. Defaults to 1 for Trees/Shrubs.';

COMMENT ON TABLE tracking.recorded_species_certainties IS '(Enum) Levels of certainty about the identity of a species recorded in a monitoring plot observation.';

COMMENT ON TABLE tracking.tree_growth_forms IS '(Enum) Growth form of each species in a biomass observation.';

COMMENT ON CONSTRAINT num_plants_sign_consistent_with_type ON tracking.plantings IS 'If the planting represents the "from" side of a reassignment or an undo of a withdrawal, the number of plants must be negative. Otherwise it must be positive.';

COMMENT ON VIEW accelerator.accelerator_projects IS 'All projects that are in the accelerator, by any of the definitions. The 3 definitions at the time of creation were: having an application, having a cohort, or the project''s org having an internal tag of "Accelerator".';

COMMENT ON TABLE accelerator.activities IS 'User-supplied descriptions of project activities.';

COMMENT ON TABLE accelerator.activity_media_files IS 'Information about activity-related media such as photos and videos.';

COMMENT ON TABLE accelerator.activity_media_types IS '(Enum) Types of media that can be associated with entries in the activity log.';

COMMENT ON TABLE accelerator.activity_statuses IS '(Enum) Statuses of entries in the activity log.';

COMMENT ON TABLE accelerator.activity_types IS '(Enum) Types of entries in the activity log.';

COMMENT ON TABLE accelerator.application_histories IS 'Change histories for accelerator applications. Only includes changes to top-level metadata, not things like changes to variable values.';

COMMENT ON TABLE accelerator.application_module_statuses IS '(Enum) Possible statuses of individual modules in an application.';

COMMENT ON TABLE accelerator.application_modules IS 'Current states of the modules for individual applications.';

COMMENT ON TABLE accelerator.application_statuses IS '(Enum) Possible statuses for an application to the accelerator program.';

COMMENT ON TABLE accelerator.applications IS 'Information about projects that are applying for the accelerator program.';

COMMENT ON TABLE accelerator.cohorts IS 'Accelerator cohort details.';

COMMENT ON TABLE accelerator.cohort_modules IS 'Which modules are assigned to which cohorts.';
COMMENT ON COLUMN accelerator.cohort_modules.title IS 'The title for the module for the cohort. For example "Module 3A" for Module 3A: Title';

COMMENT ON TABLE accelerator.cohort_phases IS '(Enum) Available cohort phases';

COMMENT ON TABLE accelerator.deal_stages IS '(Enum) Stages in the deal workflow that a project progresses through.';

COMMENT ON TABLE accelerator.default_voters IS 'Users to automatically be assigned as voters on accelerator projects.';

COMMENT ON TABLE accelerator.deliverable_categories IS '(Enum) High-level groups for organizing deliverables.';
COMMENT ON COLUMN accelerator.deliverable_categories.internal_interest_id IS 'Internal interest enum that the deliverable category corresponds to.';

COMMENT ON TABLE accelerator.deliverable_cohort_due_dates IS 'Deliverable due dates overrides for cohorts. Can be overridden at the project level.';

COMMENT ON TABLE accelerator.deliverable_documents IS 'Information about expected deliverables of type Document that isn''t relevant for other deliverable types.';

COMMENT ON TABLE accelerator.deliverable_project_due_dates IS 'Deliverable due dates overrides for projects.';

COMMENT ON TABLE accelerator.deliverable_types IS '(Enum) Types of deliverables for an accelerator module.';

COMMENT ON TABLE accelerator.deliverable_variables IS 'Which variables are associated with which deliverables. Note that this includes variables that have been replaced by newer versions, and the newer versions might be associated with different deliverables.';

COMMENT ON TABLE accelerator.deliverables IS 'Information about expected deliverables. This describes what we request from users; the data we get back from users in response is recorded in `project_deliverables` and its child tables.';
COMMENT ON COLUMN accelerator.deliverables.position IS 'Which position this deliverable appears in the module''s list of deliverables, starting with 1.';
COMMENT ON COLUMN accelerator.deliverables.is_sensitive IS 'If true, the data users provide in response to this deliverable will be visible to a smaller subset of accelerator admins. Secure documents are saved to a different document store than non-secure ones.';

COMMENT ON TABLE accelerator.document_stores IS '(Enum) Locations where uploaded documents are stored.';

COMMENT ON TABLE accelerator.event_projects IS 'Projects that are participants of an event.';

COMMENT ON TABLE accelerator.event_statuses IS '(Enum) Statuses of events for an accelerator module';

COMMENT ON TABLE accelerator.event_types IS '(Enum) Types of events for an accelerator module';

COMMENT ON TABLE accelerator.events IS 'Events with meeting links and time within an acclerator module.';

COMMENT ON TABLE accelerator.hubspot_token IS 'If the server has been authorized to make HubSpot API requests, the refresh token to use to generate new access tokens.';

COMMENT ON TABLE accelerator.internal_interests IS '(Enum) Types of notification categories for internal users.';

COMMENT ON TABLE accelerator.metric_components IS '(Enum) Components of metrics for reports.';

COMMENT ON TABLE accelerator.metric_types IS '(Enum) Types of metrics for reports.';

COMMENT ON TABLE accelerator.modules IS 'Possible steps in the workflow of a cohort phase.';
COMMENT ON COLUMN accelerator.modules.position IS 'This model''s ordinal position in the modules spreadsheet. This can be used to present modules in the same order they appear in the spreadsheet.';

COMMENT ON TABLE accelerator.participant_project_species IS 'Species that are associated to a participant project';

COMMENT ON TABLE accelerator.pipelines IS '(Enum) Deal pipelines for accelerator projects.';

COMMENT ON TABLE accelerator.project_accelerator_details IS 'Details about projects that are only relevant for accelerator applicants. The values here are for internal use, not exposed to end users.';
COMMENT ON COLUMN accelerator.project_accelerator_details.file_naming IS 'Identifier that is included in generated filenames. This is often, but not necessarily, the same as the project name.';
COMMENT ON COLUMN accelerator.project_accelerator_details.logframe_url IS 'Link to the project logical framework, monitoring and evaluation plan, and operational work plan.';

COMMENT ON VIEW accelerator.project_deliverables IS 'Deliverable information for projects including submission status and due dates.';

COMMENT ON TABLE accelerator.project_metrics IS 'Metrics specific to one project to report on.';

COMMENT ON TABLE accelerator.project_overall_scores IS 'Overall scores assigned to project by scorers.';

COMMENT ON TABLE accelerator.project_report_configs IS 'Configurations for accelerator project reports, including reporting dates and reporting frequencies.';

COMMENT ON TABLE accelerator.project_scores IS 'Scores assigned to project by scorers.';
COMMENT ON COLUMN accelerator.project_scores.score IS 'Integer score between -2 to 2. The score can be null to represent not yet scored. ';

COMMENT ON VIEW accelerator.project_variables IS 'Latest Variables for projects. Only includes accelerator projects, and includes all accelerator projects, whether or not they have values for any variables.';

COMMENT ON VIEW accelerator.project_variable_values IS 'Latest Variable Values for projects, excluding deleted. Only includes accelerator projects, and includes all accelerator projects, whether or not they have values for any variables.';

COMMENT ON TABLE accelerator.project_votes IS 'Vote selected by voters.';
COMMENT ON COLUMN accelerator.project_votes.vote_option_id IS 'Vote option can be Yes/No/Conditional. The vote can be null to represent not yet voted. ';

COMMENT ON TABLE accelerator.project_vote_decisions IS 'Calculated vote decisions for project.';

COMMENT ON TABLE accelerator.report_achievements IS 'List of achievements for accelerator project reports.';

COMMENT ON TABLE accelerator.report_challenges IS 'List of challenges and mitigation plans for accelerator project reports.';

COMMENT ON TABLE accelerator.report_frequencies IS '(Enum) Frequencies of accelerator project reports. Acts as the report type as well.';

COMMENT ON TABLE accelerator.report_metric_statuses IS '(Enum) Statuses of accelerator project report metrics.';

COMMENT ON TABLE accelerator.report_photos IS 'Photos for the accelerator project report.';
COMMENT ON COLUMN accelerator.report_photos.deleted IS 'Flag for photos to be deleted in the next publishing.';

COMMENT ON TABLE accelerator.report_project_metrics IS 'Report entries of targets and values for project metrics.';

COMMENT ON TABLE accelerator.report_quarters IS '(Enum) Quarters of accelerator project reports.';

COMMENT ON TABLE accelerator.report_standard_metrics IS 'Report entries of targets and values for standard metrics.';

COMMENT ON TABLE accelerator.report_statuses IS '(Enum) Statuses of accelerator project reports.';

COMMENT ON TABLE accelerator.report_system_metrics IS 'Report entries of targets and values for system metrics.';
COMMENT ON COLUMN accelerator.report_system_metrics.override_value IS 'Value inputted by accelerator admin to override system value. Null for no overrides.';
COMMENT ON COLUMN accelerator.report_system_metrics.system_time IS 'System value recorded time. If null, the value is not recorded yet and a live query of Terraware data should be used instead.';
COMMENT ON COLUMN accelerator.report_system_metrics.system_value IS 'Value collected via Terraware data. Null before value is submitted.';

COMMENT ON TABLE accelerator.reports IS 'Accelerator project reports.';
COMMENT ON COLUMN accelerator.reports.report_frequency_id IS 'Frequency of the report, that can determine report data. Must match with frequency of the configuration.';
COMMENT ON COLUMN accelerator.reports.report_quarter_id IS 'Quarter of the report. Must be non-null for quarterly reports and null otherwise.';

COMMENT ON TABLE accelerator.score_categories IS '(Enum) Project score categories.';

COMMENT ON TABLE accelerator.standard_metrics IS 'Standard non-system metrics for every project to measure in accelerator reports.';

COMMENT ON TABLE accelerator.submission_documents IS 'Information about documents uploaded by users to satisfy deliverables. A deliverable can have multiple documents.';
COMMENT ON COLUMN accelerator.submission_documents.name IS 'System-generated filename. The file is stored using this name in the document store. This includes several elements such as the date and description.';
COMMENT ON COLUMN accelerator.submission_documents.location IS 'Location of file in the document store identified by `document_store_id`. This is used by the system to generate download links and includes whatever information is needed to generate a link for a given document store; if the document store supports permalinks then this may be a simple URL.';
COMMENT ON COLUMN accelerator.submission_documents.original_name IS 'Original filename as supplied by the client when the document was uploaded. Not required to be unique since the user can upload revised versions of documents.';

COMMENT ON TABLE accelerator.submission_snapshots IS 'Snapshot files associated to submissions';

COMMENT ON TABLE accelerator.submission_statuses IS '(Enum) Statuses of submissions of deliverables by specific projects.';

COMMENT ON TABLE accelerator.submissions IS 'Information about the current states of the information supplied by specific projects in response to deliverables.';

COMMENT ON TABLE accelerator.system_metrics IS '(Enum) Accelerator report metrics, for which data are collected from Terraware.';

COMMENT ON TABLE accelerator.user_internal_interests IS 'Which internal interest categories are assigned to which internal users. This affects things like which accelerator admins are notified.';

COMMENT ON TABLE accelerator.vote_options IS '(Enum) Available vote options.';

COMMENT ON TABLE docprod.dependency_conditions IS '(Enum) Types of conditions that can control whether or not a variable is presented to the user.';

COMMENT ON TABLE docprod.document_saved_versions IS 'Saved versions of document variable values. A saved version is conceptually just a reference to a particular point in the edit history of the document; to restore that version, we ignore any later edits.';

COMMENT ON TABLE docprod.document_statuses IS '(Enum) Current stage of a document''s lifecycle.';

COMMENT ON TABLE docprod.document_templates IS 'Templates for the different types of documents this system can produce.';

COMMENT ON TABLE docprod.documents IS 'Top-level information about documents.';

COMMENT ON FUNCTION docprod.reject_delete_value() IS 'Trigger function that rejects deletion of `variable_values` rows unless the entire document is being deleted.';

COMMENT ON TABLE docprod.variable_image_values IS 'Linking table that defines which image files are values of which variables.';

COMMENT ON TABLE docprod.variable_injection_display_styles IS '(Enum) For injected variables, whether to render them as text fragments that are suitable for including in paragraphs or as separate items on the page. Analogous to the inline/block display styles in CSS. Not applicable to all variable types.';

COMMENT ON TABLE docprod.variable_link_values IS 'Type-specific details of the values of link variables.';

COMMENT ON TABLE docprod.variable_manifest_entries IS 'Linking table that defines which variables appear in which manifests and in what order.';

COMMENT ON TABLE docprod.variable_manifests IS 'A collection of the definitions of the variables for a document template. This is how we do versioning of variable definitions. Each revision of the variable definitions is represented as a new manifest.';

COMMENT ON TABLE docprod.variable_numbers IS 'Information about number variables that is not relevant for other variable types.';

COMMENT ON TABLE docprod.variable_owners IS 'Which internal users are responsible for reviewing the values of which variables for which projects.';

COMMENT ON TABLE docprod.variable_section_default_values IS 'Section values that are set by default on newly-created documents.';

COMMENT ON TABLE docprod.variable_section_recommendations IS 'Which sections recommend using which other variables. Can vary between manifests.';

COMMENT ON TABLE docprod.variable_section_values IS 'Fragments of the contents of document sections. Each fragment is either a block of text or a usage of a variable; they are assembled in order to render the contents of the section.';

COMMENT ON TABLE docprod.variable_sections IS 'Hierarchy of sections that define the structure of the rendered document.';
COMMENT ON COLUMN docprod.variable_sections.render_heading IS 'If false, the title is not included in the rendered document. False in cases where we want a single section to have multiple content blocks; in that case, we add them as child sections with this flag set to false.';

COMMENT ON TABLE docprod.variable_select_option_values IS 'The options of a select variable that are selected in a document. Options that are not selected do not appear in this table.';

COMMENT ON TABLE docprod.variable_select_options IS 'Available options for select variables.';
COMMENT ON COLUMN docprod.variable_select_options.rendered_text IS 'What text should be rendered in the document when this option is selected. Null if the option''s name should be rendered.';

COMMENT ON TABLE docprod.variable_selects IS 'Information about select variables that is not relevant for other variable types. This table only has information about the variable as a whole; the options are in `variable_select_options`.';
COMMENT ON COLUMN docprod.variable_selects.is_multiple IS 'If true, allow multiple options to be selected. The list of selected options is considered a single value; do not set `variables.is_list` to true unless you want a list of multiple-select variables.';

COMMENT ON TABLE docprod.variable_table_columns IS 'The order of the columns in a table. Each column must be a variable whose parent is the table.';

COMMENT ON TABLE docprod.variable_table_styles IS '(Enum) How a table should be rendered visually in the document.';

COMMENT ON TABLE docprod.variable_tables IS 'Information about tables that is not relevant for other variable types.';

COMMENT ON TABLE docprod.variable_text_types IS '(Enum) Types of text stored in a text variable field.';

COMMENT ON TABLE docprod.variable_texts IS 'Information about text variables that is not relevant for other variable types.';

COMMENT ON TABLE docprod.variable_types IS '(Enum) Data types that can be assigned to variables.';

COMMENT ON TABLE docprod.variable_usage_types IS '(Enum) When a variable is used in a section, whether to inject the value of the variable or the location where the value is injected elsewhere in the doc.';

COMMENT ON TABLE docprod.variable_value_table_rows IS 'Linking table that defines which variable values are in which rows of a table.';

COMMENT ON TABLE docprod.variable_values IS 'Insert-only table with all historical and current values of all inputs.';
COMMENT ON COLUMN docprod.variable_values.text_value IS 'For text or email variables, the variable''s value. May contain newlines if the text variable is multi-line.';

COMMENT ON TABLE docprod.variables IS 'variables that can be supplied by the user. This table stores the variables themselves, not the values of the variables in a particular document. Type-specific information is in child tables such as `variable_numbers`.';
COMMENT ON COLUMN docprod.variables.is_list IS 'True if this variable is a list of values rather than a single value. If the variable is a table, true if the table can contain multiple rows.';
COMMENT ON COLUMN docprod.variables.replaces_variable_id IS 'If this is a new version of a variable that existed in a previous manifest version, the ID of the previous version of the variable. This allows the system to automatically migrate values from older variable versions when a document is updated to a new manifest version.';

COMMENT ON TABLE docprod.variable_workflow_history IS 'History of changes to the workflow details for a variable in a project. This table is append-only; edits to the values are represented as new rows, and the row with the highest ID for a given project and variable ID holds the current workflow details for that variable.';
COMMENT ON COLUMN docprod.variable_workflow_history.max_variable_value_id IS 'The highest variable value ID at the time the workflow operation happened. This is to support fetching the variable value as it existed at the time of the workflow operation. This ID is not required to be a value of the variable referenced by `variable_id` (it can be the maximum value ID for the project as a whole).';

COMMENT ON TABLE docprod.variable_workflow_statuses IS '(Enum) Workflow statuses of variables in projects. The list of valid statuses depends on the variable type.';

COMMENT ON TABLE funder.funding_entities IS 'Top-level information about Funding Entities for Funders.';

COMMENT ON TABLE funder.funding_entity_projects IS 'Which funding entities are associated with which projects.';

COMMENT ON TABLE funder.funding_entity_users IS 'Funding Entity membership.';

COMMENT ON TABLE funder.published_activities IS 'Published project activities visible to funders.';

COMMENT ON TABLE funder.published_activity_media_files IS 'Media files for published project activities visible to funders. It is possible for a file to continue to appear here after it has been removed from the activity if the removal has not been published yet.';

COMMENT ON TABLE funder.published_project_carbon_certs IS 'Carbon Certifications for published projects.';

COMMENT ON TABLE funder.published_project_details IS 'Published Project Data visible to funders.';

COMMENT ON TABLE funder.published_project_land_use IS 'Land Use Model Types and hectares of each for published projects.';

COMMENT ON TABLE funder.published_project_sdg IS 'Sustainable Development Goals for published projects.';

COMMENT ON TABLE funder.published_report_achievements IS 'Achievements of published reports.';

COMMENT ON TABLE funder.published_report_challenges IS 'Challenges and mitigation plans of published reports.';

COMMENT ON TABLE funder.published_report_photos IS 'Photos for the published accelerator project report.';

COMMENT ON TABLE funder.published_report_project_metrics IS 'Project-specific metrics of published reports.';

COMMENT ON TABLE funder.published_report_standard_metrics IS 'Standard metrics of published reports.';

COMMENT ON TABLE funder.published_report_system_metrics IS 'System metrics of published reports.';

COMMENT ON TABLE funder.published_reports IS 'Published reports visible to funders.';

-- When adding new tables, put them in alphabetical (ASCII) order.
