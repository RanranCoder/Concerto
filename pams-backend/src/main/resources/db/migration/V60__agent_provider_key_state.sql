-- Only a fingerprint is stored here. The actual automatically generated key stays outside the database.
CREATE TABLE agent_provider_key_state (
 id INTEGER PRIMARY KEY,
 fingerprint VARCHAR(64) NOT NULL
);
