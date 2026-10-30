package com.pams.module.agent.service;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;
class ConversationMemoryMigrationTest {
    @Test void ordinaryConversationJsonDoesNotExposeUnverifiedCachedSummary() throws Exception {
        var conversation=new com.pams.module.agent.entity.AgentConversation();
        conversation.setContextSummaryJson("未复核的旧摘要");conversation.setCoveredThroughMessageId(10);conversation.setContextSummaryRevision(2);
        String encoded=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(conversation);
        assertThat(encoded).doesNotContain("未复核的旧摘要","contextSummaryJson","coveredThroughMessageId","contextSummaryRevision");
    }
    @Test void migrationKeepsExistingConversationsAndInitializesCheckpoint() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:context_summary_migration;MODE=MySQL","sa","");var sql=connection.createStatement()) {
            sql.execute("create table agent_conversation(id bigint primary key,title varchar(120))");
            sql.execute("insert into agent_conversation values(1,'已有会话')");
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V57__agent_context_summary.sql"));
            try(var row=sql.executeQuery("select title,context_summary_json,covered_through_message_id,context_summary_revision from agent_conversation where id=1")) {
                assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("已有会话");
                assertThat(row.getString(2)).isNull();assertThat(row.getLong(3)).isZero();assertThat(row.getLong(4)).isZero();
            }
        }
    }
}
