package com.pams.module.agent.orchestration;
import com.pams.common.OrgContext;
import com.pams.module.agent.core.ToolContext;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit network opt-in. Synthetic goals only; never creates a Run or invokes a business tool. */
@EnabledIfEnvironmentVariable(named="PAMS_RUN_CONTROLLED_PLANNING_REAL",matches="true")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:pams_controlled_planning_real;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","pams.agent.benchmark-enabled=true","pams.agent.benchmark-org-ids[0]=1","pams.agent.controlled-planning-enabled=true","pams.agent.controlled-planning-org-ids[0]=1"})
@ActiveProfiles("test")
public class ControlledPlanningRealModelTest {
    @Autowired ControlledPlanningEvaluation evaluation;@Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @Autowired com.pams.module.permission.service.TenantAuthorizationService authorization;
    @Autowired org.springframework.core.env.Environment environment;
    @Autowired com.pams.module.agent.provider.ProviderSecrets isolatedSecrets;
    /** Read-only local deployment config; secrets are decrypted/re-encrypted only in memory into H2. */
    Map<String,Object> installDeploymentProvider()throws Exception{
        return installTestProvider(db, environment, isolatedSecrets, false);
    }

    /** Allows the browser harness to seed only the isolated loopback MySQL acceptance schema. */
    public static Map<String,Object> installTestProvider(org.springframework.jdbc.core.JdbcTemplate db,
            org.springframework.core.env.Environment environment,
            com.pams.module.agent.provider.ProviderSecrets isolatedSecrets, boolean browserFixture) throws Exception {
        try (var isolated = db.getDataSource().getConnection()) {
            String url = isolated.getMetaData().getURL();
            boolean allowed = browserFixture
                    ? url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/pams_s5_batch6_[a-f0-9]{12}(?:\\?.*)?")
                    : url.startsWith("jdbc:h2:mem:");
            if (!allowed) throw new IllegalStateException("真实模型专项只可写入隔离的验收库");
        }
        String requested=environment.getProperty("PAMS_REAL_MODEL","deepseek-flash");
        String master=environment.getProperty("pams.agent.provider-encryption-key","");
        if(master.isBlank())master=java.nio.file.Files.readString(java.nio.file.Path.of(environment.getProperty("PAMS_REAL_PROVIDER_KEY_FILE","../data/secrets/ai-provider.key"))).trim();
        try(var connection=java.sql.DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/pams_db?connectTimeout=2000&socketTimeout=3000&useSSL=false&allowPublicKeyRetrieval=true","root",environment.getProperty("DB_PASSWORD","root"))){
            connection.setReadOnly(true);
            try(var query=connection.prepareStatement("SELECT c.base_url,c.protocol,c.model_id,c.api_key_ciphertext,c.summary_enabled FROM agent_provider_config c LEFT JOIN agent_provider_default d ON d.config_id=c.id AND d.org_id=c.org_id WHERE c.org_id=1 AND c.model_id=? ORDER BY CASE WHEN d.config_id IS NULL THEN 1 ELSE 0 END,c.id")){
                query.setString(1,requested);query.setMaxRows(1);query.setQueryTimeout(3);
                try(var row=query.executeQuery()){
                    if(!row.next())throw new IllegalStateException("指定模型没有本地服务商配置，不能用stub代替真实模型验收");
                    String url=row.getString(1),protocol=row.getString(2),model=row.getString(3);
                    String plaintext=new com.pams.module.agent.provider.ProviderSecrets(master).decrypt(row.getString(4));
                    db.update("INSERT INTO agent_provider_config(org_id,display_name,protocol,base_url,model_id,api_key_ciphertext,summary_enabled) VALUES(1,?,?,?,?,?,?)","隔离规划评测",protocol,url,model,isolatedSecrets.encrypt(plaintext),row.getBoolean(5));
                    Long id=db.queryForObject("SELECT id FROM agent_provider_config WHERE org_id=1 AND display_name=?",Long.class,"隔离规划评测");db.update("INSERT INTO agent_provider_default(org_id,config_id,version) VALUES(1,?,0)",id);
                    return Map.of("model",model,"protocol",protocol,"baseUrl",url,"sourceAccess","READ_ONLY_LOCAL_CONFIG",
                            "targetDatabase",browserFixture?"ISOLATED_LOOPBACK_MYSQL":"ISOLATED_IN_MEMORY_H2");
                }
            }
        }
    }
    record Case(String code,String goal,Set<String> expected,boolean clarify){}
    @Test void compareSyntheticPlansUnderSameCeilingWithoutBusinessExecution()throws Exception{
        var cases=List.of(
            new Case("R1_DETAIL","只读取活动42的详情，不做修改。",Set.of("getActivityDetail"),false),
            new Case("R2_COMPOSITE","先读取活动42详情，再查询该活动已登记的议程和已录入评分汇总，不生成建议方案。",Set.of("getActivityDetail","queryActivityAgenda","queryActivityScores"),false),
            new Case("A1_SUBMISSION","先读取稿件18的原文，再准备这篇稿件的送审清单；等我确认后提交组织审核，不审批、不发布。",Set.of("readArticleDocument","prepareArticleSubmission"),false),
            new Case("P1_STATUS","只查询策划书8的组织审批状态，不提交、不审批。",Set.of("getPlanReviewStatus"),false),
            new Case("C1_AMBIGUITY","把那个稿件送审；没有明确稿件ID先问我，不要默认选中任何稿件。",Set.of(),true),
            new Case("R3_TASKS","只读查询活动42的筹备任务进度，不修改任何任务。",Set.of("queryActivityTasks"),false));
        String selected=environment.getProperty("PAMS_REAL_CASES","");
        if(!selected.isBlank()){var codes=Set.of(selected.split(","));cases=cases.stream().filter(c->codes.contains(c.code())).toList();if(cases.isEmpty())throw new IllegalArgumentException("没有匹配的合成评测场景");}
        OrgContext.set(1L);
        try{
            var providerFixture=installDeploymentProvider();
            Long uid=db.queryForObject("SELECT id FROM sys_user WHERE username='teacher' AND org_id=1",Long.class);
            var permissions=new HashSet<>(authorization.authorities(uid));permissions.add("org:audit:view");
            var ctx=new ToolContext(new LoginUser(uid,"teacher","合成评测身份","TEACHER",5,null,null,1L),permissions.stream().map(SimpleGrantedAuthority::new).toList(),null,null);
            var report=new LinkedHashMap<String,Object>(evaluation.compare(cases.stream().map(Case::goal).toList(),ctx));
            var samples=(List<?>)report.get("samples");var judgments=new ArrayList<Map<String,Object>>();var failures=new ArrayList<String>();
            for(int i=0;i<cases.size();i++){
                var definition=cases.get(i);var sample=(Map<?,?>)samples.get(i);var preview=(ControlledPlanningService.Preview)sample.get("modelCandidate");
                var actual=preview.steps().stream().map(ControlledPlanValidator.Step::capability).collect(java.util.stream.Collectors.toSet());
                boolean valid=definition.clarify()?"NEEDS_CLARIFICATION".equals(preview.status())&&!preview.clarifications().isEmpty():"MODEL_PLAN_VALIDATED".equals(preview.status())&&actual.equals(definition.expected());
                if(!definition.expected().contains("prepareArticleSubmission")&&preview.steps().stream().anyMatch(ControlledPlanValidator.Step::requiresConfirmation))valid=false;
                if(!valid)failures.add(definition.code()+":"+preview.status()+":"+preview.reasonCode()+":"+actual);
                judgments.add(Map.of("code",definition.code(),"passed",valid,"expectedCapabilities",definition.expected(),"actualCapabilities",actual));
            }
            report.put("syntheticCapabilityChecks",judgments);report.put("identityFixture","ISOLATED_TEST_TEACHER_PLUS_EVALUATION_CAPABILITY");report.put("businessExecutionBenefit","NOT_MEASURED");
            report.put("providerFixture",providerFixture);
            var output=java.nio.file.Path.of("target/batch6-real-planning-comparison.json");new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
            assertThat(failures).isEmpty();
        }finally{OrgContext.clear();}
    }
}
