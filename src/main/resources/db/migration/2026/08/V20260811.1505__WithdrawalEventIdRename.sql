-- Seed bank and nursery withdrawal IDs are distinct entities but their ID wrapper classes share a
-- simple name, so both used to be written to the event payloads as "withdrawalId". Split them so
-- the event log can be queried for one kind of withdrawal without matching the other.

CALL event_log_create_id_index('nurseryWithdrawalId');
CALL event_log_create_id_index('seedbankWithdrawalId');

UPDATE event_log
SET original_event_class = COALESCE(original_event_class, event_class),
    original_payload     = COALESCE(original_payload, payload),
    event_class          = regexp_replace(event_class, 'V1$', 'V2'),
    payload              = (payload - 'withdrawalId') ||
                           jsonb_build_object('seedbankWithdrawalId', payload -> 'withdrawalId')
WHERE event_class IN (
    'com.terraformation.backend.seedbank.event.WithdrawalCreatedEventV1',
    'com.terraformation.backend.seedbank.event.WithdrawalUpdatedEventV1',
    'com.terraformation.backend.seedbank.event.WithdrawalDeletedEventV1'
  )
  AND payload ? 'withdrawalId';

UPDATE event_log
SET original_event_class = COALESCE(original_event_class, event_class),
    original_payload     = COALESCE(original_payload, payload),
    event_class          = regexp_replace(event_class, 'V1$', 'V2'),
    payload              = (payload - 'withdrawalId') ||
                           jsonb_build_object('nurseryWithdrawalId', payload -> 'withdrawalId')
WHERE event_class =
      'com.terraformation.backend.plantingmanagement.event.PlantingSeasonWithdrawalCreatedEventV1'
  AND payload ? 'withdrawalId';

DROP INDEX event_log_withdrawalid_idx;
