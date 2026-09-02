-- Long lived platform level properties, currently the anonymous OLake telemetry install id.
CREATE TABLE IF NOT EXISTS platform_property
(
    property_key   VARCHAR(128) PRIMARY KEY,
    property_value VARCHAR(512) NOT NULL,
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE platform_property IS 'Long lived platform level properties';
COMMENT ON COLUMN platform_property.property_key IS 'Property key';
COMMENT ON COLUMN platform_property.property_value IS 'Property value';
COMMENT ON COLUMN platform_property.create_time IS 'Property create timestamp';
