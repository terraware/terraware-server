-- View to find the latest variable value id for each project and stable_id
CREATE VIEW accelerator.project_variable_values AS
WITH latest_variables AS (SELECT stable_id,
                                 MAX(id) as variable_id
                          FROM docprod.variables
                          GROUP BY stable_id),
     latest_values AS (SELECT project_id,
                              variable_id,
                              MAX(id) as variable_value_id
                       FROM docprod.variable_values
                       GROUP BY project_id, variable_id)
SELECT lv.stable_id,
       lval.project_id,
       lv.variable_id,
       lval.variable_value_id
FROM latest_variables lv
         JOIN latest_values lval ON lv.variable_id = lval.variable_id
;
