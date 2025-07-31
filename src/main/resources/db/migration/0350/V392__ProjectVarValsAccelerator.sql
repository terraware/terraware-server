DROP VIEW accelerator.project_variable_values;

CREATE OR REPLACE VIEW accelerator.project_variables AS
WITH latest_variables AS (SELECT stable_id,
                                 MAX(id) as variable_id
                          FROM docprod.variables
                          GROUP BY stable_id),
     project_variables AS (SELECT DISTINCT project_id,
                                           lv.variable_id
                           FROM docprod.variable_values vv
                                    JOIN latest_variables lv ON lv.variable_id = vv.variable_id)
SELECT ap.project_id,
       lv.stable_id,
       lv.variable_id,
       v.name,
       v.is_list,
       v.variable_type_id,
       vs.is_multiple as is_multi_select
FROM accelerator.accelerator_projects ap
         LEFT JOIN project_variables pv ON pv.project_id = ap.project_id
         LEFT JOIN latest_variables lv ON lv.variable_id = pv.variable_id
         LEFT JOIN docprod.variables v ON v.id = lv.variable_id
         LEFT JOIN docprod.variable_selects vs ON vs.variable_id = lv.variable_id
;

CREATE OR REPLACE VIEW accelerator.project_variable_values AS
WITH latest_values AS (SELECT project_id,
                              variable_id,
                              list_position,
                              MAX(id) as variable_value_id
                       FROM docprod.variable_values
                       GROUP BY project_id, variable_id, list_position)
SELECT vv.project_id,
       vv.variable_id,
       vv.id     as variable_value_id,
       vv.list_position,
       vv.text_value,
       vv.number_value,
       vv.date_value,
       vlv.url   as link_url,
       vlv.title as link_title
FROM docprod.variable_values vv
    JOIN latest_values lv ON (
        (lv.project_id, lv.variable_id, lv.list_position, lv.variable_value_id) =
        (vv.project_id, vv.variable_id, vv.list_position, vv.id)
    )
         LEFT JOIN docprod.variable_link_values vlv ON vlv.variable_value_id = vv.id
WHERE NOT vv.is_deleted
;
