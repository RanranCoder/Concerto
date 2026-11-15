package com.pams.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
class ExtensionWorkspacesTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();

    record Org(long id, long branch, long member, String student, String token) {}

    @Test
    void youthRecordsFollowThePackAndHandoffDoesNotChangePartyStatus() throws Exception {
        Org org = register(List.of("party"));
        call(org, "GET", "/api/youth/overview", null, 403);
        assertThat(call(org, "GET", "/api/agent/tools", null, 200).toString()).doesNotContain("queryYouthWorkSummary");
        toggle(org, "youth", true);
        assertThat(call(org, "GET", "/api/agent/tools", null, 200).toString()).contains("queryYouthWorkSummary");
        call(org, "POST", "/api/youth/members", Map.of("memberId", org.member, "branchId", org.branch, "duty", "团支书"), 200);
        long youth = first(org, "/api/youth/members").path("id").asLong();
        assertThat(call(org, "GET", "/api/youth/members/" + youth + "/history", null, 200)).hasSize(1);
        call(org, "POST", "/api/youth/life", Map.of("branchId", org.branch, "kind", "THEME_DAY", "name", "支部学习",
                "date", "2026-11-12", "agenda", "交流学习", "minutes", "会议纪要"), 200);
        var life = first(org, "/api/youth/life");
        call(org, "PUT", "/api/youth/life/" + life.path("id").asLong() + "/archive", Map.of("version", 0), 200);
        assertThat(first(org, "/api/youth/life").path("archivedAt").asText()).isNotBlank();

        for (String kind : List.of("EVALUATION", "RECOMMENDATION")) {
            call(org, "POST", "/api/youth/cases", Map.of("youthMemberId", youth, "kind", kind, "year", 2026,
                    "submission", "支部已核对材料", "criteria", "依据本年度支部评议安排"), 200);
        }
        String evaluations = "/api/youth/cases?kind=EVALUATION";
        assertThat(first(org, evaluations).path("criteria").asText()).isEqualTo("依据本年度支部评议安排");
        long evaluation = first(org, evaluations).path("id").asLong();
        call(org, "PUT", "/api/youth/cases/" + evaluation + "/review", Map.of("version", 0, "approved", true, "decision", "确认结果"), 200);
        call(org, "PUT", "/api/youth/cases/" + evaluation + "/archive", Map.of("version", 1), 200);
        assertThat(first(org, evaluations).path("status").asText()).isEqualTo("ARCHIVED");

        String recommendations = "/api/youth/cases?kind=RECOMMENDATION";
        long recommendation = first(org, recommendations).path("id").asLong();
        String url = "/api/youth/cases/" + recommendation;
        call(org, "PUT", url + "/review", Map.of("version", 0, "approved", false, "decision", "补充评议意见"), 200);
        call(org, "POST", "/api/youth/cases", Map.of("youthMemberId", youth, "kind", "RECOMMENDATION", "year", 2026,
                "submission", "已补充评议意见", "version", 1), 200);
        call(org, "PUT", url + "/review", Map.of("version", 2, "approved", true, "decision", "同意推荐"), 200);
        toggle(org, "party", false);
        assertThat(call(org, "GET", url + "/export", null, 200).path("submission").asText()).isEqualTo("已补充评议意见");
        call(org, "GET", url + "/party-matches", null, 403);
        toggle(org, "party", true);
        long party = call(org, "POST", "/api/party/members", Map.of("name", "陈禾", "studentNo", org.student, "politicalStatus", "入党申请人"), 200).asLong();
        assertThat(call(org, "GET", url + "/party-matches", null, 200).get(0).path("id").asLong()).isEqualTo(party);
        call(org, "PUT", url + "/receive", Map.of("version", 3, "partyMemberId", party), 200);
        assertThat(call(org, "GET", "/api/youth/party-receipts/" + party, null, 200)).hasSize(1);
        assertThat(call(org, "GET", url + "/history", null, 200)).hasSize(5);
        assertThat(call(org, "GET", "/api/party/members/" + party, null, 200).path("politicalStatus").asText()).isEqualTo("入党申请人");
        toggle(org, "youth", false);
        call(org, "GET", recommendations, null, 403);
        assertThat(call(org, "GET", "/api/agent/tools", null, 200).toString()).doesNotContain("queryYouthWorkSummary");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM youth_case WHERE org_id=?", Integer.class, org.id)).isEqualTo(2);
        toggle(org, "youth", true);
        assertThat(first(org, recommendations).path("status").asText()).isEqualTo("RECEIVED");
    }

    @Test
    void volunteerRecordsRequireMatchingAttendanceAndRetainApprovedHours() throws Exception {
        Org org = register(List.of("volunteer"));
        Org other = register(List.of("volunteer"));
        call(org, "POST", "/api/volunteer/activities", Map.of("name", "校园志愿服务", "date", "2026-11-12", "maxMinutes", 180), 200);
        var event = first(org, "/api/volunteer/activities");
        long activityId = event.path("activityId").asLong();
        String path = "/api/volunteer/activities/" + event.path("id").asLong();
        call(other, "GET", path, null, 404);
        call(org, "POST", path + "/registrations", Map.of("memberId", other.member), 404);
        call(org, "POST", path + "/registrations", Map.of("memberId", org.member), 200);
        call(org, "POST", path + "/registrations", Map.of("memberId", org.member), 409);
        long record = call(org, "GET", path, null, 200).path("records").path("records").get(0).path("id").asLong();
        String recordPath = "/api/volunteer/records/" + record;
        call(org, "PUT", recordPath + "/submit", Map.of("version", 0, "minutes", 120), 400);
        db.update("INSERT INTO signin(org_id,activity_id,name,student_no,sign_type,sign_time) VALUES(?,?,?,?,?,?)",
                org.id, activityId, "陈禾", org.student, "MANUAL", Timestamp.valueOf(LocalDateTime.of(2026, 11, 12, 9, 0)));
        call(org, "PUT", recordPath + "/submit", Map.of("version", 0, "minutes", 181), 400);
        call(org, "PUT", recordPath + "/submit", Map.of("version", 0, "minutes", 120), 200);
        assertThat(call(org, "GET", path + "/export", null, 200)).isEmpty();
        db.update("DELETE FROM signin WHERE org_id=? AND activity_id=?", org.id, activityId);
        call(org, "PUT", recordPath + "/review", Map.of("version", 1, "approved", true), 404);
        call(org, "PUT", recordPath + "/review", Map.of("version", 1, "approved", false, "decision", "签到需重新核对"), 200);
        db.update("INSERT INTO signin(org_id,activity_id,name,student_no,sign_type,sign_time) VALUES(?,?,?,?,?,?)",
                org.id, activityId, "陈禾", org.student, "MANUAL", Timestamp.valueOf(LocalDateTime.of(2026, 11, 12, 9, 0)));
        call(org, "PUT", recordPath + "/submit", Map.of("version", 2, "minutes", 120), 200);
        call(org, "PUT", recordPath + "/review", Map.of("version", 0, "approved", true), 409);
        call(org, "PUT", recordPath + "/review", Map.of("version", 3, "approved", true), 200);
        toggle(org, "volunteer", false);
        call(org, "GET", path + "/export", null, 403);
        call(org, "PUT", recordPath + "/review", Map.of("version", 2, "approved", true), 403);
        toggle(org, "volunteer", true);
        assertThat(call(org, "GET", path + "/export", null, 200).get(0).path("serviceMinutes").asInt()).isEqualTo(120);
        assertThat(first(org, "/api/volunteer/activities").path("approvedMinutes").asInt()).isEqualTo(120);
    }

    private Org register(List<String> packs) throws Exception {
        String code = UUID.randomUUID().toString().substring(0, 8);
        String name = "pack_" + code;
        var payload = Map.of("org", Map.of("name", "验收组织-" + code), "admin", Map.of("username", name, "password", "test-password"),
                "units", List.of(Map.of("name", "第一支部")), "enabledPacks", packs);
        long org = call(null, "POST", "/api/orgs/register", payload, 200).path("orgId").asLong();
        String token = call(null, "POST", "/api/auth/login", Map.of("username", name, "password", "test-password"), 200).path("token").asText();
        long branch = db.queryForObject("SELECT id FROM sys_department WHERE org_id=?", Long.class, org);
        db.update("INSERT INTO member_session(org_id,name,is_current) VALUES(?,?,1)", org, "届别-" + code);
        long session = db.queryForObject("SELECT id FROM member_session WHERE org_id=?", Long.class, org);
        db.update("INSERT INTO member(org_id,session_id,dept_id,name,student_no,position,status,deleted) VALUES(?,?,?,?,?,'STAFF','ACTIVE',0)",
                org, session, branch, "陈禾", code);
        long member = db.queryForObject("SELECT id FROM member WHERE org_id=?", Long.class, org);
        return new Org(org, branch, member, code, token);
    }

    private void toggle(Org org, String pack, boolean enabled) throws Exception {
        call(org, "PUT", "/api/orgs/current/packs/" + pack, Map.of("enabled", enabled), 200);
    }

    private JsonNode first(Org org, String path) throws Exception { return call(org, "GET", path, null, 200).path("records").get(0); }

    private JsonNode call(Org org, String method, String path, Object body, int expected) throws Exception {
        var request = switch (method) { case "PUT" -> put(path); case "POST" -> post(path); default -> get(path); };
        if (org != null) request.header("Authorization", "Bearer " + org.token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (path.equals("/api/orgs/register")) request.header("X-Forwarded-For", UUID.randomUUID().toString());
        return json.readTree(mvc.perform(request).andExpect(status().is(expected)).andReturn()
                .getResponse().getContentAsString()).path("data");
    }
}
