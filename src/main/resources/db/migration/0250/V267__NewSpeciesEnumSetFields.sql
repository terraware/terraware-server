-- Successional Group Sourcing Method enum table
CREATE TABLE public.successional_groups (
    id INTEGER NOT NULL PRIMARY KEY,
    name TEXT NOT NULL
);

-- Successional Group species relationship table
CREATE TABLE public.species_successional_groups (
    species_id BIGINT REFERENCES public.species ON DELETE CASCADE,
    successional_group_id INTEGER REFERENCES public.successional_groups,

    PRIMARY KEY (species_id, successional_group_id)
);

CREATE INDEX ON public.species_successional_groups(species_id);

-- Plant Material Sourcing Method enum table
CREATE TABLE public.plant_material_sourcing_methods (
    id INTEGER NOT NULL PRIMARY KEY,
    name TEXT NOT NULL
);

-- Plant Material Sourcing Method species relationship table
CREATE TABLE public.species_plant_material_sourcing_methods (
    species_id BIGINT REFERENCES public.species ON DELETE CASCADE,
    plant_material_sourcing_method_id INTEGER REFERENCES public.plant_material_sourcing_methods,

    PRIMARY KEY (species_id, plant_material_sourcing_method_id)
);

CREATE INDEX ON public.species_plant_material_sourcing_methods (species_id);
