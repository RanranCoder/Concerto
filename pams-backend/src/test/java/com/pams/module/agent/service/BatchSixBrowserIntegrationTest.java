package com.pams.module.agent.service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PAMS_S5_BATCH6",matches="true")
class BatchSixBrowserIntegrationTest {
    @Test void browserConfirmsOnlyPersistedScopedPreviews()throws Exception{
        var output=Path.of(System.getProperty("pams.batch6.output")).toAbsolutePath().normalize();Files.createDirectories(output);
        try(var probe=new java.net.ServerSocket()){probe.bind(new java.net.InetSocketAddress("127.0.0.1",18080));}
        var ready=output.resolve("browser-fixture.json");var server=new BatchSixProcessRecoveryTest().child("browser",ready,output.resolve("browser-backend.log"));
        try{
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
            while(!Files.exists(ready)&&server.isAlive()&&System.nanoTime()<deadline)Thread.sleep(250);
            assertThat(Files.exists(ready)).as("see browser-backend.log").isTrue();
            assertThat(AgentPreparedOperations.mapper().readTree(Files.readString(ready)).path("pid").asLong()).isEqualTo(server.pid());
            boolean realWord = "true".equals(System.getenv("PAMS_AGENT_RELEASE_BROWSER_REAL"));
            String browserCommand = "export PATH=/c/dev/nodejs:$PATH; npx playwright test --config playwright.batch6.config.ts";
            if (realWord) browserCommand += " --grep 'real model Word'";
            var builder=new ProcessBuilder("C:/dev/Git/bin/bash.exe","-c",browserCommand);
            builder.directory(Path.of("../pams-web").toAbsolutePath().normalize().toFile());builder.environment().put("PAMS_BATCH6_BROWSER_OUTPUT",output.toString());
            var browser=builder.redirectErrorStream(true).redirectOutput(output.resolve("playwright.log").toFile()).start();
            try{assertThat(browser.waitFor(600,TimeUnit.SECONDS)).isTrue();assertThat(browser.exitValue()).as("see playwright.log").isZero();}
            finally{if(browser.isAlive()){browser.descendants().forEach(ProcessHandle::destroyForcibly);browser.destroyForcibly();}}
        }finally{server.destroy();if(!server.waitFor(30,TimeUnit.SECONDS))server.destroyForcibly();}
    }
}
