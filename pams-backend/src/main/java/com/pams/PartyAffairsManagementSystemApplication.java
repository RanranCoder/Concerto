package com.pams;

import com.pams.config.PostgresqlMigration;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class PartyAffairsManagementSystemApplication {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "migrate-postgresql".equals(args[0])) {
            PostgresqlMigration.run(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        SpringApplication.run(PartyAffairsManagementSystemApplication.class, args);
    }
}
