SET LOCAL search_path TO pams;

DO $$
BEGIN
    IF to_regprocedure('pams.show_trgm(text)') IS NULL THEN
        RAISE EXCEPTION 'Install pg_trgm in schema pams before migrating';
    END IF;
    IF cardinality(pams.show_trgm('组织排班')) = 0 THEN
        RAISE EXCEPTION 'pg_trgm requires a database LC_CTYPE that recognizes Chinese characters';
    END IF;
END
$$;

CREATE INDEX idx_kb_chunk_title_trgm
    ON kb_chunk USING gin (title gin_trgm_ops) WHERE deleted = 0;
CREATE INDEX idx_kb_chunk_content_trgm
    ON kb_chunk USING gin (content gin_trgm_ops) WHERE deleted = 0 AND tier = 'T1';
CREATE INDEX idx_kb_chunk_search_scope
    ON kb_chunk (org_id, tier, id) WHERE deleted = 0;
