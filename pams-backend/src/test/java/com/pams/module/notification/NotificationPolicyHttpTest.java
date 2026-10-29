package com.pams.module.notification;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pams.common.OrgContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NotificationPolicyHttpTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired com.pams.security.JwtUtil jwt;
  String owner,staff;
  @BeforeEach void setup(){OrgContext.set(1L);db.update("DELETE FROM notification_org_policy WHERE org_id=1");owner=jwt.generate(db.queryForObject("SELECT id FROM sys_user WHERE username='teacher'",Long.class),"teacher","TEACHER");staff=jwt.generate(db.queryForObject("SELECT id FROM sys_user WHERE username='staff'",Long.class),"staff","STAFF");}
  @AfterEach void clear(){OrgContext.clear();}
  @Test void minutePrecisionRoundTripAndStaleVersionConflict() throws Exception {
    String body="""
      {"quietEnabled":true,"quietStart":"22:00","quietEnd":"08:00","digestEnabled":true,"digestTime":"09:00","version":0}
      """;
    mvc.perform(put("/api/notifications/operations/policy").header("Authorization","Bearer "+owner).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
    mvc.perform(get("/api/notifications/operations/policy").header("Authorization","Bearer "+owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.quietEnabled").value(true));
    mvc.perform(put("/api/notifications/operations/policy").header("Authorization","Bearer "+owner).contentType("application/json").content(body)).andExpect(status().isConflict());
  }
  @Test void policyAndMetricsRequireCurrentGovernanceIdentity() throws Exception {
    mvc.perform(get("/api/notifications/operations/policy")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/notifications/operations/policy").header("Authorization","Bearer "+staff)).andExpect(status().isForbidden());
    mvc.perform(get("/api/notifications/operations/metrics").header("Authorization","Bearer "+staff)).andExpect(status().isForbidden());
    mvc.perform(get("/api/notifications/operations/metrics").header("Authorization","Bearer "+owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.outbox.pending").isNumber());
  }
}
