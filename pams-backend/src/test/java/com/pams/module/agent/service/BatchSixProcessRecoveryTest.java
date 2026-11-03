package com.pams.module.agent.service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PAMS_S5_BATCH6",matches="true")
class BatchSixProcessRecoveryTest {
    Process child(String phase,Path checkpoint,Path log)throws Exception{
        var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx384m","-Dfile.encoding=UTF-8","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),BatchSixRuntimeProcess.class.getName(),phase,checkpoint.toString());
        var env=p.environment();env.put("SPRING_DATASOURCE_URL",System.getenv("PAMS_S5_DB_URL"));env.put("SPRING_DATASOURCE_USERNAME",System.getenv("PAMS_S5_DB_USER"));env.put("SPRING_DATASOURCE_PASSWORD",System.getenv("PAMS_S5_DB_PASSWORD"));env.put("SPRING_DATASOURCE_DRIVER_CLASS_NAME","com.mysql.cj.jdbc.Driver");env.put("SPRING_FLYWAY_LOCATIONS","classpath:db/migration,classpath:db/migration-mysql");env.put("PAMS_TEST_AGENT_PROVIDER","stub");
        return p.redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    @Test void killedJvmRecoversPendingCommittedInFlightAndControlledNodes()throws Exception{
        var output=Path.of(System.getProperty("pams.batch6.output","../docs/audits/agent-acceptance-20261102")).toAbsolutePath().normalize();Files.createDirectories(output);var checkpoint=output.resolve("process-checkpoint.json");
        var preparing=child("prepare",checkpoint,output.resolve("process-prepare.log"));
        try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);while(!Files.exists(checkpoint)&&preparing.isAlive()&&System.nanoTime()<deadline)Thread.sleep(200);assertThat(Files.exists(checkpoint)).as("see process-prepare.log").isTrue();preparing.destroyForcibly();assertThat(preparing.waitFor(30,TimeUnit.SECONDS)).isTrue();}finally{if(preparing.isAlive())preparing.destroyForcibly();}
        var verifying=child("verify",checkpoint,output.resolve("process-verify.log"));
        try{assertThat(verifying.waitFor(180,TimeUnit.SECONDS)).isTrue();assertThat(verifying.exitValue()).as("see process-verify.log").isZero();var result=AgentPreparedOperations.mapper().readTree(Files.readString(output.resolve("process-recovery-evidence.json")));assertThat(result.path("actualJvmKilled").asBoolean()).isTrue();assertThat(result.path("cases").size()).isEqualTo(4);}finally{if(verifying.isAlive())verifying.destroyForcibly();}
    }
}
