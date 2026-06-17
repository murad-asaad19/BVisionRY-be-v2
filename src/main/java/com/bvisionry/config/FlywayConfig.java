package com.bvisionry.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class FlywayConfig {

    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                // Tolerate migrations that were applied to the DB but have since been
                // intentionally removed from source (e.g. V84). Without this, validate
                // fails with "applied migration not resolved locally" and the app won't
                // boot. Only "missing" is ignored — checksum mismatches still fail.
                .ignoreMigrationPatterns("*:missing")
                .load();
    }
}
