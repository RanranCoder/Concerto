package com.pams.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminWorkspaceTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    final ObjectMapper json = new ObjectMapper();
    String token;
    long orgId, ownerId, roleId;
    final String password = "Admin-review-2026";
    static final java.util.concurrent.atomic.AtomicInteger source = new java.util.concurrent.atomic.AtomicInteger();

    String register() throws Exception {
        String username = "review_" + UUID.randomUUID().toString().replace("-", "");
        var body = Map.of("org", Map.of("name", "页面验收-" + username, "type", "PARTY"),
                "admin", Map.of("username", username, "password", password, "realName", "验收管理员"),
                "units", List.of(Map.of("name", "组织部", "children", List.of())), "enabledPacks", List.of("party"));
        mvc.perform(post("/api/orgs/register").with(request -> {request.setRemoteAddr("192.0.2." + source.incrementAndGet()); return request;})
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        var login = json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        return login.path("token").asText();
    }

    JsonNode request(String method, String path, Object body) throws Exception {
        var builder = method.equals("GET") ? get(path) : post(path);
        builder.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
        if (body != null) builder.content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(builder).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString()).path("data");
    }

    @BeforeEach void setup() throws Exception {
        token = register();
        var me = request("GET", "/api/auth/me", null);
        orgId = me.path("orgId").asLong(); ownerId = me.path("id").asLong();
        var roles = request("GET", "/api/roles/assignable", null);
        roleId = java.util.stream.StreamSupport.stream(roles.spliterator(), false)
                .filter(r -> r.path("code").asText().equals("STAFF")).findFirst().orElse(roles.get(roles.size() - 1)).path("id").asLong();
    }

    long user(String name, int status) throws Exception {
        return request("POST", "/api/users", Map.of("username", "person_" + UUID.randomUUID().toString().replace("-", ""),
                "realName", name, "studentNo", "only-student-" + name, "password", password, "roleId", roleId, "status", status)).asLong();
    }

    @Test void accountFiltersAndSummaryUseWholeOrganizationBeforePagination() throws Exception {
        long enabled = user("唯一学号", 1); user("停用账号", 0);
        var summary = request("GET", "/api/users/summary", null);
        assertThat(summary.path("total").asInt()).isEqualTo(3);
        assertThat(summary.path("enabled").asInt()).isEqualTo(2);
        assertThat(summary.path("disabled").asInt()).isEqualTo(1);
        assertThat(summary.path("ownerCount").asInt()).isEqualTo(1);
        var filtered = request("GET", "/api/users?accountStatus=ENABLED&managementSource=NONE&size=1&keyword=only-student", null);
        assertThat(filtered.path("total").asInt()).isEqualTo(1);
        assertThat(filtered.path("records").get(0).path("id").asLong()).isEqualTo(enabled);
        assertThat(filtered.path("records").get(0).path("managementSource").asText()).isEqualTo("NONE");
        var owners = request("GET", "/api/users?managementSource=OWNER", null);
        assertThat(owners.path("records").get(0).path("id").asLong()).isEqualTo(ownerId);
    }

    @Test void statusBatchReturnsPartialResultsAndRejectsForeignProtectedAndPendingTargets() throws Exception {
        long ordinary = user("普通账号", 1), pending = user("等待设密", 0);
        String hash = db.queryForObject("SELECT password FROM sys_user WHERE id=?", String.class, pending);
        db.update("INSERT INTO member_account_credential(user_id,batch_id,org_id,pending_password_hash) VALUES(?,?,?,?)", pending, "review-fixture", orgId, hash);
        String originalToken = token;
        token = register();
        long foreign = request("GET", "/api/auth/me", null).path("id").asLong();
        token = originalToken;
        var result = request("POST", "/api/users/status-changes", Map.of("userIds", List.of(ordinary, ownerId, foreign, pending), "status", 0));
        assertThat(result.get(0).path("status").asText()).isEqualTo("UPDATED");
        assertThat(result.get(1).path("status").asText()).isEqualTo("FAILED");
        assertThat(result.get(2).path("status").asText()).isEqualTo("FAILED");
        assertThat(result.get(3).path("status").asText()).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT status FROM sys_user WHERE id=?", Integer.class, ordinary)).isZero();
        assertThat(db.queryForObject("SELECT real_name FROM sys_user WHERE id=?", String.class, ordinary)).isEqualTo("普通账号");
        assertThat(db.queryForObject("SELECT status FROM sys_user WHERE id=?", Integer.class, foreign)).isEqualTo(1);
        var activation = request("POST", "/api/users/status-changes", Map.of("userIds", List.of(pending), "status", 1));
        assertThat(activation.get(0).path("status").asText()).isEqualTo("FAILED");
        assertThat(request("GET", "/api/users?accountStatus=PENDING", null).path("total").asInt()).isEqualTo(1);
    }

    @Test void knowledgeEntriesAggregateAllChunksAndRemainTenantScoped() throws Exception {
        long document = request("POST", "/api/kb/documents", Map.of("title", "验收制度", "body", "第一章 资料归档\n" + "材料应按要求整理并核对负责人。".repeat(220) + "\n第二章 复核要求\n独特检索词验收标记", "visibility", "ALL")).asLong();
        var chunks = request("GET", "/api/kb/chunks/manual/" + document, null);
        assertThat(chunks.size()).isGreaterThan(1);
        var entries = request("GET", "/api/kb/entries?sourceType=manual&keyword=独特检索词&size=1", null);
        assertThat(entries.path("total").asInt()).isEqualTo(1);
        assertThat(entries.path("records").get(0).path("chunkCount").asInt()).isEqualTo(chunks.size());
        assertThat(entries.path("records").get(0).has("content")).isFalse();
        assertThat(request("GET", "/api/kb/entries?sourceType=manual&tier=T2", null).path("total").asInt()).isZero();
        assertThat(request("GET", "/api/kb/stats", null).path("entryCount").asInt()).isEqualTo(1);
        token = register();
        assertThat(request("GET", "/api/kb/entries?keyword=独特检索词", null).path("total").asInt()).isZero();
        mvc.perform(get("/api/kb/chunks/manual/" + document).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }

    @Test void delegatedManagerCannotReactivateAnotherDisabledAdministrator() throws Exception {
        long actor = user("账号经办人", 1), target = user("停用管理员", 0);
        for (long id : new long[]{actor, target}) db.update("INSERT INTO org_role_delegation(org_id,user_id,capabilities_json,grantable_json,status,version) VALUES(?,?,?,?,'ACTIVE',1)",
                orgId, id, "[\"user:view\",\"user:manage\"]", "[]");
        String username = db.queryForObject("SELECT username FROM sys_user WHERE id=?", String.class, actor);
        token = json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("token").asText();
        var result = request("POST", "/api/users/status-changes", Map.of("userIds", List.of(target), "status", 1));
        assertThat(result.get(0).path("status").asText()).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT status FROM sys_user WHERE id=?", Integer.class, target)).isZero();
    }
}
