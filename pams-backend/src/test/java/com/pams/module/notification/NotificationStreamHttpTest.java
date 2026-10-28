package com.pams.module.notification;

import static org.assertj.core.api.Assertions.*;
import com.pams.module.notification.service.NotificationStreamRegistry;
import com.pams.security.JwtUtil;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NotificationStreamHttpTest {
  @Autowired Environment environment;
  @Autowired JwtUtil jwt;
  @Autowired JdbcTemplate db;
  @Autowired NotificationStreamRegistry streams;
  @Test void realHttpConnectionFlushesReadyAndChangeWithoutWaitingForClose() throws Exception {
    long uid=db.queryForObject("SELECT id FROM sys_user WHERE username='teacher'",Long.class);
    String token=jwt.generate(uid,"teacher","TEACHER");
    var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+environment.getProperty("local.server.port")+"/api/notifications/stream"))
      .header("Authorization","Bearer "+token).header("Accept","text/event-stream").GET().build();
    var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
    assertThat(response.statusCode()).isEqualTo(200);
    try(var input=response.body();var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8));var executor=Executors.newSingleThreadExecutor()) {
      assertThat(executor.submit(()->event(reader)).get(5,TimeUnit.SECONDS)).contains("event:ready");
      streams.changed(1,uid);
      assertThat(executor.submit(()->event(reader)).get(5,TimeUnit.SECONDS)).contains("event:inbox.changed");
    } finally {streams.shutdown();client.close();}
  }
  static String event(BufferedReader reader) throws IOException {
    StringBuilder result=new StringBuilder();String line;
    while((line=reader.readLine())!=null && !line.isEmpty())result.append(line).append('\n');
    return result.toString();
  }
}
