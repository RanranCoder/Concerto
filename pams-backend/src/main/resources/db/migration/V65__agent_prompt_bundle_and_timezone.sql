ALTER TABLE agent_run ADD COLUMN prompt_bundle_json MEDIUMTEXT NULL;
ALTER TABLE agent_run ADD COLUMN user_timezone VARCHAR(80) NULL;
