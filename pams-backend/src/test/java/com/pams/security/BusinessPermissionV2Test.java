package com.pams.security;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.time.*;
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
class BusinessPermissionV2Test {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired jakarta.persistence.EntityManager entities;
  final ObjectMapper json = new ObjectMapper();
  String token;

  @BeforeEach
  void setup() throws Exception {
    token = login("teacher");
  }

  String login(String name) throws Exception {
    return json.readTree(
            mvc.perform(
                    post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsString(
                                Map.of("username", name, "password", "123456"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("data")
        .path("token")
        .asText();
  }

  JsonNode call(String method, String path, Object data, int statusCode) throws Exception {
    var request =
        switch (method) {
          case "GET" -> get(path);
          case "PUT" -> put(path);
          default -> post(path);
        };
    request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    if (data != null) request.content(json.writeValueAsString(data));
    return json.readTree(
            mvc.perform(request)
                .andExpect(status().is(statusCode))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("data");
  }

  long role(String... permissions) throws Exception {
    long role = call("POST", "/api/roles", Map.of("name", "职责-" + UUID.randomUUID()), 200).asLong();
    call(
        "PUT",
        "/api/roles/" + role + "/draft",
        Map.of(
            "draftVersion",
            0,
            "permissionChanges",
            Arrays.stream(permissions)
                .map(p -> Map.of("operation", "SET", "permission", p, "scope", "ORG"))
                .toList()),
        200);
    String preview =
        call(
                "POST",
                "/api/roles/" + role + "/change-previews",
                Map.of("action", "PUBLISH", "version", 0),
                200)
            .path("id")
            .asText();
    call("POST", "/api/orgs/current/authorization-changes/" + preview + "/apply", null, 200);
    return role;
  }

  long user(long role) throws Exception {
    String name = "permissions-" + UUID.randomUUID();
    db.update(
        "INSERT INTO"
            + " sys_user(username,password,real_name,role_id,org_id,status,deleted,created_at,updated_at)"
            + " SELECT ?,password,'同名成员',?,1,1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM sys_user"
            + " WHERE username='staff'",
        name,
        role);
    long id = db.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, name);
    token = login(name);
    return id;
  }

  long activity() throws Exception {
    return call("POST", "/api/activities", Map.of("name", "权限隔离测试活动"), 200).asLong();
  }

  @Test
  void taskResponsibilityDoesNotEditActivitiesOrReadOtherSubmodules() throws Exception {
    long activity = activity();
    user(role("activity:view", "task:view", "task:manage"));
    call(
        "POST",
        "/api/tasks",
        Map.of(
            "activityId",
            activity,
            "name",
            "只维护任务",
            "startDate",
            LocalDate.now().toString(),
            "endDate",
            LocalDate.now().plusDays(1).toString()),
        200);
    call("PUT", "/api/activities/" + activity, Map.of("name", "不可修改"), 403);
    call("PUT", "/api/activities/" + activity + "/status", Map.of("status", "PLANNING"), 403);
    call("GET", "/api/plans?activityId=" + activity, null, 403);
    var detail = call("GET", "/api/activities/" + activity + "/detail", null, 200);
    assertThat(detail.path("tasks").size()).isEqualTo(1);
    assertThat(detail.path("agendas").size()).isZero();
    assertThat(detail.path("score").size()).isZero();
  }

  @Test
  void activityEditingDoesNotMaintainTasks() throws Exception {
    long activity = activity();
    user(role("activity:view", "activity:edit"));
    call("POST", "/api/tasks", Map.of("activityId", activity, "name", "不可新建任务"), 403);
  }

  @Test
  void ownAttendanceNeverGrantsOrganizationReadOrManualCorrection() throws Exception {
    long uid = user(role("schedule:check", "attendance:self:view"));
    db.update(
        "INSERT INTO schedule(schedule_type,status,org_id,created_at)"
            + " VALUES('ACTIVITY','PUBLISHED',1,CURRENT_TIMESTAMP)");
    long shift = db.queryForObject("SELECT MAX(id) FROM schedule", Long.class);
    db.update(
        "INSERT INTO attendance(schedule_id,person_id,person_name,status,org_id,created_at)"
            + " VALUES(?,?,'同名成员','NORMAL',1,CURRENT_TIMESTAMP)",
        shift,
        uid);
    db.update(
        "INSERT INTO attendance(schedule_id,person_id,person_name,status,org_id,created_at)"
            + " VALUES(?,?,'同名成员','ABSENT',1,CURRENT_TIMESTAMP)",
        shift,
        uid + 10000);
    var mine = call("GET", "/api/attendances/my", null, 200);
    assertThat(mine.size()).isEqualTo(1);
    assertThat(mine.get(0).path("personId").asLong()).isEqualTo(uid);
    call("GET", "/api/attendances", null, 403);
    call("PUT", "/api/attendances/1/status", Map.of("status", "NORMAL"), 403);
    call("POST", "/api/attendances/1/makeup", null, 403);
    call("GET", "/api/attendances/summary", null, 403);
  }

  @Test
  void selfCheckInDerivesIdentityAndStatusFromThePublishedShift() throws Exception {
    long uid = user(role("schedule:check"));
    db.update(
        "INSERT INTO"
            + " schedule(schedule_type,status,schedule_date,start_time,end_time,org_id,created_at)"
            + " VALUES('ACTIVITY','PUBLISHED',?,'00:00:00','23:59:59',1,CURRENT_TIMESTAMP)",
        java.sql.Date.valueOf(LocalDate.now()));
    long shift = db.queryForObject("SELECT MAX(id) FROM schedule", Long.class);
    db.update(
        "INSERT INTO schedule_person(schedule_id,user_id,person_name,is_primary,org_id)"
            + " VALUES(?,?,'同名成员',1,1)",
        shift,
        uid);
    var recorded =
        call(
            "POST",
            "/api/attendances",
            Map.of(
                "scheduleId",
                shift,
                "personId",
                uid + 10000,
                "personName",
                "别人",
                "status",
                "MAKEUP"),
            200);
    assertThat(recorded.path("personId").asLong()).isEqualTo(uid);
    assertThat(recorded.path("status").asText()).isIn("NORMAL", "LATE");
    long id = recorded.path("id").asLong();
    var repeated =
        call(
            "POST",
            "/api/attendances",
            Map.of("scheduleId", shift, "personName", "别人", "status", "NORMAL"),
            200);
    assertThat(repeated.path("id").asLong()).isEqualTo(id);
    db.update("UPDATE schedule SET status='DRAFT' WHERE id=?", shift);
    entities.clear();
    call(
        "POST",
        "/api/attendances",
        Map.of("scheduleId", shift, "personName", "本人", "status", "NORMAL"),
        409);
  }

  @Test
  void regularScheduleDraftRequiresSeparatePublicationAndStaysOutOfPersonalSchedules()
      throws Exception {
    long editorRole = role("schedule:view", "schedule:manage", "schedule:self:view");
    long publisherRole = role("schedule:view", "schedule:publish");
    long uid = user(editorRole);
    String editorToken = token;
    var data =
        Map.of(
            "scheduleType",
            "CLASS_DUTY",
            "scheduleDate",
            LocalDate.now().toString(),
            "sessionName",
            "09:00-10:00",
            "persons",
            List.of(Map.of("userId", uid, "personName", "本人")));
    long shift = call("POST", "/api/schedules", data, 200).asLong();
    assertThat(db.queryForObject("SELECT status FROM schedule WHERE id=?", String.class, shift))
        .isEqualTo("DRAFT");
    assertThat(call("GET", "/api/schedules/my", null, 200).toString())
        .doesNotContain("\"id\":" + shift + ",");
    call("POST", "/api/schedules/" + shift + "/publish", null, 403);
    token = login("teacher");
    user(publisherRole);
    call("POST", "/api/schedules/" + shift + "/publish", null, 200);
    token = editorToken;
    assertThat(call("GET", "/api/schedules/my", null, 200).findValuesAsText("id"))
        .contains(Long.toString(shift));
    call("PUT", "/api/schedules/" + shift, data, 403);
  }

  @Test
  void allBusinessPermissionsHaveExplicitCompleteDefinitions() throws Exception {
    var catalogue = call("GET", "/api/permissions/catalog", null, 200);
    for (var p : catalogue) {
      assertThat(p.path("description").asText()).isNotBlank();
      assertThat(p.path("dependencies").isArray()).isTrue();
      assertThat(p.path("catalogVersion").asInt()).isEqualTo(3);
    }
  }

  @Test
  void generationEditingAndPublishingAreIndependent() throws Exception {
    user(role("schedule:view", "schedule:generate"));
    call("POST", "/api/activity-schedules/1/publish", null, 403);
    call("POST", "/api/activity-schedules/publish-all", null, 403);
    call("GET", "/api/schedules/export", null, 403);
  }

  @Test
  void editorsDoNotAcquireReviewIndexOrNewsPublication() throws Exception {
    user(role("content:view", "article:edit", "kb:read", "kb:document:manage", "news:manage"));
    call("PUT", "/api/articles/1/review", Map.of("approved", true), 403);
    call("POST", "/api/news/1/publish", null, 403);
    call("POST", "/api/kb/reindex", null, 403);
    call("POST", "/api/kb/snapshots/refresh", null, 403);
  }

  @Test
  void explicitCrossModuleDependenciesAreValidated() throws Exception {
    long id = call("POST", "/api/roles", Map.of("name", "缺少依赖-" + UUID.randomUUID()), 200).asLong();
    call(
        "PUT",
        "/api/roles/" + id + "/draft",
        Map.of(
            "draftVersion",
            0,
            "permissionChanges",
            List.of(Map.of("operation", "SET", "permission", "plan:review", "scope", "ORG"))),
        200);
    call(
        "POST",
        "/api/roles/" + id + "/change-previews",
        Map.of("action", "PUBLISH", "version", 0),
        400);
  }

  @Test
  void composingTemplatesCreatesOneDraftAndNeverPublishesIt() throws Exception {
    long id =
        call(
                "POST",
                "/api/roles",
                Map.of(
                    "name",
                    "组合职责-" + UUID.randomUUID(),
                    "templateCodes",
                    List.of("DUTY_TASK", "DUTY_PLAN_REVIEW")),
                200)
            .asLong();
    var detail = call("GET", "/api/roles/" + id, null, 200);
    assertThat(detail.path("draft").path("grants").asText())
        .contains("task:progress", "plan:review");
    assertThat(detail.path("published").size()).isZero();
    assertThat(detail.path("role").path("status").asText()).isEqualTo("DRAFT");
  }

  @Test
  void diagnosisDoesNotReturnBusinessContentsOrCrossOrganizationTargets() throws Exception {
    long role = role("activity:view");
    long uid = user(role);
    token = login("teacher");
    var result =
        call(
            "GET",
            "/api/users/" + uid + "/permission-explanation?operation=plan:review&resourceId=999999",
            null,
            200);
    assertThat(result.path("allowed").asBoolean()).isFalse();
    assertThat(result.fieldNames()).toIterable().containsExactlyInAnyOrder("allowed", "reason");
    call(
        "GET",
        "/api/users/" + uid + "/permission-explanation?operation=unknown&resourceId=1",
        null,
        400);
    db.update("INSERT INTO organization(name,type,status,deleted) VALUES('隔离组织','OTHER',1,0)");
    long org = db.queryForObject("SELECT MAX(id) FROM organization", Long.class);
    db.update("UPDATE sys_user SET org_id=? WHERE id=?", org, uid);
    call("GET", "/api/users/" + uid + "/effective-permissions", null, 404);
  }
}
