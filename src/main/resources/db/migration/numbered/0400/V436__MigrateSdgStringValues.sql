UPDATE seed_fund_reports
SET body = jsonb_set(
        body,
        '{annualDetails,sustainableDevelopmentGoals}',
        (
            SELECT jsonb_agg(
                           CASE
                               WHEN jsonb_typeof(elem -> 'goal') = 'string'
                                   THEN jsonb_set(
                                       elem,
                                       '{goal}',
                                       CASE
                                           WHEN elem ->> 'goal' = 'NoPoverty' THEN to_jsonb(1)
                                           WHEN elem ->> 'goal' = 'ZeroHunger' THEN to_jsonb(2)
                                           WHEN elem ->> 'goal' = 'GoodHealth' THEN to_jsonb(3)
                                           WHEN elem ->> 'goal' = 'QualityEducation' THEN to_jsonb(4)
                                           WHEN elem ->> 'goal' = 'GenderEquality' THEN to_jsonb(5)
                                           WHEN elem ->> 'goal' = 'CleanWater' THEN to_jsonb(6)
                                           WHEN elem ->> 'goal' = 'AffordableEnergy' THEN to_jsonb(7)
                                           WHEN elem ->> 'goal' = 'DecentWork' THEN to_jsonb(8)
                                           WHEN elem ->> 'goal' = 'Industry' THEN to_jsonb(9)
                                           WHEN elem ->> 'goal' = 'ReducedInequalities' THEN to_jsonb(10)
                                           WHEN elem ->> 'goal' = 'SustainableCities' THEN to_jsonb(11)
                                           WHEN elem ->> 'goal' = 'ResponsibleConsumption' THEN to_jsonb(12)
                                           WHEN elem ->> 'goal' = 'ClimateAction' THEN to_jsonb(13)
                                           WHEN elem ->> 'goal' = 'LifeBelowWater' THEN to_jsonb(14)
                                           WHEN elem ->> 'goal' = 'LifeOnLand' THEN to_jsonb(15)
                                           WHEN elem ->> 'goal' = 'Peace' THEN to_jsonb(16)
                                           WHEN elem ->> 'goal' = 'Partnerships' THEN to_jsonb(17)
                                           ELSE elem -> 'goal'  -- keep as-is if unknown
                                           END
                                        )
                               ELSE elem
                               END
                   )
            FROM jsonb_array_elements(body -> 'annualDetails' -> 'sustainableDevelopmentGoals') AS elem
        )
           )
WHERE EXISTS (
    SELECT 1
    FROM jsonb_array_elements(body -> 'annualDetails' -> 'sustainableDevelopmentGoals') AS elem
    WHERE jsonb_typeof(elem -> 'goal') = 'string'
);
