CREATE TABLE agent_runtime_lease (
    owner_id VARCHAR(36) PRIMARY KEY,
    expires_at DATETIME NOT NULL
);
