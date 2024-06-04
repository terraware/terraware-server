CREATE TABLE variable_usage_types (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

ALTER TABLE variable_section_values ADD COLUMN usage_type_id INTEGER REFERENCES variable_usage_types;
ALTER TABLE variable_section_values RENAME referenced_variable_id TO used_variable_id;
ALTER TABLE variable_section_values RENAME referenced_variable_type_id TO used_variable_type_id;

ALTER TABLE variable_section_values ADD CONSTRAINT usages_must_have_types
    CHECK ((used_variable_id IS NULL) = (usage_type_id IS NULL));

CREATE TABLE variable_injection_display_styles (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

ALTER TABLE variable_section_values ADD COLUMN display_style_id INTEGER REFERENCES variable_injection_display_styles;

-- Also in R__TypeCodes.sql, but duplicated here to clarify what the constraint means
INSERT INTO variable_usage_types (id, name)
VALUES (1, 'Injection'),
       (2, 'Reference');

ALTER TABLE variable_section_values ADD CONSTRAINT injection_has_display_style
    CHECK ((usage_type_id = 1) = (display_style_id IS NOT NULL));

-- 4 = image, 6 = table, 8 = section
ALTER TABLE variable_section_values ADD CONSTRAINT reference_only_for_figures_and_sections
    CHECK (usage_type_id <> 2 OR used_variable_type_id IN (4, 6, 8));

ALTER TABLE variable_section_values ADD CONSTRAINT cannot_inject_sections
    CHECK (NOT (usage_type_id = 1 AND used_variable_type_id = 8));
