package com.pams.security;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.pams.module.permission.service.TenantAuthorizationService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.transaction.annotation.Transactional
class TenantRoleManagementTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired TenantAuthorizationService auth;
  final ObjectMapper json = new ObjectMapper();
  String token;

  @BeforeEach
  void login() throws Exception {
    token = loginAs("teacher");
  }

  String loginAs(String username) throws Exception {
    return json.readTree(
            mvc.perform(
                    post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsString(
                                Map.of("username", username, "password", "123456"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("data")
        .path("token")
        .asText();
  }

  JsonNode call(String method, String path, Object body, int expected) throws Exception {
    var request =
        switch (method) {
          case "PUT" -> put(path);
          case "GET" -> get(path);
          default -> post(path);
        };
    request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    if (body != null) request.content(json.writeValueAsString(body));
    return json.readTree(
            mvc.perform(request)
                .andExpect(status().is(expected))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("data");
  }

  long create() throws Exception {
    return call("POST", "/api/roles", Map.of("name", "自定义角色-" + UUID.randomUUID()), 200).asLong();
  }

  void draft(long role, String... codes) throws Exception {
    var detail = call("GET", "/api/roles/" + role, null, 200);
    JsonNode base =
        detail.path("draft").isNull()
            ? detail.path("published")
            : json.readTree(detail.path("draft").path("grants").asText());
    List<Map<String, Object>> grants = new ArrayList<>();
    for (var g : base)
      if (!Arrays.asList(codes).contains(g.path("permission").asText()))
        grants.add(Map.of("operation", "REVOKE", "permission", g.path("permission").asText()));
    for (String c : codes) grants.add(Map.of("operation", "SET", "permission", c, "scope", "ORG"));
    call(
        "PUT",
        "/api/roles/" + role + "/draft",
        Map.of("draftVersion", 0, "permissionChanges", grants),
        200);
  }

  String publishPreview(long role) throws Exception {
    return call(
            "POST",
            "/api/roles/" + role + "/change-previews",
            Map.of("action", "PUBLISH", "version", 0),
            200)
        .path("id")
        .asText();
  }

  void apply(String id, int expected) throws Exception {
    call("POST", "/api/orgs/current/authorization-changes/" + id + "/apply", Map.of(), expected);
  }

  long fixture(String username, long org) {
    db.update(
        "INSERT INTO"
            + " sys_user(username,password,real_name,role_id,org_id,status,deleted,created_at,updated_at)"
            + " SELECT ?,password,?,role_id,?,1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM sys_user"
            + " WHERE username='staff'",
        username,
        username,
        org);
    return db.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, username);
  }

  @Test
  void draftPublishAssignAndRevokeAreLiveAndIdempotent() throws Exception {
    long role = create();
    draft(role, "activity:view");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM role_grant WHERE role_id=?", Integer.class, role))
        .isZero();
    String preview = publishPreview(role);
    apply(preview, 200);
    apply(preview, 200);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM role_revision WHERE role_id=? AND state='PUBLISHED'",
                Integer.class,
                role))
        .isEqualTo(1);
    String name = "role-user-" + UUID.randomUUID();
    long uid = fixture(name, 1);
    var assignment =
        call("POST", "/api/users/role-change-previews", Map.of("userId", uid, "roleId", role), 200);
    apply(assignment.path("id").asText(), 200);
    token = loginAs(name);
    var me = call("GET", "/api/auth/me", null, 200);
    assertThat(me.path("businessPermissions").toString()).contains("activity:view");
    assertThat(me.path("managementCapabilities").size()).isZero();
    call("GET", "/api/roles", null, 403);
    call("GET", "/api/activities", null, 200);
    call("GET", "/api/members", null, 403);
    call("GET", "/api/chat/categories", null, 403);
    call("GET", "/api/seat-layouts/templates", null, 403);
    var dashboard = call("GET", "/api/dashboard", null, 200);
    assertThat(dashboard.path("recentMaterials").size()).isZero();
    assertThat(dashboard.path("recentArticles").size()).isZero();
    token = loginAs("teacher");
    draft(role);
    var revoke =
        call(
            "POST",
            "/api/roles/" + role + "/change-previews",
            Map.of("action", "PUBLISH", "version", 1),
            200);
    apply(revoke.path("id").asText(), 200);
    token = loginAs(name);
    call("GET", "/api/activities", null, 403);
  }

  @Test
  void governanceCannotBePublishedAsBusiness() throws Exception {
    long role = create();
    call(
        "PUT",
        "/api/roles/" + role + "/draft",
        Map.of(
            "draftVersion",
            0,
            "permissionChanges",
            List.of(Map.of("operation", "SET", "permission", "role:grant", "scope", "ORG"))),
        403);
  }

  @Test
  void missingDependenciesAndUnsupportedScopeAreRejected() throws Exception {
    long role = create();
    draft(role, "activity:edit");
    call(
        "POST",
        "/api/roles/" + role + "/change-previews",
        Map.of("action", "PUBLISH", "version", 0),
        400);
  }

  @Test
  void stalePreviewDoesNotWrite() throws Exception {
    long role = create();
    draft(role, "activity:view");
    String preview = publishPreview(role);
    create();
    apply(preview, 409);
    assertThat(
            db.queryForObject(
                "SELECT current_revision_id FROM sys_role WHERE id=?", Long.class, role))
        .isNull();
  }

  @Test
  void tenantIdsCannotBeEnumerated() throws Exception {
    db.update(
        "INSERT INTO organization(name,type,status,deleted) VALUES(?,'OTHER',1,0)",
        "另一个组织-" + UUID.randomUUID());
    long org = db.queryForObject("SELECT MAX(id) FROM organization", Long.class);
    auth.initializeOrg(org);
    long other = db.queryForObject("SELECT MIN(id) FROM sys_role WHERE org_id=?", Long.class, org);
    call("GET", "/api/roles/" + other, null, 404);
  }

  @Test
  void oldGlobalWritesAreClosed() throws Exception {
    call("PUT", "/api/permissions/roles/STAFF", List.of("user:permission"), 409);
  }

  @Test
  void disableRequiresResolvingReferences() throws Exception {
    long role = create();
    draft(role, "activity:view");
    apply(publishPreview(role), 200);
    String name = "ref-user-" + UUID.randomUUID();
    long uid = fixture(name, 1);
    db.update("UPDATE sys_user SET role_id=? WHERE id=?", role, uid);
    call(
        "POST",
        "/api/roles/" + role + "/change-previews",
        Map.of("action", "DISABLE", "version", 1),
        409);
  }

  @Test
  void expiredDelegationCannotManageRoles() throws Exception {
    String name = "expired-admin-" + UUID.randomUUID();
    long uid = fixture(name, 1);
    db.update(
        "INSERT INTO"
            + " org_role_delegation(org_id,user_id,capabilities_json,grantable_json,expires_at,status)"
            + " VALUES(1,?,'[\"role:view\"]','[]',?,'ACTIVE')",
        uid,
        java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusMinutes(1)));
    token = loginAs(name);
    call("GET", "/api/roles", null, 403);
  }

  @Test
  void delegatedGrantabilityIsIndependentAndRevocationIsImmediate() throws Exception {
    String name = "delegate-" + UUID.randomUUID();
    long uid = fixture(name, 1);
    call(
        "PUT",
        "/api/orgs/current/role-delegations/" + uid,
        Map.of("active", true, "grantable", List.of("member:view"), "version", 0),
        200);
    assertThat(
            call("GET", "/api/orgs/current/role-delegations/" + uid, null, 200)
                .path("version")
                .asInt())
        .isEqualTo(1);
    call(
        "PUT",
        "/api/orgs/current/role-delegations/" + uid,
        Map.of("active", true, "grantable", List.of(), "version", 0),
        409);
    String delegateToken = loginAs(name);
    token = delegateToken;
    assertThat(auth.business(uid)).doesNotContain("member:view");
    long role = create();
    draft(role, "member:view");
    apply(publishPreview(role), 200);
    assertThat(auth.business(uid)).doesNotContain("member:view");
    long forbidden = create();
    call(
        "PUT",
        "/api/roles/" + forbidden + "/draft",
        Map.of(
            "draftVersion",
            0,
            "permissionChanges",
            List.of(Map.of("operation", "SET", "permission", "activity:view", "scope", "ORG"))),
        403);
    long owner = db.queryForObject("SELECT owner_user_id FROM organization WHERE id=1", Long.class);
    call("POST", "/api/users/role-change-previews", Map.of("userId", owner, "roleId", role), 403);
    token = loginAs("teacher");
    call(
        "PUT",
        "/api/orgs/current/role-delegations/" + uid,
        Map.of("active", false, "grantable", List.of(), "version", 1),
        200);
    token = delegateToken;
    call("GET", "/api/roles", null, 403);
  }

  @Test
  void reenablingPackDoesNotRestoreOldGrants() throws Exception {
    long role = create();
    draft(role, "party:view");
    apply(publishPreview(role), 200);
    assertThat(auth.rolePermissions(1, role)).contains("party:view");
    auth.updatePacks(1, false);
    assertThat(auth.rolePermissions(1, role)).doesNotContain("party:view");
    auth.updatePacks(1, true);
    assertThat(auth.rolePermissions(1, role)).doesNotContain("party:view");
    draft(role, "party:view"); // Explicit confirmation drops the obsolete activation version.
    var preview =
        call(
            "POST",
            "/api/roles/" + role + "/change-previews",
            Map.of("action", "PUBLISH", "version", 1),
            200);
    apply(preview.path("id").asText(), 200);
    assertThat(auth.rolePermissions(1, role)).contains("party:view");
  }

  @Test
  void unsupportedScopeAndConcurrentDraftAreRejected() throws Exception {
    long role = create();
    call(
        "PUT",
        "/api/roles/" + role + "/draft",
        Map.of(
            "draftVersion",
            0,
            "permissionChanges",
            List.of(Map.of("operation", "SET", "permission", "activity:view", "scope", "SELF"))),
        400);
    draft(role, "activity:view");
    call(
        "PUT",
        "/api/roles/" + role + "/draft",
        Map.of("draftVersion", 0, "permissionChanges", List.of()),
        409);
  }

  @Test
  void knowledgeListsDetailsAndCountsUseSourcePermissions() throws Exception {
    token = loginAs("staff");
    long baseline = call("GET", "/api/kb/stats", null, 200).path("chunkCount").asLong();
    String marker = "kb-isolation-" + UUID.randomUUID();
    for (String visibility : List.of("ALL", "LEADER")) {
      db.update(
          "INSERT INTO kb_document(title,body,visibility,org_id) VALUES(?,?,?,1)",
          marker + visibility,
          marker + "正文",
          visibility);
      long id =
          db.queryForObject(
              "SELECT id FROM kb_document WHERE title=?", Long.class, marker + visibility);
      db.update(
          "INSERT INTO kb_chunk(source_type,source_id,source_key,seq,title,content,org_id)"
              + " VALUES('manual',?,?,0,?,?,1)",
          id,
          "manual:" + id,
          marker + visibility,
          marker + "正文");
      if (visibility.equals("LEADER")) {
        call("GET", "/api/kb/documents/" + id, null, 404);
        call("GET", "/api/kb/chunks/manual/" + id, null, 404);
      }
    }
    var documents = call("GET", "/api/kb/documents?keyword=" + marker, null, 200);
    assertThat(documents.path("total").asInt()).isEqualTo(1);
    assertThat(documents.toString()).doesNotContain(marker + "LEADER");
    var chunks = call("GET", "/api/kb/chunks?keyword=" + marker, null, 200);
    assertThat(chunks.path("total").asInt()).isEqualTo(1);
    assertThat(chunks.toString()).doesNotContain(marker + "LEADER");
    assertThat(call("GET", "/api/kb/stats", null, 200).path("chunkCount").asLong())
        .isEqualTo(baseline + 1);
  }

  @Test
  void retrievalHistoryDoesNotExposeOtherUsersQuestions() throws Exception {
    String name = "kb-user-" + UUID.randomUUID();
    long uid = fixture(name, 1);
    db.update("INSERT INTO kb_retrieval_log(user_id,query_text,org_id) VALUES(?,?,1)", uid, "我的问题");
    db.update(
        "INSERT INTO kb_retrieval_log(user_id,query_text,org_id) SELECT id,'其他人的私密问题',1 FROM"
            + " sys_user WHERE username='teacher'");
    token = loginAs(name);
    var logs = call("GET", "/api/kb/retrievals", null, 200);
    assertThat(logs.path("total").asInt()).isEqualTo(1);
    assertThat(logs.toString()).contains("我的问题").doesNotContain("其他人的私密问题");
    assertThat(call("GET", "/api/kb/stats", null, 200).path("retrievalTotal").asInt()).isEqualTo(1);
  }

  @Test
  void deltaPreservesReadonlyGrantsWhileAssignmentChecksTheWholeRole() throws Exception {
    long role = create();
    draft(role, "activity:view", "member:view");
    apply(publishPreview(role), 200);
    String name = "limited-" + UUID.randomUUID();
    long uid = fixture(name, 1);
    long target = fixture("target-" + UUID.randomUUID(), 1);
    call(
        "PUT",
        "/api/orgs/current/role-delegations/" + uid,
        Map.of("active", true, "grantable", List.of("member:view"), "version", 0),
        200);
    token = loginAs(name);
    call(
        "PUT",
        "/api/roles/" + role + "/draft",
        Map.of(
            "draftVersion",
            0,
            "permissionChanges",
            List.of(Map.of("operation", "REVOKE", "permission", "member:view"))),
        200);
    var preview =
        call(
            "POST",
            "/api/roles/" + role + "/change-previews",
            Map.of("action", "PUBLISH", "version", 1),
            200);
    apply(preview.path("id").asText(), 200);
    assertThat(auth.rolePermissions(1, role)).containsExactly("activity:view");
    call("POST", "/api/users/role-change-previews", Map.of("userId", target, "roleId", role), 403);
    call(
        "PUT",
        "/api/roles/" + role + "/draft",
        Map.of(
            "draftVersion",
            0,
            "permissionChanges",
            List.of(Map.of("operation", "REVOKE", "permission", "activity:view"))),
        403);
  }
}
