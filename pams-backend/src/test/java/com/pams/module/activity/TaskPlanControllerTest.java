package com.pams.module.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TaskPlanControllerTest {
    @Autowired MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.pams.repository.DepartmentRepository departments;
    final ObjectMapper json = new ObjectMapper();
    String token;

    @BeforeEach void login() throws Exception {
        token = json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"teacher\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("token").asText();
    }

    JsonNode call(String method, String url, Object body, int expected) throws Exception {
        var request = method.equals("GET") ? get(url) : method.equals("PUT") ? put(url) : post(url);
        request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
        if (body != null) request.content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }

    long activity() throws Exception {
        return call("POST", "/api/activities", Map.of("name", "任务计划接口验证", "type", "OTHER"), 200).path("data").asLong();
    }

    @Test void memberOptionsAreScopedToSelectedDepartmentAndOrganization() throws Exception {
        var department = new com.pams.entity.Department();department.setOrgId(1L);department.setName("任务选人部门");
        long deptId=departments.saveAndFlush(department).getId();
        var other = new com.pams.entity.Department();other.setOrgId(1L);other.setName("其他选人部门");
        long otherId=departments.saveAndFlush(other).getId();
        for (int i=0;i<5;i++) {
            jdbc.update("INSERT INTO sys_user(username,password,real_name,role_id,dept_id,org_id,status,deleted) "
                    + "SELECT ?,password,?,role_id,?,?,?,? FROM sys_user WHERE username='teacher'",
                    "task-picker-"+java.util.UUID.randomUUID().toString().substring(0,8), "候选成员"+i,
                    i==1?otherId:deptId,i==2?999999L:1L,i==3?0:1,i==4?1L:0L);
        }
        var response=mvc.perform(get("/api/users/options").param("deptId",String.valueOf(deptId))
                .with(user("task-picker").authorities(new SimpleGrantedAuthority("task:manage"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode result=json.readTree(response).path("data");
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.get(0).path("realName").asText()).isEqualTo("候选成员0");
        assertThat(result.get(0).has("username")).isFalse();
        assertThat(result.get(0).has("phone")).isFalse();
    }

    @Test void previewSaveAndUndoRoundTripUsesRealVersions() throws Exception {
        long activity = activity();
        JsonNode parent = call("POST", "/api/tasks", Map.of("activityId",activity,"name","前置准备","startDate","2026-10-20","endDate","2026-10-21"),200).path("data");
        long id = parent.path("id").asLong();
        long child = call("POST", "/api/tasks", Map.of("activityId",activity,"name","后续准备","startDate","2026-10-22","endDate","2026-10-23","dependsOn",id),200).path("data").path("id").asLong();
        var task = Map.of("activityId",activity,"name","前置准备","startDate","2026-10-20","endDate","2026-10-24","expectedRevision",parent.path("revision").asLong());
        JsonNode preview = call("POST","/api/tasks/plan/preview",Map.of("id",id,"task",task),200).path("data");
        assertThat(preview.path("impacts").size()).isEqualTo(1);
        JsonNode saved = call("POST","/api/tasks/plan",Map.of("id",id,"task",task,"previewToken",preview.path("token").asText(),"cascade",true),200).path("data");
        assertThat(saved.path("undo").path("entries").size()).isEqualTo(2);
        JsonNode shifted = saved.path("tasks").findValues("startDate").stream().filter(d->d.asText().equals("2026-10-25")).findFirst().orElseThrow();
        assertThat(shifted.asText()).isEqualTo("2026-10-25");
        JsonNode restored = call("POST","/api/tasks/plan/undo",saved.path("undo"),200).path("data");
        for (JsonNode row : restored.path("tasks")) if (row.path("id").asLong()==child) {
            assertThat(row.path("startDate").asText()).isEqualTo("2026-10-22");
            assertThat(row.path("revision").asLong()).isGreaterThan(0);
        }
    }

    @Test void executorCannotSaveOrRestorePlans_andUnscheduledDatesStayNull() throws Exception {
        long activity=activity();var task=Map.of("activityId",activity,"name","未排期任务");
        JsonNode preview=call("POST","/api/tasks/plan/preview",Map.of("task",task),200).path("data");
        JsonNode result=call("POST","/api/tasks/plan",Map.of("task",task,"previewToken",preview.path("token").asText()),200).path("data");
        JsonNode saved=result.path("tasks").get(0);
        assertThat(saved.path("startDate").isNull()).isTrue();
        assertThat(saved.path("endDate").isNull()).isTrue();
        var actor=user("executor").authorities(new SimpleGrantedAuthority("task:progress"));
        mvc.perform(post("/api/tasks/plan/preview").with(actor).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("task",task)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks/plan/undo").with(actor).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(result.path("undo")))).andExpect(status().isForbidden());
        JsonNode progress=call("PUT","/api/tasks/"+saved.path("id").asLong()+"/progress",Map.of("progress",100,"expectedRevision",saved.path("revision").asLong()),200).path("data");
        assertThat(progress.path("status").asText()).isEqualTo("DONE");
        assertThat(progress.path("revision").asLong()).isGreaterThan(saved.path("revision").asLong());
        assertThat(call("POST","/api/tasks/plan/undo",result.path("undo"),400).path("message").asText()).contains("不会覆盖");
    }
}
