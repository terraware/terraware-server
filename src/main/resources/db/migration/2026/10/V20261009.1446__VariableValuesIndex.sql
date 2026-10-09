CREATE INDEX ON docprod.variable_values (project_id, variable_id, list_position) INCLUDE (id);

DROP INDEX docprod.variable_values_project_id_idx;
