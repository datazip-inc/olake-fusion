CREATE TABLE IF NOT EXISTS table_configurations
(
    catalog_name             VARCHAR(64) NOT NULL,
    db_name                  VARCHAR(128) NOT NULL,
    table_name               VARCHAR(256) NOT NULL,
    self_optimizing_enabled  BOOLEAN NOT NULL DEFAULT FALSE,
    minor_trigger_cron       VARCHAR(128) DEFAULT NULL,
    major_trigger_cron       VARCHAR(128) DEFAULT NULL,
    full_trigger_cron        VARCHAR(128) DEFAULT NULL,
    target_size              BIGINT NOT NULL DEFAULT 536870912,
    olake_created            BOOLEAN DEFAULT NULL,
    health_score             INTEGER DEFAULT NULL,
    health_score_snapshot_id BIGINT DEFAULT NULL,
    create_time              TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (catalog_name, db_name, table_name)
);
