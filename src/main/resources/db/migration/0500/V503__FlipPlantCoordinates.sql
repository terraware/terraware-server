UPDATE tracking.recorded_plants
SET gps_coordinates = ST_FlipCoordinates(gps_coordinates);
