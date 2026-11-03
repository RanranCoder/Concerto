package com.pams.module.agent.testing;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Refuse a non-disposable schema before Spring creates its datasource. */
public final class BatchSixDatabase {
    private BatchSixDatabase(){}
    public static void configure(DynamicPropertyRegistry registry){
        if(!"true".equals(System.getenv("PAMS_S5_BATCH6")))return;
        String url=System.getenv("PAMS_S5_DB_URL"),user=System.getenv("PAMS_S5_DB_USER"),password=System.getenv("PAMS_S5_DB_PASSWORD");
        if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/pams_s5_batch6_[a-f0-9]{12}\\?.*")||user==null||!user.matches("pams_s6_[a-f0-9]{12}")||password==null||password.isBlank())throw new IllegalStateException("Batch 6 requires its own local disposable MySQL schema and restricted account");
        registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->user);registry.add("spring.datasource.password",()->password);
        registry.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");registry.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/migration-mysql");
    }
}
