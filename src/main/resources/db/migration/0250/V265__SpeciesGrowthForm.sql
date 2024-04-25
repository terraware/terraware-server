CREATE TABLE public.species_growth_forms (
    species_id BIGINT REFERENCES public.species ON DELETE CASCADE,
    growth_form_id INTEGER REFERENCES public.growth_forms,

    PRIMARY KEY (species_id, growth_form_id)
);

CREATE INDEX ON public.species_growth_forms (species_id);
