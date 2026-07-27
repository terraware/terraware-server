-- Reset cached hectares planted so that the new definition is used
UPDATE accelerator.report_auto_calculated_indicators
SET system_time = NULL, system_value = NULL
WHERE auto_calculated_indicator_id = 6 AND system_value IS NOT NULL
;
