package com.pams.module.agent.service;
import com.pams.common.OrgContext;
import com.pams.module.agent.core.ToolContext;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class AgentDirectoryReadTest {
    @org.springframework.test.context.DynamicPropertySource static void database(org.springframework.test.context.DynamicPropertyRegistry registry){AgentBatchFiveDatabase.configure(registry);}
    @Autowired AgentDirectoryReadService reads;@Autowired JdbcTemplate db;
    ToolContext ctx;String prefix;long session;
    @BeforeEach void setup(){OrgContext.set(1L);prefix="dir-"+UUID.randomUUID().toString().substring(0,12);
        ctx=new ToolContext(new LoginUser(1L,"teacher","测试","TEACHER",5,null,null,1L),List.of(new SimpleGrantedAuthority("member:view")),null,null);
        db.update("INSERT INTO member_session(org_id,name,is_current,sort_order,deleted,created_at) VALUES(?,?,0,0,0,CURRENT_TIMESTAMP)",1L,prefix);
        session=db.queryForObject("SELECT id FROM member_session WHERE org_id=1 AND name=?",Long.class,prefix);
    }
    @AfterEach void clear(){OrgContext.clear();}
    void member(int number){db.update("INSERT INTO member(org_id,session_id,name,position,status,deleted,student_no,phone) VALUES(?,?,?,'MEMBER','ACTIVE',0,?,?)",1L,session,prefix+"同名",prefix+number,"13800138000");}
    void account(String suffix){long role=db.queryForObject("SELECT role_id FROM sys_user WHERE username='teacher'",Long.class);db.update("INSERT INTO sys_user(org_id,username,password,real_name,student_no,role_id,status,deleted) VALUES(?,?,'test','账号名称',?,?,1,0)",1L,prefix+suffix,prefix+0,role);}
    @Test void memberAndAccountIdentityAreSeparateAndAmbiguousAccountsAreNotGuessed(){
        member(0);member(1);account("a");var result=reads.members(ctx,Map.of("keyword",prefix));
        @SuppressWarnings("unchecked")var rows=(List<Map<String,Object>>)result.payload().get("members");
        assertThat(rows).hasSize(2);assertThat(rows.getFirst()).containsKeys("memberId","userId","accountState");assertThat(rows.getFirst().get("userId")).isNotNull();assertThat(rows.get(1).get("userId")).isNull();
        assertThat(rows.toString()).doesNotContain("13800138000","studentNo","password","phone");
        @SuppressWarnings("unchecked")var positions=(List<Map<String,Object>>)reads.units(ctx,Map.of()).payload().get("positions");assertThat(positions).isNotEmpty().allSatisfy(p->assertThat(p).containsKey("code").doesNotContainKey("id"));
        account("b");result=reads.members(ctx,Map.of("keyword",prefix));
        @SuppressWarnings("unchecked")var ambiguous=(List<Map<String,Object>>)result.payload().get("members");assertThat(ambiguous.getFirst()).containsEntry("userId",null).containsEntry("accountState","AMBIGUOUS");
    }
    @Test void sameScopePaginationAndNoImplicitMemberPermissionFromSending(){
        for(int i=0;i<7;i++)member(i);var result=reads.members(ctx,Map.of("keyword",prefix));assertThat(result.payload()).containsEntry("total",7L).containsEntry("hasMore",true);
        assertThat(reads.members(ctx,Map.of("keyword",prefix,"page",1)).payload()).containsEntry("hasMore",false);
        var sendOnly=new ToolContext(ctx.getLoginUser(),List.of(new SimpleGrantedAuthority("notification:send")),null,null);assertThatThrownBy(()->reads.members(sendOnly,Map.of())).hasMessageContaining("权限");
        assertThat(reads.units(ctx,Map.of()).payload().toString()).doesNotContain("password","capabilities","permissions","phone");
        var otherOrg=new ToolContext(new LoginUser(1L,"u","u","TEACHER",5,null,null,2L),List.of(new SimpleGrantedAuthority("member:view")),null,null);assertThatThrownBy(()->reads.units(otherOrg,Map.of())).hasMessageContaining("组织");
    }
}
