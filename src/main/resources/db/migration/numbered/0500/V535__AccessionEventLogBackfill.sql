CALL event_log_create_id_index('viabilityTestId');

-- Accessions that were created as part of a CSV import.
INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    a.created_by,
    a.created_time,
    'com.terraformation.backend.seedbank.event.AccessionUploadedEventV1',
    json_object(
        '_historical' VALUE true,
        'accessionId' VALUE a.id,
        'accessionNumber' VALUE a.number,
        'facilityId' VALUE a.facility_id,
        'organizationId' VALUE f.organization_id
        ABSENT ON NULL
    )::JSONB
FROM seedbank.accessions a
JOIN facilities f ON a.facility_id = f.id
JOIN seedbank.data_sources ds ON a.data_source_id = ds.id
WHERE ds.name = 'File Import';

-- Accessions that were created manually via the web app or the seed collector app.
INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    a.created_by,
    a.created_time,
    'com.terraformation.backend.seedbank.event.AccessionCreatedEventV1',
    json_object(
        '_historical' VALUE true,
        'accessionId' VALUE a.id,
        'accessionNumber' VALUE a.number,
        'dataSource' VALUE ds.name,
        'facilityId' VALUE a.facility_id,
        'organizationId' VALUE f.organization_id
        ABSENT ON NULL
    )::JSONB
FROM seedbank.accessions a
JOIN facilities f ON a.facility_id = f.id
JOIN seedbank.data_sources ds ON a.data_source_id = ds.id
WHERE ds.name <> 'File Import';

-- Accession state transitions. The initial row (no old state) is the "Accession created"
-- transition and is skipped because the creation is already covered above.
INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    ash.updated_by,
    ash.updated_time,
    'com.terraformation.backend.seedbank.event.AccessionStateChangedEventV1',
    json_object(
        '_historical' VALUE true,
        'accessionId' VALUE ash.accession_id,
        'changedFrom' VALUE json_object('state' VALUE os.name ABSENT ON NULL),
        'changedTo' VALUE json_object('state' VALUE ns.name ABSENT ON NULL),
        'facilityId' VALUE a.facility_id,
        'organizationId' VALUE f.organization_id,
        'reason' VALUE ash.reason
        ABSENT ON NULL
    )::JSONB
FROM seedbank.accession_state_history ash
JOIN seedbank.accessions a ON ash.accession_id = a.id
JOIN facilities f ON a.facility_id = f.id
JOIN seedbank.accession_states os ON ash.old_state_id = os.id
JOIN seedbank.accession_states ns ON ash.new_state_id = ns.id
WHERE ash.old_state_id IS NOT NULL;

-- Manual (Observed) edits to an accession's remaining quantity. The window functions run over ALL
-- quantity-history rows (both Observed and Computed) so that changedFrom reflects the immediately
-- preceding remaining quantity, including any intervening computed (withdrawal) changes. Only
-- Observed rows with a predecessor (rn > 1) are emitted; this drops each accession's first quantity
-- row, which is the creation baseline that live code never emits a quantity event for.
--
-- Accepted approximation: for an accession created with no initial remaining quantity, the very
-- first manual quantity-set is also the first quantity-history row and is therefore dropped too.
WITH quantity_history AS (
    SELECT
        aqh.id,
        aqh.created_by,
        aqh.created_time,
        aqh.accession_id,
        aqh.notes,
        aqht.name AS history_type_name,
        a.facility_id,
        f.organization_id,
        json_object(
            'quantity' VALUE aqh.remaining_quantity,
            'units' VALUE squ.name
        )::JSONB AS quantity_obj
    FROM seedbank.accession_quantity_history aqh
    JOIN seedbank.accession_quantity_history_types aqht ON aqh.history_type_id = aqht.id
    JOIN seedbank.accessions a ON aqh.accession_id = a.id
    JOIN facilities f ON a.facility_id = f.id
    JOIN seedbank.seed_quantity_units squ ON aqh.remaining_units_id = squ.id
),
quantity_history_windowed AS (
    SELECT
        quantity_history.*,
        ROW_NUMBER() OVER w AS rn,
        LAG(quantity_obj) OVER w AS prev_quantity_obj
    FROM quantity_history
    WINDOW w AS (PARTITION BY accession_id ORDER BY created_time, id)
)
INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    created_by,
    created_time,
    'com.terraformation.backend.seedbank.event.AccessionQuantityUpdatedEventV1',
    json_object(
        '_historical' VALUE true,
        'accessionId' VALUE accession_id,
        'changedFrom' VALUE json_object('quantity' VALUE prev_quantity_obj ABSENT ON NULL),
        'changedTo' VALUE json_object('quantity' VALUE quantity_obj ABSENT ON NULL),
        'facilityId' VALUE facility_id,
        'notes' VALUE notes,
        'organizationId' VALUE organization_id
        ABSENT ON NULL
    )::JSONB
FROM quantity_history_windowed
WHERE history_type_name = 'Observed' AND rn > 1;

-- Withdrawals. created_by can be null on old rows, so fall back to the accession's creator.
INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    COALESCE(w.created_by, a.created_by),
    w.created_time,
    'com.terraformation.backend.seedbank.event.WithdrawalCreatedEventV1',
    json_object(
        '_historical' VALUE true,
        'accessionId' VALUE w.accession_id,
        'batchId' VALUE w.batch_id,
        'date' VALUE w.date,
        'facilityId' VALUE a.facility_id,
        'notes' VALUE w.notes,
        'organizationId' VALUE f.organization_id,
        'purpose' VALUE wp.name,
        'staffResponsible' VALUE w.staff_responsible,
        'withdrawalId' VALUE w.id,
        'withdrawnQuantity' VALUE CASE
            WHEN w.withdrawn_quantity IS NOT NULL AND squ.name IS NOT NULL
            THEN json_object('quantity' VALUE w.withdrawn_quantity, 'units' VALUE squ.name)::JSONB
        END
        ABSENT ON NULL
    )::JSONB
FROM seedbank.withdrawals w
JOIN seedbank.accessions a ON w.accession_id = a.id
JOIN facilities f ON a.facility_id = f.id
LEFT JOIN seedbank.withdrawal_purposes wp ON w.purpose_id = wp.id
LEFT JOIN seedbank.seed_quantity_units squ ON w.withdrawn_units_id = squ.id;

-- Viability tests. This table has no created_by/created_time columns, so the events are
-- attributed and backdated to the parent accession.
INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    a.created_by,
    a.created_time,
    'com.terraformation.backend.seedbank.event.ViabilityTestCreatedEventV1',
    json_object(
        '_historical' VALUE true,
        'accessionId' VALUE vt.accession_id,
        'endDate' VALUE vt.end_date,
        'facilityId' VALUE a.facility_id,
        'organizationId' VALUE f.organization_id,
        'seedType' VALUE vtst.name,
        'seedsTested' VALUE vt.seeds_sown,
        'startDate' VALUE vt.start_date,
        'substrate' VALUE vtsu.name,
        'testType' VALUE vtt.name,
        'treatment' VALUE st.name,
        'viabilityTestId' VALUE vt.id
        ABSENT ON NULL
    )::JSONB
FROM seedbank.viability_tests vt
JOIN seedbank.accessions a ON vt.accession_id = a.id
JOIN facilities f ON a.facility_id = f.id
JOIN seedbank.viability_test_types vtt ON vt.test_type = vtt.id
LEFT JOIN seedbank.viability_test_substrates vtsu ON vt.substrate_id = vtsu.id
LEFT JOIN seedbank.viability_test_seed_types vtst ON vt.seed_type_id = vtst.id
LEFT JOIN seed_treatments st ON vt.treatment_id = st.id;

-- Photos added to accessions. created_by can be null on old file rows, so fall back to the
-- accession's creator.
INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    COALESCE(fi.created_by, a.created_by),
    fi.created_time,
    'com.terraformation.backend.seedbank.event.AccessionPhotoAddedEventV1',
    json_object(
        '_historical' VALUE true,
        'accessionId' VALUE ap.accession_id,
        'contentType' VALUE fi.content_type,
        'facilityId' VALUE a.facility_id,
        'fileId' VALUE ap.file_id,
        'filename' VALUE fi.file_name,
        'organizationId' VALUE f.organization_id
        ABSENT ON NULL
    )::JSONB
FROM seedbank.accession_photos ap
JOIN files fi ON ap.file_id = fi.id
JOIN seedbank.accessions a ON ap.accession_id = a.id
JOIN facilities f ON a.facility_id = f.id;
