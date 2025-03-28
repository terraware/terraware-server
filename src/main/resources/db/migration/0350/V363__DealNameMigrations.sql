UPDATE accelerator.project_accelerator_details as details
SET details.deal_name = (
    SELECT values.text_value
    FROM docprod.variable_values as values
    WHERE values.is_deleted = false
      AND values.variable_id = (
        SELECT variables.id
        FROM docprod.variables as variables
        WHERE variables.stable_id = '472'
        ORDER BY variables.id DESC
        LIMIT 1
    )
      AND values.project_id = details.project_id
    ORDER BY values.id DESC
    LIMIT 1
);
