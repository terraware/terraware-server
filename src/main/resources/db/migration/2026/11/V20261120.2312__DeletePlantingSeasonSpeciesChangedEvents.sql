DELETE FROM rate_limited_events
WHERE event_class = 'com.terraformation.backend.plantingmanagement.event.RateLimitedPlantingSeasonSpeciesChangedEvent';
