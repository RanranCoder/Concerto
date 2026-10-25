package com.pams.module.agent.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PAMS_S5_BROWSER",matches="true")
class S5BrowserIntegrationTest {
    @Test void realHttpAndWebsocketUiUsesOnlyTheSandboxDatabase() throws Exception {
        Path dir=Path.of("../docs/audits/lingsi-upgrade-implementation/s5-browser").toAbsolutePath().normalize();Files.createDirectories(dir);
        try(var probe=new java.net.ServerSocket()) {probe.bind(new java.net.InetSocketAddress("127.0.0.1",18080));}
        Path ready=dir.resolve("server-ready-"+System.currentTimeMillis()+".json");
        Process backend=S5ProcessRecoveryTest.child("serve",ready,dir.resolve("backend.log")).start();
        try {
            var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();boolean healthy=false;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
            while(backend.isAlive() && System.nanoTime()<deadline) {
                try { if(!Files.exists(ready)){Thread.sleep(500);continue;}
                    var marker=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(ready));
                    assertThat(marker.path("pid").asLong()).isEqualTo(backend.pid());assertThat(marker.path("database").asText()).startsWith("pams_s5_");
                    var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18080/api/ping")).timeout(Duration.ofSeconds(3)).build(),HttpResponse.BodyHandlers.ofString());
                    if(response.statusCode()==200 && response.body().contains("pong")){healthy=true;break;}
                }catch(Exception ignored) { }
                Thread.sleep(500);
            }
            assertThat(healthy).as("isolated backend startup; see backend.log").isTrue();
            Path web=Path.of("../pams-web").toAbsolutePath().normalize();
            var builder=System.getProperty("os.name").toLowerCase().contains("win")
                ?new ProcessBuilder("C:/dev/Git/bin/bash.exe","-c","export PATH=/c/dev/nodejs:$PATH; npx playwright test --config playwright.s5.config.ts")
                :new ProcessBuilder("bash","-c","npx playwright test --config playwright.s5.config.ts");
            Process browser=builder.directory(web.toFile()).redirectErrorStream(true).redirectOutput(dir.resolve("playwright.log").toFile()).start();
            try {assertThat(browser.waitFor(420,TimeUnit.SECONDS)).isTrue();assertThat(browser.exitValue()).as("real browser acceptance; see playwright.log").isZero();}
            finally {if(browser.isAlive())browser.destroyForcibly();}
        } finally {backend.destroy();if(!backend.waitFor(30,TimeUnit.SECONDS))backend.destroyForcibly();}
    }
}
