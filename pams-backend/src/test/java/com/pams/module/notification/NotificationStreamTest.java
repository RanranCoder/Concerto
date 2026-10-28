package com.pams.module.notification;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pams.module.notification.service.NotificationStreamRegistry;
import com.pams.security.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationStreamTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired JwtUtil jwt;
  @Autowired NotificationStreamRegistry streams;
  @AfterEach void close(){streams.shutdown();}
  @Test void streamRequiresBearerEvenWhenAUserIsNamedInQuery() throws Exception {
    mvc.perform(get("/api/notifications/stream?userId=1&orgId=1")).andExpect(status().isUnauthorized());
    assertThat(streams.size()).isZero();
  }
  @Test void authenticatedStreamEmitsThinSignalsAndCleansUpOnRevocation() throws Exception {
    long uid=db.queryForObject("SELECT id FROM sys_user WHERE username='teacher'",Long.class);
    String token=jwt.generate(uid,"teacher","TEACHER",null);
    var response=mvc.perform(get("/api/notifications/stream").header("Authorization","Bearer "+token))
      .andExpect(status().isOk()).andExpect(request().asyncStarted())
      .andExpect(header().string("X-Accel-Buffering","no")).andReturn().getResponse();
    await(()->response.getContentAsString().contains("event:ready"));
    assertThat(response.getContentAsString()).contains("event:ready").doesNotContain("策划书");
    streams.changed(1,uid);
    await(()->response.getContentAsString().contains("event:inbox.changed"));
    db.update("UPDATE sys_user SET status=0 WHERE id=?",uid);
    try {streams.heartbeat();await(()->streams.size()==0);}
    finally {db.update("UPDATE sys_user SET status=1 WHERE id=?",uid);}
  }
  interface Check {boolean get() throws Exception;}
  static void await(Check check) throws Exception {
    long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
    while(!check.get() && System.nanoTime()<end)Thread.sleep(10);
    assertThat(check.get()).isTrue();
  }
}
