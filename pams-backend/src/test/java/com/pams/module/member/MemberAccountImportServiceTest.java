package com.pams.module.member;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MemberAccountImportServiceTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder encoder;
    final ObjectMapper json = new ObjectMapper();
    String token;
    long session, member, second, role;

    @BeforeEach
    void fixture() throws Exception {
        token = login("teacher", "123456");
        role = db.queryForObject("SELECT id FROM sys_role WHERE org_id=1 AND code='STAFF'", Long.class);
        db.update("INSERT INTO member_session(name,org_id,is_current,deleted) VALUES('开户测试',1,1,0)");
        session = db.queryForObject("SELECT id FROM member_session WHERE name='开户测试'", Long.class);
        member = member("batch_student_1", 1, session);
        second = member("batch_student_2", 1, session);
    }

    long member(String username, long org, long sid) {
        db.update("INSERT INTO member(session_id,org_id,position,name,student_no,status,deleted)"
                + " VALUES(?,?,'STAFF',?,?,'ACTIVE',0)", sid, org, username, username);
        return db.queryForObject("SELECT id FROM member WHERE student_no=?", Long.class, username);
    }

    String login(String username, String password) throws Exception {
        em.flush(); em.clear();
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("token").asText();
    }

    JsonNode call(String method, String path, Object body, int expected) throws Exception {
        em.flush(); em.clear();
        var request = switch (method) {
            case "GET" -> get(path);
            case "PUT" -> put(path);
            case "DELETE" -> delete(path);
            default -> post(path);
        };
        request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
        if (body != null) request.content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request).andExpect(status().is(expected))
            .andReturn().getResponse().getContentAsString()).path("data");
    }

    Map<String, Object> request(List<Long> ids) {
        Map<Long, Long> roleIds = new HashMap<>();
        ids.forEach(id -> roleIds.put(id, role));
        return Map.of("sessionId", session, "memberIds", ids, "roleIds", roleIds);
    }

    String preview() throws Exception {
        return call("POST", "/api/members/account-batches/previews", request(List.of(member, second)), 200).path("id").asText();
    }

    String createBatch() throws Exception {
        String id = preview();
        call("POST", "/api/members/account-batches/" + id + "/apply", Map.of(), 200);
        return id;
    }

    void setup(String setupToken, String password, int expected) throws Exception {
        em.flush(); em.clear();
        mvc.perform(post("/api/auth/account-setup").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("token", setupToken, "password", password))))
            .andExpect(status().is(expected));
    }

    @Test
    void previewThenCreateIsIdempotentAndDoesNotGrantGovernance() throws Exception {
        var p = call("POST", "/api/members/account-batches/previews", request(List.of(member, second)), 200);
        assertThat(p.path("rows").size()).isEqualTo(2);
        assertThat(p.path("rows").get(0).path("roleName").asText()).isNotBlank();
        assertThat(p.path("rows").get(0).path("permissions").toString()).doesNotContain("role:assign", "user:manage");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username LIKE 'batch_student_%'", Integer.class)).isZero();
        String id = p.path("id").asText();
        var first = call("POST", "/api/members/account-batches/" + id + "/apply", Map.of(), 200);
        var retry = call("POST", "/api/members/account-batches/" + id + "/apply", Map.of(), 200);
        assertThat(first).isEqualTo(retry);
        assertThat(first.path("created").asInt()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE entity_type='ACCOUNT_IMPORT' AND content LIKE ?",Integer.class,"%"+id+"%")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT content FROM notification WHERE entity_type='ACCOUNT_IMPORT' AND content LIKE ?",String.class,"%"+id+"%")).contains("2 个待激活账号").doesNotContain("batch_student_1","batch_student_2","password","token");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username LIKE 'batch_student_%' AND org_id=1 AND status=0 AND role_id=?", Integer.class, role)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM org_role_delegation WHERE user_id IN (SELECT user_id FROM member_account_credential WHERE batch_id=?)", Integer.class, id)).isZero();
        assertThat(call("GET", "/api/members/account-batches", null, 200).size()).isEqualTo(1);
    }

    @Test
    void credentialsAreUniqueHashedSingleUseAndAllowLoginOnlyAfterSetup() throws Exception {
        String id = createBatch();
        em.flush(); em.clear();
        var response = mvc.perform(post("/api/members/account-batches/" + id + "/credentials")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse();
        var credentials = json.readTree(response.getContentAsString()).path("data");
        String a = credentials.get(0).path("token").asText(), b = credentials.get(1).path("token").asText();
        assertThat(a).hasSize(43).isNotEqualTo(b);
        assertThat(db.queryForList("SELECT token_hash FROM member_account_credential", String.class))
            .allSatisfy(h -> assertThat(h).hasSize(64).isNotEqualTo(a).isNotEqualTo(b));
        assertThat(db.queryForObject("SELECT preview_json FROM member_account_batch WHERE id=?", String.class, id)).doesNotContain(a, b);
        assertThat(db.queryForList("SELECT detail_json FROM org_audit_log", String.class)).allSatisfy(d -> assertThat(d).doesNotContain(a, b));
        setup(a, "short", 400);
        setup(a, "MyNewPassword_2026", 200);
        setup(a, "ReplacementPassword", 400);
        String username = credentials.get(0).path("username").asText();
        String hash = db.queryForObject("SELECT password FROM sys_user WHERE username=?", String.class, username);
        assertThat(encoder.matches("MyNewPassword_2026", hash)).isTrue();
        assertThat(login(username, "MyNewPassword_2026")).isNotBlank();
        var remaining = call("POST", "/api/members/account-batches/" + id + "/credentials", Map.of(), 200);
        assertThat(remaining.size()).isEqualTo(1);
        setup(b, "AnotherPassword_2026", 400); // Reissue invalidates the unused old link.
        setup(remaining.get(0).path("token").asText(), "AnotherPassword_2026", 200);
    }

    @Test
    void rejectsExpiredPreviewsAndChangedAuthorization() throws Exception {
        String id = preview();
        db.update("UPDATE member_account_batch SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE id=?", id);
        call("POST", "/api/members/account-batches/" + id + "/apply", Map.of(), 409);
        String fresh = preview();
        db.update("UPDATE organization SET authz_version=authz_version+1 WHERE id=1");
        call("POST", "/api/members/account-batches/" + fresh + "/apply", Map.of(), 409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM member_account_credential", Integer.class)).isZero();
    }

    @Test
    void rejectsRosterChangeAndAccountConflictsWithoutPartialCreation() throws Exception {
        String id = preview();
        db.update("UPDATE member SET name='更改姓名' WHERE id=?", member);
        call("POST", "/api/members/account-batches/" + id + "/apply", Map.of(), 409);
        String fresh = preview();
        db.update("INSERT INTO sys_user(username,password,real_name,role_id,org_id,status,deleted) VALUES('batch_student_2','hash','已有账号',?,1,1,0)", role);
        call("POST", "/api/members/account-batches/" + fresh + "/apply", Map.of(), 409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username='batch_student_1'", Integer.class)).isZero();
    }

    @Test
    void rejectsForeignRoleMemberSessionAndUnpublishedRoles() throws Exception {
        long foreignRole = 99001;
        db.update("INSERT INTO organization(id,name,status,deleted) VALUES(99001,'其他组织',1,0)");
        db.update("INSERT INTO sys_role(id,org_id,code,name,level,data_scope,status) VALUES(?,99001,'STAFF','其他组织干事',1,'ORG','ACTIVE')", foreignRole);
        call("POST", "/api/members/account-batches/previews", Map.of("sessionId", session, "memberIds", List.of(member), "roleIds", Map.of(member, foreignRole)), 404);
        db.update("INSERT INTO member_session(id,name,org_id,deleted) VALUES(99001,'其他届',99001,0)");
        long other = member("other_org_student", 99001, 99001);
        call("POST", "/api/members/account-batches/previews", request(List.of(other)), 404);
        call("POST", "/api/members/account-batches/previews", Map.of("sessionId", 99001, "memberIds", List.of(member), "roleIds", Map.of(member, role)), 404);
        db.update("UPDATE sys_role SET status='DRAFT' WHERE id=?", role);
        call("POST", "/api/members/account-batches/previews", request(List.of(member)), 403);
    }

    @Test
    void rejectsDuplicateMissingRoleInactiveMemberAndReservedUsername() throws Exception {
        call("POST", "/api/members/account-batches/previews", request(List.of(member, member)), 400);
        call("POST", "/api/members/account-batches/previews", Map.of("sessionId", session, "memberIds", List.of(member)), 400);
        db.update("UPDATE member SET status='RESIGNED' WHERE id=?", member);
        call("POST", "/api/members/account-batches/previews", request(List.of(member)), 409);
        db.update("INSERT INTO sys_user(username,password,real_name,role_id,org_id,status,deleted) VALUES('batch_student_2','hash','已删除',?,1,0,999)", role);
        call("POST", "/api/members/account-batches/previews", request(List.of(second)), 409);
        assertThat(call("GET", "/api/members/unregistered?sessionId=" + session, null, 200).size()).isZero();
    }

    @Test
    void anotherAdministratorCannotReadApplyOrIssueThisBatch() throws Exception {
        String id = preview();
        token = login("zhuren", "123456");
        call("POST", "/api/members/account-batches/" + id + "/apply", Map.of(), 404);
        call("POST", "/api/members/account-batches/" + id + "/credentials", Map.of(), 404);
        assertThat(call("GET", "/api/members/account-batches", null, 200).size()).isZero();
    }

    @Test
    void businessRoleCannotImportAndRevokedGovernanceInvalidatesBatch() throws Exception {
        String id = preview();
        token = login("staff", "123456");
        call("POST", "/api/members/account-batches/previews", request(List.of(member)), 403);
        token = login("zhuren", "123456");
        String directorBatch = preview();
        db.update("UPDATE org_role_delegation SET status='REVOKED' WHERE user_id=(SELECT id FROM sys_user WHERE username='zhuren')");
        call("POST", "/api/members/account-batches/" + directorBatch + "/apply", Map.of(), 403);
        call("POST", "/api/members/account-batches/" + id + "/credentials", Map.of(), 403);
    }

    @Test
    void expiredCredentialsAndDisabledOrganizationCannotActivate() throws Exception {
        String id = createBatch();
        var c = call("POST", "/api/members/account-batches/" + id + "/credentials", Map.of(), 200);
        String a = c.get(0).path("token").asText();
        db.update("UPDATE member_account_credential SET expires_at=TIMESTAMP '2000-01-01 00:00:00'");
        setup(a, "NewPassword_2026", 400);
        var reissued = call("POST", "/api/members/account-batches/" + id + "/credentials", Map.of(), 200);
        db.update("UPDATE organization SET status=0 WHERE id=1");
        setup(reissued.get(0).path("token").asText(), "NewPassword_2026", 400);
        setup("unrecognized", "NewPassword_2026", 400);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username LIKE 'batch_student_%' AND status=1", Integer.class)).isZero();
    }

    @Test
    void editingAnAccountRevokesItsOldLinkAndDeletionPreventsReissue() throws Exception {
        String id = createBatch();
        var links = call("POST", "/api/members/account-batches/" + id + "/credentials", Map.of(), 200);
        long uid = db.queryForObject("SELECT id FROM sys_user WHERE username='batch_student_1'", Long.class);
        call("PUT", "/api/users/" + uid, Map.of("username", "batch_student_1", "realName", "已核对姓名",
            "studentNo", "batch_student_1", "roleId", role, "status", 0), 200);
        setup(links.get(0).path("token").asText(), "NewPassword_2026", 400);
        long secondUid = db.queryForObject("SELECT id FROM sys_user WHERE username='batch_student_2'", Long.class);
        call("DELETE", "/api/users/" + secondUid, null, 200);
        setup(links.get(1).path("token").asText(), "NewPassword_2026", 400);
        var reissued = call("POST", "/api/members/account-batches/" + id + "/credentials", Map.of(), 200);
        assertThat(reissued.size()).isEqualTo(1);
        assertThat(reissued.get(0).path("name").asText()).isEqualTo("已核对姓名");
        setup(reissued.get(0).path("token").asText(), "NewPassword_2026", 200);
    }

    @Test
    void currentGrantableBoundsAreRequiredForPreviewAndCredentialReissue() throws Exception {
        token = login("zhuren", "123456");
        String id = createBatch();
        db.update("UPDATE org_role_delegation SET grantable_json='[]' WHERE user_id=(SELECT id FROM sys_user WHERE username='zhuren')");
        call("POST", "/api/members/account-batches/previews", request(List.of(member)), 409); // Already created.
        call("POST", "/api/members/account-batches/" + id + "/credentials", Map.of(), 403);
        long third = member("batch_student_3", 1, session);
        call("POST", "/api/members/account-batches/previews", request(List.of(third)), 403);
    }

    @Test
    void noPreviewBypassAndBatchSizeIsBounded() throws Exception {
        call("POST", "/api/members/import-accounts", request(List.of(member)), 409);
        call("POST", "/api/members/account-batches/not-a-preview/apply", request(List.of(member)), 404);
        var tooMany = java.util.stream.LongStream.rangeClosed(1, 201).boxed().toList();
        call("POST", "/api/members/account-batches/previews", request(tooMany), 400);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM member_account_credential", Integer.class)).isZero();
    }

    @Test
    void recommendsDirectorDeputyDepartmentLeadersAndStaffFromAssignableTenantRoles() throws Exception {
        db.update("UPDATE member SET position='DIRECTOR' WHERE id=?", member);
        db.update("UPDATE member SET position='SUB_DIRECTOR' WHERE id=?", second);
        Map<String, String> expected = new HashMap<>();
        expected.put("batch_student_1", "DIRECTOR"); expected.put("batch_student_2", "DIRECTOR");
        String[][] departments = {{"组织部", "ORG_LEADER"}, {"文秘部", "SECRETARY_LEADER"},
            {"新媒体中心", "MEDIA_LEADER"}, {"青年科技部", "TECH_LEADER"}};
        for (int i = 0; i < departments.length; i++) {
            String username = "position_student_" + i;
            long id = member(username, 1, session);
            long department = db.queryForObject("SELECT id FROM sys_department WHERE org_id=1 AND name=?", Long.class, departments[i][0]);
            db.update("UPDATE member SET position=?,dept_id=? WHERE id=?", i % 2 == 0 ? "DEPT_HEAD" : "SUB_DEPT_HEAD", department, id);
            expected.put(username, departments[i][1]);
        }
        member("ordinary_staff", 1, session); expected.put("ordinary_staff", "STAFF");
        var rows = call("GET", "/api/members/unregistered?sessionId=" + session, null, 200);
        assertThat(rows.size()).isEqualTo(7);
        for (var row : rows) {
            long expectedRole = db.queryForObject("SELECT id FROM sys_role WHERE org_id=1 AND code=?", Long.class,
                expected.get(row.path("studentNo").asText()));
            assertThat(row.path("recommendedRoleId").asLong()).isEqualTo(expectedRole);
            assertThat(row.path("position").asText()).isNotBlank();
        }
    }

    @Test
    void recommendationDoesNotUseForeignDisabledOrUngrantableRolesAndDoesNotGuessUnknownDepartments() throws Exception {
        db.update("INSERT INTO organization(id,name,status,deleted) VALUES(99001,'其他组织',1,0)");
        db.update("INSERT INTO sys_role(org_id,code,name,level,data_scope,status) VALUES(99001,'STAFF','其他组织干事',1,'ORG','ACTIVE')");
        db.update("UPDATE sys_role SET status='DISABLED' WHERE org_id=1 AND code='STAFF'");
        db.update("UPDATE member SET position='DEPT_HEAD',dept_id=NULL WHERE id=?", second);
        var rows = call("GET", "/api/members/unregistered?sessionId=" + session, null, 200);
        for (var row : rows) {
            assertThat(row.path("recommendedRoleId").isNull()).isTrue();
            assertThat(row.path("recommendationReason").asText()).contains("手动选择");
        }
        db.update("UPDATE sys_role SET status='ACTIVE' WHERE org_id=1 AND code='STAFF'");
        token = login("zhuren", "123456");
        db.update("UPDATE org_role_delegation SET grantable_json='[]' WHERE user_id=(SELECT id FROM sys_user WHERE username='zhuren')");
        rows = call("GET", "/api/members/unregistered?sessionId=" + session, null, 200);
        for (var row : rows) assertThat(row.path("recommendedRoleId").isNull()).isTrue();
    }
}
