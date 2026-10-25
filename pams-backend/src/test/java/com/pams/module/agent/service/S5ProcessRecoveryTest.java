package com.pams.module.agent.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PAMS_S5_ACCEPTANCE",matches="true")
class S5ProcessRecoveryTest {
    static ProcessBuilder child(String phase,Path checkpoint,Path log) {
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx384m","-Dfile.encoding=UTF-8","-cp",
            System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),S5RuntimeProcess.class.getName(),phase,checkpoint.toString());
        var env=builder.environment();env.put("SPRING_DATASOURCE_URL",System.getenv("PAMS_S5_DB_URL"));env.put("SPRING_DATASOURCE_USERNAME",System.getenv("PAMS_S5_DB_USER"));
        env.put("SPRING_DATASOURCE_PASSWORD",System.getenv("PAMS_S5_DB_PASSWORD"));env.put("SPRING_DATASOURCE_DRIVER_CLASS_NAME","com.mysql.cj.jdbc.Driver");
        env.put("SPRING_FLYWAY_LOCATIONS","classpath:db/migration,classpath:db/migration-mysql");
        if(!"serve".equals(phase))env.put("PAMS_TEST_AGENT_PROVIDER","stub");
        return builder.redirectErrorStream(true).redirectOutput(log.toFile());
    }
    @Test void aRealKilledJvmPreservesPendingConfirmationAndNeverReplaysAnUncertainCommit() throws Exception {
        Path dir=Path.of("../docs/audits/lingsi-upgrade-implementation/s5-process").toAbsolutePath().normalize();Files.createDirectories(dir);
        Path checkpoint=dir.resolve("checkpoint-"+System.currentTimeMillis()+".json");
        Process prepare=child("prepare",checkpoint,dir.resolve("prepare.log")).start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
            while(!Files.exists(checkpoint) && prepare.isAlive() && System.nanoTime()<deadline)Thread.sleep(250);
            assertThat(Files.exists(checkpoint)).as("prepare checkpoint; see prepare.log").isTrue();
            var checkpointCases=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(checkpoint));
            assertThat(checkpointCases.isArray()).isTrue();
            assertThat(checkpointCases.size()).isEqualTo(2);
            prepare.destroyForcibly();assertThat(prepare.waitFor(20,TimeUnit.SECONDS)).isTrue();
        } finally {if(prepare.isAlive())prepare.destroyForcibly();}
        Process verify=child("verify",checkpoint,dir.resolve("verify.log")).start();
        try {
            assertThat(verify.waitFor(180,TimeUnit.SECONDS)).as("verification timeout").isTrue();
            assertThat(verify.exitValue()).as("recovery assertions; see verify.log").isZero();
            var evidence=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(dir.resolve("verified.json")));
            assertThat(evidence.path("status").asText()).isEqualTo("PASS");
            assertThat(evidence.path("actualJvmKilled").asBoolean()).isTrue();
            assertThat(evidence.path("cases").size()).isEqualTo(2);
        } finally {if(verify.isAlive())verify.destroyForcibly();}
    }
}
