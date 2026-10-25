package com.pams.module.agent.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

class WorkItemMigrationTest {
    @Test void migrationPreservesLegacyRowsAndEnforcesUniqueNodeKeys() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:s2_migration;MODE=MySQL","sa","");
            var sql=connection.createStatement()) {
            for(String table:new String[]{"agent_run","agent_task","agent_step","agent_message"}) {
                sql.execute("create table "+table+" (id bigint primary key)");
                sql.execute("insert into "+table+"(id) values(1)");
            }
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V43__agent_work_items.sql"));
            try(var row=sql.executeQuery("select tool_mode,dispatch_token from agent_run where id=1")) {
                assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("standard");assertThat(row.getString(2)).isNull();
            }
            try(var row=sql.executeQuery("select row_version,work_item_id from agent_task where id=1")) {
                assertThat(row.next()).isTrue();assertThat(row.getLong(1)).isZero();assertThat(row.getObject(2)).isNull();
            }
            String insert="insert into agent_work_item(run_id,node_key,agent_name,instruction,dependencies_json,status,updated_at) values(1,1,'generalist','task','[]','QUEUED',current_timestamp)";
            sql.execute(insert);
            assertThatThrownBy(()->sql.execute(insert)).isInstanceOf(java.sql.SQLException.class)
                    .satisfies(e->assertThat(((java.sql.SQLException)e).getSQLState()).isEqualTo("23505"));
        }
    }
    @Test void runtimeMigrationPreservesRowsAndEnforcesDurableEventSequence() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:s3_migration;MODE=MySQL","sa","");var sql=connection.createStatement()) {
            for(String table:new String[]{"agent_run","agent_task","agent_step","agent_message","kb_retrieval_log"}) {
                sql.execute("create table "+table+" (id bigint primary key)");sql.execute("insert into "+table+"(id) values(1)");
            }
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V43__agent_work_items.sql"));
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V44__agent_reliable_runtime.sql"));
            try(var row=sql.executeQuery("select event_seq,cancel_requested from agent_run where id=1")) {
                assertThat(row.next()).isTrue();assertThat(row.getLong(1)).isZero();assertThat(row.getBoolean(2)).isFalse();
            }
            String insert="insert into agent_run_event(run_id,org_id,user_id,event_seq,status,created_at) values(1,1,1,1,'RUNNING',current_timestamp)";
            sql.execute(insert);assertThatThrownBy(()->sql.execute(insert)).isInstanceOf(java.sql.SQLException.class);
        }
    }
}
