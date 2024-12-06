ALTER TABLE docprod.variable_values DROP CONSTRAINT variable_values_check1;
ALTER TABLE docprod.variable_values ADD CONSTRAINT text_value_is_text_or_email
    CHECK (text_value IS NULL OR variable_type_id IN (2, 9));
