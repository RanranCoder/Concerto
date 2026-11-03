package com.pams.module.agent.service;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Optional rerun of the same business tests against an explicitly configured isolated MySQL. */
final class AgentBatchFiveDatabase {
    private AgentBatchFiveDatabase(){}
    static void configure(DynamicPropertyRegistry registry){
        if(!"true".equals(System.getenv("PAMS_S5_BATCH5")))return;
        String url=System.getenv("PAMS_S5_DB_URL"),user=System.getenv("PAMS_S5_DB_USER"),password=System.getenv("PAMS_S5_DB_PASSWORD");
        if(url==null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/pams_s5_[A-Za-z0-9_]+\\?.*") || user==null || password==null)throw new IllegalStateException("Batch 5 acceptance requires explicit isolated pams_s5_* MySQL configuration on loopback");
        registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->user);registry.add("spring.datasource.password",()->password);
        registry.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");registry.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/migration-mysql");
    }
}
