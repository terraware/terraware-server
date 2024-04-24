INSERT INTO public.species_growth_forms (species_id, growth_form_id)
SELECT id, growth_form_id
FROM public.species
WHERE growth_form_id IS NOT null
ON CONFLICT DO NOTHING;

ALTER TABLE public.species DROP COLUMN growth_form_id;
