ALTER TABLE agent_conversation ADD COLUMN context_summary_json MEDIUMTEXT;
ALTER TABLE agent_conversation ADD COLUMN covered_through_message_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_conversation ADD COLUMN context_summary_revision BIGINT NOT NULL DEFAULT 0;
