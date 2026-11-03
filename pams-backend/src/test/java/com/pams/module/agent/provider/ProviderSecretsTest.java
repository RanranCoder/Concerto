package com.pams.module.agent.provider;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class ProviderSecretsTest {
    @TempDir Path directory;
    private JdbcTemplate db;
    private Path file;
    @BeforeEach void setup() {
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V59__agent_provider_settings.sql"),
                new ClassPathResource("db/migration/V60__agent_provider_key_state.sql")).execute(ds);
        file = directory.resolve("secrets/provider.key");
    }
    private ProviderSecrets automatic() { return new ProviderSecrets("", file.toString(), db); }
    @Test void initializesWithoutEnvironmentAndSurvivesRestart() throws Exception {
        var first=automatic(); assertThat(first.available()).isTrue();
        String ciphertext=first.encrypt("private-api-key");String key=Files.readString(file);
        assertThat(ciphertext).doesNotContain("private-api-key");
        var restarted=automatic(); assertThat(restarted.available()).isTrue();
        assertThat(restarted.decrypt(ciphertext)).isEqualTo("private-api-key");
        assertThat(Files.readString(file)).isEqualTo(key);
        assertThat(db.queryForObject("select fingerprint from agent_provider_key_state",String.class)).doesNotContain(key);
    }
    @Test void concurrentInitializersShareOneDurableKey() throws Exception {
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);var tasks=new ArrayList<Future<String>>();
            for(int i=0;i<8;i++) tasks.add(pool.submit(()->{start.await();return automatic().encrypt("same-value");}));
            start.countDown();var reader=automatic();
            for(var task:tasks) assertThat(reader.decrypt(task.get(5,TimeUnit.SECONDS))).isEqualTo("same-value");
        }
        assertThat(db.queryForObject("select count(*) from agent_provider_key_state",Long.class)).isEqualTo(1L);
    }
    @Test void lostOrCorruptFileNeverSilentlyReplacesOriginalKey() throws Exception {
        assertThat(automatic().available()).isTrue();String key=Files.readString(file);
        Files.delete(file);assertThat(automatic().available()).isFalse();assertThat(file).doesNotExist();
        Files.writeString(file,"corrupt");assertThat(automatic().available()).isFalse();assertThat(Files.readString(file)).isEqualTo("corrupt");
        Files.writeString(file,key);assertThat(automatic().available()).isTrue();
    }
    @Test void explicitKeyStillWorksAndCannotBeAccidentallyChanged() {
        String key=Base64.getEncoder().encodeToString(new byte[32]);
        var explicit=new ProviderSecrets(key,file.toString(),db);String ciphertext=explicit.encrypt("key");
        assertThat(new ProviderSecrets(key,file.toString(),db).decrypt(ciphertext)).isEqualTo("key");
        byte[] other=new byte[32];Arrays.fill(other,(byte)1);
        assertThat(new ProviderSecrets(Base64.getEncoder().encodeToString(other),file.toString(),db).available()).isFalse();
        assertThat(new ProviderSecrets("invalid",file.toString(),db).available()).isFalse();
        assertThat(file).doesNotExist();
    }
    @Test void secondInstallationWithSameDatabaseMustShareTheOriginalKey() throws Exception {
        assertThat(automatic().available()).isTrue();
        Path other=directory.resolve("other/provider.key");
        assertThat(new ProviderSecrets("",other.toString(),db).available()).isFalse();
        assertThat(other).doesNotExist();
    }
    @Test void initializationFailureCanRecoverWithoutLosingUserConfiguration() throws Exception {
        Files.writeString(directory.resolve("secrets"),"not-a-directory");
        var store=automatic();assertThat(store.available()).isFalse();
        Files.delete(directory.resolve("secrets"));assertThat(store.available()).isTrue();
    }
    @Test void existingCiphertextRequiresItsOriginalKeyBeforeRegisteringFingerprint() {
        String key=Base64.getEncoder().encodeToString(new byte[32]);
        String encrypted=new ProviderSecrets(key).encrypt("legacy-api-key");
        db.update("insert into agent_provider_config(org_id,display_name,protocol,base_url,model_id,api_key_ciphertext) values(1,'legacy','openai_responses','https://ai.example.com/v1','model',?)",encrypted);
        assertThat(automatic().available()).isFalse();assertThat(file).doesNotExist();
        byte[] wrong=new byte[32];Arrays.fill(wrong,(byte)2);
        assertThat(new ProviderSecrets(Base64.getEncoder().encodeToString(wrong),file.toString(),db).available()).isFalse();
        assertThat(db.queryForObject("select count(*) from agent_provider_key_state",Long.class)).isZero();
        var migrated=new ProviderSecrets(key,file.toString(),db);
        assertThat(migrated.available()).isTrue();assertThat(migrated.decrypt(encrypted)).isEqualTo("legacy-api-key");
    }
}
