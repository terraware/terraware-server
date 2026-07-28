ALTER TABLE tracking.recorded_trees ADD COLUMN tree_crown_diameter_cm INTEGER;

ALTER TABLE tracking.recorded_trees ADD CONSTRAINT tree_crown_diameter_positive
    CHECK (tree_crown_diameter_cm >= 0);

-- Growth forms: 1 = tree, 2 = shrub, 3 = trunk
ALTER TABLE tracking.recorded_trees ADD CONSTRAINT tree_crown_diameter_requires_tree
    CHECK (
        tree_growth_form_id IN (1, 3)
        OR tree_crown_diameter_cm IS NULL
    );
