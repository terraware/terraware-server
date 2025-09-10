INSERT INTO seedbank.accession_quantity_history_types (id, name)
VALUES (1, 'Observed'),
       (2, 'Computed')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seedbank.accession_states (id, name, active)
VALUES (5, 'Awaiting Check-In', TRUE),
       (10, 'Pending', TRUE),
       (15, 'Awaiting Processing', TRUE),
       (20, 'Processing', TRUE),
       (30, 'Processed', TRUE),
       (40, 'Drying', TRUE),
       (50, 'Dried', TRUE),
       (60, 'In Storage', TRUE),
       (70, 'Withdrawn', FALSE),
       (75, 'Used Up', FALSE),
       (80, 'Nursery', FALSE)
ON CONFLICT (id) DO UPDATE SET name   = excluded.name,
                               active = excluded.active;

INSERT INTO accelerator.activity_media_types (id, name)
VALUES (1, 'Photo'),
       (2, 'Video')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.activity_types (id, name)
VALUES (1, 'Seed Collection'),
       (2, 'Nursery'),
       (3, 'Planting'),
       (4, 'Monitoring'),
       (5, 'Site Visit'),
       (6, 'Stakeholder Engagement'),
       (7, 'Drone Flight')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.application_module_statuses (id, name)
VALUES (1, 'Incomplete'),
       (2, 'Complete')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.application_statuses (id, name)
VALUES (1, 'Not Submitted'),
       (2, 'Failed Pre-screen'),
       (3, 'Passed Pre-screen'),
       (4, 'Submitted'),
       (5, 'Sourcing Team Review'),
       (6, 'GIS Assessment'),
       (7, 'Expert Review'),
       (8, 'Carbon Assessment'),
       (9, 'P0 Eligible'),
       (10, 'Accepted'),
       (11, 'Issue Active'),
       (12, 'Issue Reassessment'),
       (13, 'Not Eligible')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO nursery.batch_quantity_history_types (id, name)
VALUES (1, 'Observed'),
       (2, 'Computed'),
       (3, 'StatusChanged')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO nursery.batch_substrates (id, name)
VALUES (1, 'MediaMix'),
       (2, 'Soil'),
       (3, 'Sand'),
       (4, 'Moss'),
       (5, 'PerliteVermiculite'),
       (6, 'Other')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO tracking.biomass_forest_types (id, name)
VALUES (1, 'Terrestrial'),
       (2, 'Mangrove')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO chat_memory_message_types (id, name)
VALUES (1, 'Assistant'),
       (2, 'System'),
       (3, 'ToolResponse'),
       (4, 'User')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.cohort_phases (id, name)
VALUES (0, 'Phase 0 - Due Diligence'),
       (1, 'Phase 1 - Feasibility Study'),
       (2, 'Phase 2 - Plan and Scale'),
       (3, 'Phase 3 - Implement and Monitor'),
       (100, 'Pre-Screen'),
       (101, 'Application')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seedbank.collection_sources (id, name)
VALUES (1, 'Wild'),
       (2, 'Reintroduced'),
       (3, 'Cultivated'),
       (4, 'Other')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO conservation_categories (id, name)
VALUES ('CR', 'Critically Endangered'),
       ('DD', 'Data Deficient'),
       ('EN', 'Endangered'),
       ('EW', 'Extinct in the Wild'),
       ('EX', 'Extinct'),
       ('LC', 'Least Concern'),
       ('NE', 'Not Evaluated'),
       ('NT', 'Near Threatened'),
       ('VU', 'Vulnerable')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seedbank.data_sources (id, name)
VALUES (1, 'Web'),
       (2, 'Seed Collector App'),
       (3, 'File Import')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.deliverable_categories (id, name, internal_interest_id)
VALUES (1, 'Compliance', 1),
       (2, 'Financial Viability', 2),
       (3, 'GIS', 3),
       (4, 'Carbon Eligibility', 4),
       (5, 'Stakeholders and Community Impact', 5),
       (6, 'Proposed Restoration Activities', 6),
       (7, 'Verra Non-Permanence Risk Tool (NPRT)', 7),
       (8, 'Supplemental Files', 8)
ON CONFLICT (id) DO UPDATE SET name                 = excluded.name,
                               internal_interest_id = excluded.internal_interest_id;

INSERT INTO accelerator.deliverable_types (id, name)
VALUES (1, 'Document'),
       (2, 'Species'),
       (3, 'Questions')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.document_stores (id, name)
VALUES (1, 'Dropbox'),
       (2, 'Google'),
       (3, 'External')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO device_template_categories (id, name)
VALUES (1, 'PV'),
       (2, 'Seed Bank Default')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO docprod.dependency_conditions (id, name)
VALUES (1, 'eq'),
       (2, 'gt'),
       (3, 'gte'),
       (4, 'lt'),
       (5, 'lte'),
       (6, 'neq')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO docprod.document_statuses (id, name)
VALUES (1, 'Draft'),
       (2, 'Locked'),
       (3, 'Published'),
       (4, 'Ready'),
       (5, 'Submitted')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO docprod.variable_injection_display_styles (id, name)
VALUES (1, 'Inline'),
       (2, 'Block')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO docprod.variable_table_styles (id, name)
VALUES (1, 'Horizontal'),
       (2, 'Vertical')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO docprod.variable_text_types (id, name)
VALUES (1, 'SingleLine'),
       (2, 'MultiLine')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO docprod.variable_types (id, name)
VALUES (1, 'Number'),
       (2, 'Text'),
       (3, 'Date'),
       (4, 'Image'),
       (5, 'Select'),
       (6, 'Table'),
       (7, 'Link'),
       (8, 'Section'),
       (9, 'Email')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO docprod.variable_usage_types (id, name)
VALUES (1, 'Injection'),
       (2, 'Reference')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO ecosystem_types (id, name)
VALUES (1, 'Boreal forests/Taiga'),
       (2, 'Deserts and xeric shrublands'),
       (3, 'Flooded grasslands and savannas'),
       (4, 'Mangroves'),
       (5, 'Mediterranean forests, woodlands and scrubs'),
       (6, 'Montane grasslands and shrublands'),
       (7, 'Temperate broad leaf and mixed forests'),
       (8, 'Temperate coniferous forest'),
       (9, 'Temperate grasslands, savannas and shrublands'),
       (10, 'Tropical and subtropical coniferous forests'),
       (11, 'Tropical and subtropical dry broad leaf forests'),
       (12, 'Tropical and subtropical grasslands, savannas and shrublands'),
       (13, 'Tropical and subtropical moist broad leaf forests'),
       (14, 'Tundra')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.event_statuses (id, name)
VALUES (1, 'Not Started'),
       (2, 'Starting Soon'),
       (3, 'In Progress'),
       (4, 'Ended')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.event_types (id, name)
VALUES (1, 'One-on-One Session'),
       (2, 'Workshop'),
       (3, 'Live Session'),
       (4, 'Recorded Session')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO facility_connection_states (id, name)
VALUES (1, 'Not Connected'),
       (2, 'Connected'),
       (3, 'Configured')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO facility_types (id, name)
VALUES (1, 'Seed Bank'),
       (2, 'Desalination'),
       (3, 'Reverse Osmosis'),
       (4, 'Nursery')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO global_roles (id, name)
VALUES (1, 'Super-Admin'),
       (2, 'Accelerator Admin'),
       (3, 'TF Expert'),
       (4, 'Read Only')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO growth_forms (id, name)
VALUES (1, 'Tree'),
       (2, 'Shrub'),
       (3, 'Forb'),
       (4, 'Graminoid'),
       (5, 'Fern'),
       (6, 'Fungus'),
       (7, 'Lichen'),
       (8, 'Moss'),
       (9, 'Vine'),
       (10, 'Liana'),
       (12, 'Subshrub'),
       (13, 'Multiple Forms'),
       (14, 'Mangrove'),
       (15, 'Herb')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.internal_interests (id, name)
VALUES (1, 'Compliance'),
       (2, 'Financial Viability'),
       (3, 'GIS'),
       (4, 'Carbon Eligibility'),
       (5, 'Stakeholders and Community Impact'),
       (6, 'Proposed Restoration Activities'),
       (7, 'Verra Non-Permanence Risk Tool (NPRT)'),
       (8, 'Supplemental Files'),
       (101, 'Sourcing')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO land_use_model_types (id, name)
VALUES (1, 'Native Forest'),
       (2, 'Monoculture'),
       (3, 'Sustainable Timber'),
       (4, 'Other Timber'),
       (5, 'Mangroves'),
       (6, 'Agroforestry'),
       (7, 'Silvopasture'),
       (8, 'Other Land-Use Model')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO managed_location_types (id, name)
VALUES (1, 'SeedBank'),
       (2, 'Nursery'),
       (3, 'PlantingSite')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO tracking.mangrove_tides (id, name)
VALUES (1, 'Low'),
       (2, 'High')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.metric_components (id, name)
VALUES (1, 'Project Objectives'),
       (2, 'Climate'),
       (3, 'Community'),
       (4, 'Biodiversity')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.metric_types (id, name)
VALUES (1, 'Activity'),
       (2, 'Output'),
       (3, 'Outcome'),
       (4, 'Impact')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO notification_criticalities (id, name)
VALUES (1, 'Info'),
       (2, 'Warning'),
       (3, 'Error'),
       (4, 'Success')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO notification_types (id, name, notification_criticality_id)
VALUES (1, 'User Added to Organization', 1),
       (2, 'Facility Idle', 2),
       (3, 'Facility Alert Requested', 3),
       (6, 'Accession Scheduled to End Drying', 1),
       (12, 'Sensor Out Of Bounds', 3),
       (13, 'Unknown Automation Triggered', 3),
       (14, 'Device Unresponsive', 3),
       (15, 'Nursery Seedling Batch Ready', 1),
       (16, 'Seed Fund Report Created', 1),
       (17, 'Observation Upcoming', 1),
       (18, 'Observation Started', 1),
       (19, 'Schedule Observation', 1),
       (20, 'Schedule Observation Reminder', 1),
       (21, 'Observation Not Scheduled (Support)', 1),
       (22, 'Planting Season Started', 1),
       (23, 'Schedule Planting Season', 2),
       (24, 'Planting Season Not Scheduled (Support)', 2),
       (25, 'Deliverable Ready For Review', 1),
       (26, 'Deliverable Status Updated', 1),
       (27, 'Event Reminder', 1),
       (28, 'Participant Project Species Added To Project', 1),
       (29, 'Participant Project Species Approved Species Edited', 1),
       (30, 'Application Submitted', 1),
       (31, 'Completed Section Variable Updated', 1),
       (32, 'Accelerator Report Submitted', 1),
       (33, 'Accelerator Report Upcoming', 1),
       (34, 'Accelerator Report Published', 1)
ON CONFLICT (id) DO UPDATE SET name                        = excluded.name,
                               notification_criticality_id = excluded.notification_criticality_id;

INSERT INTO tracking.observable_conditions (id, name)
VALUES (1, 'AnimalDamage'),
       (2, 'FastGrowth'),
       (3, 'FavorableWeather'),
       (4, 'Fungus'),
       (5, 'Pests'),
       (6, 'SeedProduction'),
       (7, 'UnfavorableWeather')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO tracking.observation_photo_types (id, name)
VALUES (1, 'Plot'),
       (2, 'Quadrat'),
       (3, 'Soil')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO tracking.observation_plot_positions (id, name)
VALUES (1, 'SouthwestCorner'),
       (2, 'SoutheastCorner'),
       (3, 'NortheastCorner'),
       (4, 'NorthwestCorner')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO tracking.observation_plot_statuses (id, name)
VALUES (1, 'Unclaimed'),
       (2, 'Claimed'),
       (3, 'Completed'),
       (4, 'Not Observed')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO tracking.observation_states (id, name)
VALUES (1, 'Upcoming'),
       (2, 'InProgress'),
       (3, 'Completed'),
       (4, 'Overdue'),
       (5, 'Abandoned')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO tracking.observation_types (id, name)
VALUES (1, 'Monitoring'),
       (2, 'Biomass Measurements')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO organization_types (id, name)
VALUES (1, 'Government'),
       (2, 'NGO'),
       (3, 'Arboreta'),
       (4, 'Academia'),
       (5, 'ForProfit'),
       (6, 'Other')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.pipelines (id, name)
VALUES (1, 'Accelerator Projects'),
       (2, 'Carbon Supply'),
       (3, 'Carbon Waitlist')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO plant_material_sourcing_methods (id, name)
VALUES (1, 'Seed collection & germination'),
       (2, 'Seed purchase & germination'),
       (3, 'Mangrove propagules'),
       (4, 'Vegetative propagation'),
       (5, 'Wildling harvest'),
       (6, 'Seedling purchase'),
       (7, 'Other')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO tracking.planting_types (id, name)
VALUES (1, 'Delivery'),
       (2, 'Reassignment From'),
       (3, 'Reassignment To'),
       (4, 'Undo')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO project_internal_roles (id, name)
VALUES (1, 'Project Lead'),
       (2, 'Restoration Lead'),
       (3, 'Social Lead'),
       (4, 'GIS Lead'),
       (5, 'Carbon Lead'),
       (6, 'Phase Lead'),
       (7, 'Regional Expert'),
       (8, 'Project Finance Lead'),
       (9, 'Climate Impact Lead'),
       (10, 'Legal Lead'),
       (11, 'Consultant')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO tracking.recorded_plant_statuses (id, name)
VALUES (1, 'Live'),
       (2, 'Dead'),
       (3, 'Existing')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO tracking.recorded_species_certainties (id, name)
VALUES (1, 'Known'),
       (2, 'Other'),
       (3, 'Unknown')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO regions (id, name)
VALUES (1, 'Antarctica'),
       (2, 'East Asia & Pacific'),
       (3, 'Europe & Central Asia'),
       (4, 'Latin America & Caribbean'),
       (5, 'Middle East & North Africa'),
       (6, 'North America'),
       (7, 'Oceania'),
       (8, 'South Asia'),
       (9, 'Sub-Saharan Africa')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.report_frequencies (id, name)
VALUES (1, 'Quarterly'),
       (2, 'Annual')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.report_metric_statuses (id, name)
VALUES (1, 'Achieved'),
       (2, 'On-Track'),
       (3, 'Unlikely')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

-- The id must match the quarter number for constraint checking
INSERT INTO accelerator.report_quarters (id, name)
VALUES (1, 'Q1'),
       (2, 'Q2'),
       (3, 'Q3'),
       (4, 'Q4')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.report_statuses (id, name)
VALUES (1, 'Not Submitted'),
       (2, 'Submitted'),
       (3, 'Approved'),
       (4, 'Needs Update'),
       (5, 'Not Needed')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO roles (id, name)
VALUES (1, 'Contributor'),
       (2, 'Manager'),
       (3, 'Admin'),
       (4, 'Owner'),
       (5, 'Terraformation Contact')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.score_categories (id, name)
VALUES (1, 'Carbon'),
       (2, 'Finance'),
       (3, 'Forestry'),
       (4, 'Legal'),
       (5, 'Social Impact'),
       (6, 'GIS'),
       (7, 'Climate Impact'),
       (8, 'Expansion Potential'),
       (9, 'Experience and Understanding'),
       (10, 'Operational Capacity'),
       (11, 'Responsiveness and Attention to Detail'),
       (12, 'Values Alignment')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.system_metrics (id, name, description, type_id, component_id, reference, is_publishable)
VALUES (1, 'Seeds Collected', 'Total seed count checked-into accessions.', 2, 2, '1.1', false),
       (2, 'Seedlings', 'Plants in the nursery, including those provided by partners, where available. Not applicable for mangrove projects (input 0).', 2, 2, '1.2', true),
       (3, 'Trees Planted', 'Total trees (and plants) planted in the field.', 2, 2, '1.3', true),
       (4, 'Species Planted', 'Total species of the plants/trees planted.', 2, 2, '1.4', true),
       (5, 'Mortality Rate', 'Mortality rate of plantings.', 3, 2, '2', true),
       (6, 'Hectares Planted', 'This is the hectares marked as “Planting Complete” within the Project Area.', 2, 2, '1.1.1.1', true),
       (7, 'Survival Rate', 'Survival rate of plantings.', 3, 2, '2', true)
ON CONFLICT (id) DO UPDATE
SET name = excluded.name,
    description = excluded.description,
    type_id = excluded.type_id,
    component_id = excluded.component_id,
    reference = excluded.reference,
    is_publishable = excluded.is_publishable;

INSERT INTO seed_fund_report_statuses (id, name)
VALUES (1, 'New'),
       (2, 'In Progress'),
       (3, 'Locked'),
       (4, 'Submitted')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seedbank.seed_quantity_units (id, name)
VALUES (1, 'Seeds'),
       (2, 'Grams'),
       (3, 'Milligrams'),
       (4, 'Kilograms'),
       (5, 'Ounces'),
       (6, 'Pounds')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seed_storage_behaviors (id, name)
VALUES (1, 'Orthodox'),
       (2, 'Recalcitrant'),
       (3, 'Intermediate'),
       (4, 'Unknown'),
       (5, 'Likely Orthodox'),
       (6, 'Likely Recalcitrant'),
       (7, 'Likely Intermediate'),
       (8, 'Intermediate - Cool Temperature Sensitive'),
       (9, 'Intermediate - Partial Desiccation Tolerant'),
       (10, 'Intermediate - Short Lived'),
       (11, 'Likely Intermediate - Cool Temperature Sensitive'),
       (12, 'Likely Intermediate - Partial Desiccation Tolerant'),
       (13, 'Likely Intermediate - Short Lived')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seed_treatments (id, name)
VALUES (1, 'Soak'),
       (2, 'Scarify'),
       (3, 'Chemical'),
       (4, 'Stratification'),
       (5, 'Other'),
       (6, 'Light')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO species_native_categories (id, name)
VALUES (1, 'Native'),
       (2, 'Non-native')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO species_problem_fields (id, name)
VALUES (1, 'Scientific Name')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO species_problem_types (id, name)
VALUES (1, 'Name Misspelled'),
       (2, 'Name Not Found'),
       (3, 'Name Is Synonym')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO successional_groups (id, name)
VALUES (1, 'Pioneer'),
       (2, 'Early secondary'),
       (3, 'Late secondary'),
       (4, 'Mature')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.submission_statuses (id, name)
VALUES (1, 'Not Submitted'),
       (2, 'In Review'),
       (3, 'Needs Translation'),
       (4, 'Approved'),
       (5, 'Rejected'),
       (6, 'Not Needed'),
       (7, 'Completed')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO timeseries_types (id, name)
VALUES (1, 'Numeric'),
       (2, 'Text')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO tracking.tree_growth_forms (id, name)
VALUES (1, 'Tree'),
       (2, 'Shrub'),
       (3, 'Trunk')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO upload_problem_types (id, name)
VALUES (1, 'Unrecognized Value'),
       (2, 'Missing Required Value'),
       (3, 'Duplicate Value'),
       (4, 'Malformed Value')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

-- These are not in time order; add new intermediate statuses to the end rather than renumbering
-- existing ones.
INSERT INTO upload_statuses (id, name, finished)
VALUES (1, 'Receiving', FALSE),
       (2, 'Validating', FALSE),
       (3, 'Processing', FALSE),
       (4, 'Completed', TRUE),
       (5, 'Processing Failed', TRUE),
       (6, 'Invalid', TRUE),
       (7, 'Receiving Failed', TRUE),
       (8, 'Awaiting Validation', FALSE),
       (9, 'Awaiting User Action', FALSE),
       (10, 'Awaiting Processing', FALSE)
ON CONFLICT (id) DO UPDATE SET name     = excluded.name,
                               finished = excluded.finished;

INSERT INTO upload_types (id, name, expire_files)
VALUES (1, 'Species CSV', TRUE),
       (2, 'Accession CSV', TRUE),
       (3, 'Seedling Batch CSV', TRUE)
ON CONFLICT (id) DO UPDATE SET name         = excluded.name,
                               expire_files = excluded.expire_files;

INSERT INTO user_types (id, name)
VALUES (1, 'Individual'),
       -- 2 was Super-Admin, which is now a global role
       (3, 'Device Manager'),
       (4, 'System'),
       (5, 'Funder')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO docprod.variable_workflow_statuses (id, name)
VALUES (1, 'Not Submitted'),
       (2, 'In Review'),
       (3, 'Needs Translation'),
       (4, 'Approved'),
       (5, 'Rejected'),
       (6, 'Not Needed'),
       (7, 'Incomplete'),
       (8, 'Complete')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seedbank.viability_test_seed_types (id, name)
VALUES (1, 'Fresh'),
       (2, 'Stored')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seedbank.viability_test_substrates (id, name)
VALUES (1, 'Nursery Media'),
       (2, 'Agar'),
       (3, 'Paper'),
       (4, 'Other'),
       (5, 'Sand'),
       (6, 'Media Mix'),
       (7, 'Soil'),
       (8, 'Moss'),
       (9, 'Perlite/Vermiculite'),
       (10, 'None')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seedbank.viability_test_types (id, name)
VALUES (1, 'Lab'),
       (2, 'Nursery'),
       (3, 'Cut')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accelerator.vote_options (id, name)
VALUES (1, 'No'),
       (2, 'Conditional'),
       (3, 'Yes')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO nursery.withdrawal_purposes (id, name)
VALUES (1, 'Nursery Transfer'), -- ID 1 is used in a check constraint; don't change it
       (2, 'Dead'),
       (3, 'Out Plant'),
       (4, 'Other'),
       (5, 'Undo')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seedbank.withdrawal_purposes (id, name)
VALUES (6, 'Other'),
       (7, 'Viability Testing'),
       (8, 'Out-planting'),
       (9, 'Nursery')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO wood_density_levels (id, name)
VALUES (1, 'Species'),
       (2, 'Genus'),
       (3, 'Family')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

-- Depends on accelerator.pipelines
INSERT INTO accelerator.deal_stages (id, name, pipeline_id)
VALUES (101, 'Phase 0 (Doc Review)', 1),
       (102, 'Phase 1', 1),
       (103, 'Phase 2', 1),
       (104, 'Phase 3', 1),
       (105, 'Graduated, Finished Planting', 1),
       (106, 'Non Graduate', 1),
       (201, 'Application Submitted', 2),
       (202, 'Project Lead Screening Review', 2),
       (203, 'Screening Questions Ready for Review', 2),
       (204, 'Carbon Pre-Check', 2),
       (205, 'Submission Requires Follow Up', 2),
       (206, 'Carbon Eligible', 2),
       (207, 'Closed Lost', 2),
       (301, 'Issue Active', 3),
       (302, 'Issue Pending', 3),
       (303, 'Issue Reesolved', 3)
ON CONFLICT (id) DO UPDATE SET name = excluded.name,
                               pipeline_id = excluded.pipeline_id;

-- Depends on user_types
INSERT INTO internal_tags (id, name, description, is_system, created_by, created_time, modified_by, modified_time)
SELECT t.id, t.name, t.description, TRUE, su.id, NOW(), su.id, NOW()
FROM (
         SELECT id
         FROM users
         WHERE user_type_id = 4
           AND email = 'system'
     ) AS su, (
         VALUES (1, 'Reporter', 'Organization must submit reports to Terraformation.'),
                (2, 'Internal', 'Terraformation-managed internal organization, not a customer.'),
                (3, 'Testing', 'Used for internal testing; may contain invalid data.'),
                (4, 'Accelerator', 'Organization is an accelerator participant.')
     ) AS t (id, name, description)
ON CONFLICT (id) DO UPDATE SET name = excluded.name,
                               description = excluded.description;


-- When adding new tables, put them in alphabetical (ASCII) order unless they depend on other
-- tables with alphabetically-greater names.
