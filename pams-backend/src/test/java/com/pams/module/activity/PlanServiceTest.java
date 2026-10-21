package com.pams.module.activity;

import com.fasterxml.jackson.databind.*;
import com.pams.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.http.MediaType;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real requests, tenant filters, database locks and serialized responses. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class PlanServiceTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    final ObjectMapper json=new ObjectMapper();
    String author, outsider;
    long activity, plan, authorId;
    List<Long> reviewers;
    Map<Long,String> tokens=new HashMap<>();
    JsonNode data(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsString()).path("data"); }
    String login(String username) throws Exception {
        return data(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username",username,"password","123456"))))
            .andExpect(status().isOk()).andReturn()).path("token").asText();
    }
    MvcResult write(String verb,String url,String token,Object body,int expected) throws Exception {
        var builder=verb.equals("POST")?post(url):put(url);
        builder.header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON);
        if(body!=null) builder.content(json.writeValueAsString(body));
        return mvc.perform(builder).andExpect(status().is(expected)).andReturn();
    }
    JsonNode read(String url,String token) throws Exception {
        return data(mvc.perform(get(url).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn());
    }
    String reviewer(long uid) throws Exception {
        if(!tokens.containsKey(uid)) tokens.put(uid,login(users.findById(uid).orElseThrow().getUsername()));
        return tokens.get(uid);
    }
    @BeforeEach void setup() throws Exception {
        author=login("teacher"); outsider=login("staff");
        authorId=users.findByUsername("teacher").orElseThrow().getId();
        activity=data(write("POST","/api/activities",author,Map.of("name","版本审核测试 "+UUID.randomUUID(),"theme","原主题"),200)).asLong();
        plan=data(write("POST","/api/plans",author,Map.of("activityId",activity,"content","<p>当前版机密正文</p>"),200)).path("id").asLong();
        reviewers=new ArrayList<>();
        for(var c:read("/api/plans/reviewers",author)) if(reviewers.size()<2) reviewers.add(c.path("id").asLong());
        assertThat(reviewers).hasSize(2).doesNotContain(authorId);
    }
    void submit() throws Exception { write("PUT","/api/plans/"+plan+"/submit",author,Map.of("reviewerIds",reviewers),200); }
    void review(long uid,boolean approve,int expected) throws Exception {
        write("PUT","/api/plans/"+plan+"/review",reviewer(uid),Map.of("approved",approve,"comment",approve?"同意":"请完善流程"),expected);
    }
    @Test void privateDraftAndSubmissionNeverLeakThroughDetailOrWorkspace() throws Exception {
        var metadata=read("/api/plans/workspace?activityId="+activity,author).path("versions").get(0);
        assertThat(metadata.path("ownerId").asLong()).isEqualTo(authorId);
        assertThat(metadata.path("ownerName").asText()).isEqualTo(users.findByUsername("teacher").orElseThrow().getRealName());
        assertThat(metadata.path("note").asText()).isEqualTo("当前工作版本");
        for(String url:List.of("/api/plans?activityId="+activity,"/api/plans/workspace?activityId="+activity,"/api/activities/"+activity+"/detail"))
            assertThat(read(url,outsider).toString()).doesNotContain("当前版机密正文");
        mvc.perform(get("/api/plans/"+plan).header("Authorization","Bearer "+outsider)).andExpect(status().isForbidden());
        submit();
        assertThat(read("/api/plans/"+plan,reviewer(reviewers.getFirst())).path("content").asText()).contains("机密正文");
        assertThat(read("/api/activities/"+activity+"/detail",outsider).path("plan").path("currentHidden").asBoolean()).isTrue();
    }
    @Test void allReviewersMustApproveAndDuplicateDecisionIsRejected() throws Exception {
        submit(); review(reviewers.getFirst(),true,200);
        assertThat(read("/api/plans/"+plan,author).path("status").asText()).isEqualTo("PENDING");
        review(reviewers.getFirst(),true,400); review(reviewers.getLast(),true,200);
        assertThat(read("/api/plans/"+plan,author).path("status").asText()).isEqualTo("APPROVED");
        assertThat(read("/api/activities/"+activity,author).path("status").asText()).isEqualTo("ASSIGNED");
    }
    @Test void concurrentDecisionsAreSerializedWithoutLostApprovals() throws Exception {
        submit(); var first=reviewer(reviewers.getFirst()); var second=reviewer(reviewers.getLast());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var tasks=List.of(first,second).stream().map(token -> pool.submit(() -> {
                try { write("PUT","/api/plans/"+plan+"/review",token,Map.of("approved",true),200); }
                catch(Exception e) { throw new RuntimeException(e); }
            })).toList();
            for(var task:tasks) task.get(20,TimeUnit.SECONDS);
        }
        var saved=read("/api/plans/"+plan,author);
        assertThat(saved.path("status").asText()).isEqualTo("APPROVED");
        for(var r:saved.path("reviewers")) assertThat(r.path("approved").asBoolean()).isTrue();
    }
    @Test void rejectionLocksOriginalAndRevisionResetsAccessAndReviewers() throws Exception {
        submit(); review(reviewers.getFirst(),false,200);
        write("PUT","/api/plans/"+plan,author,Map.of("activityId",activity,"content","篡改历史"),400);
        long draft=data(write("POST","/api/plans/"+plan+"/revision",author,null,200)).path("id").asLong();
        assertThat(read("/api/plans/"+draft,author).path("reviewers").size()).isZero();
        assertThat(read("/api/plans/"+plan,outsider).path("content").asText()).contains("机密正文");
        mvc.perform(get("/api/plans/"+draft).header("Authorization","Bearer "+reviewer(reviewers.getFirst()))).andExpect(status().isForbidden());
        write("PUT","/api/plans/"+plan+"/submit",author,Map.of("reviewerIds",reviewers),400);
    }
    @Test void invalidAndSelfAssignmentsCannotSubmit() throws Exception {
        for(var ids:List.of(List.<Long>of(),List.of(authorId),List.of(reviewers.getFirst(),reviewers.getFirst()),List.of(99999999L)))
            write("PUT","/api/plans/"+plan+"/submit",author,Map.of("reviewerIds",ids),ids.isEmpty()||ids.size()==2?400:403);
        assertThat(read("/api/plans/"+plan,author).path("status").asText()).isEqualTo("DRAFT");
    }
    @Test void nonSelectedReviewerCannotReviewOrCreateCompetingDraft() throws Exception {
        submit(); write("PUT","/api/plans/"+plan+"/review",author,Map.of("approved",true),403);
        write("POST","/api/plans",reviewer(reviewers.getFirst()),Map.of("activityId",activity,"content","覆盖内容"),403);
        write("PUT","/api/plans/"+plan,author,Map.of("activityId",activity,"content","覆盖内容"),400);
    }
    @Test void savesRoundTripAndMetadataSnapshotsRemainStable() throws Exception {
        write("PUT","/api/plans/"+plan,author,Map.of("activityId",activity,"content","<p><strong>保存原地回显</strong></p>","nameOverride","冻结的文档名称","syncActivity",true),200);
        assertThat(read("/api/activities/"+activity,author).path("name").asText()).isEqualTo("冻结的文档名称");
        assertThat(read("/api/plans/"+plan,author).path("content").asText()).contains("<strong>保存原地回显</strong>");
        submit(); review(reviewers.getFirst(),true,200); review(reviewers.getLast(),true,200);
        write("POST","/api/plans/"+plan+"/revision",author,null,200);
        write("PUT","/api/activities/"+activity,author,Map.of("name","后来修改的活动名称","organizer","后来填写的组织"),200);
        assertThat(read("/api/plans/"+plan,outsider).path("nameOverride").asText()).isEqualTo("冻结的文档名称");
        assertThat(read("/api/plans/"+plan,outsider).path("organizerOverride").asText()).isEmpty();
    }
    @Test void staleDraftCannotOverwriteNewerSave() throws Exception {
        long original=read("/api/plans/"+plan,author).path("revision").asLong();
        write("PUT","/api/plans/"+plan,author,Map.of("activityId",activity,"content","新保存的内容"),200);
        write("PUT","/api/plans/"+plan,author,Map.of("activityId",activity,"content","过期窗口的内容","expectedRevision",original),409);
        assertThat(read("/api/plans/"+plan,author).path("content").asText()).isEqualTo("新保存的内容");
    }
    @Test void withdrawingKeepsDecisionsButCreatesUnassignedPrivateRevision() throws Exception {
        submit(); review(reviewers.getFirst(),true,200);
        long draft=data(write("POST","/api/plans/"+plan+"/revision",author,null,200)).path("id").asLong();
        var original=read("/api/plans/"+plan,outsider);
        assertThat(original.path("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(original.path("reviewers").get(0).path("approved").asBoolean()).isTrue();
        assertThat(read("/api/plans/"+draft,author).path("reviewers").size()).isZero();
        review(reviewers.getLast(),true,400);
    }
    @Test void privateImageIdsAndLegacyUrlsCannotBeUsedToStealAttachments() throws Exception {
        var upload=data(mvc.perform(multipart("/api/files/upload")
            .file(new org.springframework.mock.web.MockMultipartFile("file","private.png","image/png",new byte[]{1,2,3}))
            .param("bizType","PLAN").header("Authorization","Bearer "+author)).andExpect(status().isOk()).andReturn());
        long file=upload.path("id").asLong();
        String image="<p><img src=\"/api/files/"+file+"/download\"></p>";
        write("PUT","/api/plans/"+plan,author,Map.of("activityId",activity,"content",image),200);
        String other=reviewer(reviewers.getFirst());
        mvc.perform(get("/api/files/"+file+"/download").header("Authorization","Bearer "+other)).andExpect(status().isBadRequest());
        mvc.perform(get("/uploads/"+upload.path("path").asText()).header("Authorization","Bearer "+other)).andExpect(status().isNotFound());
        long otherActivity=data(write("POST","/api/activities",other,Map.of("name","图片引用测试"),200)).asLong();
        write("POST","/api/plans",other,Map.of("activityId",otherActivity,"content",image),400);
        submit();
        mvc.perform(get("/api/files/"+file+"/download").header("Authorization","Bearer "+other)).andExpect(status().isOk());
    }
}
