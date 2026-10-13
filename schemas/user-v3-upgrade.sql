PRAGMA user_version=3;
CREATE TABLE legacy_record_history(
 source_identity TEXT NOT NULL,record_type TEXT NOT NULL,source_record_id TEXT NOT NULL,
 record_sha256 TEXT NOT NULL,payload_json TEXT NOT NULL,disposition TEXT NOT NULL,
 reason TEXT,batch_id INTEGER NOT NULL REFERENCES migration_batch(id),
 PRIMARY KEY(source_identity,record_type,source_record_id,record_sha256)
);
CREATE TABLE migration_snapshot(
 source_identity TEXT NOT NULL,logical_sha256 TEXT NOT NULL,batch_id INTEGER NOT NULL REFERENCES migration_batch(id),
 PRIMARY KEY(source_identity,logical_sha256)
);
CREATE TABLE plan_refresh_request(
 target_language TEXT PRIMARY KEY REFERENCES target_language_registry(language_id),requested_at INTEGER NOT NULL
);
