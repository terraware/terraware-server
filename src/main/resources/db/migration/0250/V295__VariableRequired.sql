ALTER TABLE docprod.variables DISABLE TRIGGER variables_no_update;

ALTER TABLE docprod.variables ADD COLUMN is_required BOOLEAN;
UPDATE docprod.variables SET is_required = FALSE;
ALTER TABLE docprod.variables ALTER COLUMN is_required SET NOT NULL;

ALTER TABLE docprod.variables ENABLE TRIGGER variables_no_update;
