ALTER TABLE agent_request ADD COLUMN steering_open BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent_request ADD COLUMN steering_json TEXT NULL;
ALTER TABLE agent_request ADD COLUMN steering_consumed INT NOT NULL DEFAULT 0;
