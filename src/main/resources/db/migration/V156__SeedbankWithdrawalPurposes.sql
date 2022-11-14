UPDATE seedbank.withdrawals
SET purpose_id = (SELECT id FROM seedbank.withdrawal_purposes WHERE name = 'Out-planting')
WHERE purpose_id = (SELECT id FROM seedbank.withdrawal_purposes WHERE name = 'Propagation');

UPDATE seedbank.withdrawals
SET purpose_id = (SELECT id FROM seedbank.withdrawal_purposes WHERE name = 'Other')
WHERE purpose_id IN (SELECT id
                     FROM seedbank.withdrawal_purposes
                     WHERE name IN (
                                    'Outreach or Education',
                                    'Research',
                                    'Broadcast',
                                    'Share with Another Site'
                         ));

DELETE
FROM seedbank.withdrawal_purposes
WHERE name IN (
               'Propagation',
               'Outreach or Education',
               'Research',
               'Broadcast',
               'Share with Another Site'
    );
