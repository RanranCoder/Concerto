package com.pams.module.agent.orchestration;
import com.pams.common.OrgContext;
import com.pams.security.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class ControlledPlanningHttpTest {
    @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired JwtUtil jwt;
    @Autowired com.pams.module.agent.config.AgentProperties props;
    String token;
    @BeforeEach void setup(){OrgContext.set(1L);Long user=db.queryForObject("SELECT id FROM sys_user WHERE username='teacher'",Long.class);token=jwt.generate(user,"teacher","TEACHER");}
    @AfterEach void clear(){props.setControlledPlanningEnabled(false);props.getControlledPlanningOrgIds().clear();OrgContext.clear();}
    @Test void authenticatedDefaultPreviewReturnsRuleBaselineWithoutCreatingRunOrBusinessRows()throws Exception{
        long runs=db.queryForObject("SELECT COUNT(*) FROM agent_run WHERE org_id=1",Long.class),activities=db.queryForObject("SELECT COUNT(*) FROM activity WHERE org_id=1",Long.class);
        mvc.perform(post("/api/agent/team/plan/controlled").header("Authorization","Bearer "+token).contentType("application/json").content("{\"goal\":\"查询活动\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.previewOnly").value(true)).andExpect(jsonPath("$.data.status").value("RULE_BASELINE"))
                .andExpect(jsonPath("$.data.reasonCode").value("ROLLOUT_DISABLED")).andExpect(jsonPath("$.data.attempts").value(0));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_run WHERE org_id=1",Long.class)).isEqualTo(runs);assertThat(db.queryForObject("SELECT COUNT(*) FROM activity WHERE org_id=1",Long.class)).isEqualTo(activities);
    }
    @Test void unauthenticatedAndInvalidGoalCannotEnterPreview()throws Exception{
        mvc.perform(post("/api/agent/team/plan/controlled").contentType("application/json").content("{\"goal\":\"查询\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/agent/team/plan/controlled").header("Authorization","Bearer "+token).contentType("application/json").content("{\"goal\":\" \"}")).andExpect(status().isBadRequest());
    }
    @Test void fullRegisteredMenuFitsBudgetAndStubCannotBecomeExecutionApproval()throws Exception{
        props.setControlledPlanningEnabled(true);props.getControlledPlanningOrgIds().add(1L);
        mvc.perform(post("/api/agent/team/plan/controlled").header("Authorization","Bearer "+token).contentType("application/json").content("{\"goal\":\"读取活动42\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reasonCode").value("MODEL_UNVERIFIED")).andExpect(jsonPath("$.data.attempts").value(1));
    }
    @Test void preparationAndControlledExecutionDoNotBypassDefaultRolloutGate()throws Exception{
        mvc.perform(post("/api/agent/team/plan/controlled/prepare").header("Authorization","Bearer "+token).contentType("application/json").content("{\"goal\":\"读取活动42\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.planRef").doesNotExist());
        mvc.perform(post("/api/agent/team/run/controlled").header("Authorization","Bearer "+token).contentType("application/json").content("{\"planRef\":\"forged\"}"))
                .andExpect(status().isConflict());
    }
}
