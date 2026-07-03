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
                // Tolerate applied-but-missing migrations. Flyway's ignoreMigrationPatterns
                // only accepts a "type:status" format (type = repeatable|versioned|*,
                // status = missing|pending|ignored|future|*); it cannot target a specific
                // version, so we cannot narrow this to V84 alone.
                //
                // V84 is the SINGLE known-deleted version: it revoked the seeded "admin123"
                // SUPER_ADMIN and was intentionally removed from source (the sequence jumps
                // V83 -> V85). Its intent now lives durably in V113__remove_seeded_super_admin.sql,
                // which re-applies the revocation for databases that never ran V84. We do NOT
                // restore a V84 file, because its checksum would mismatch in environments that
                // already applied the original.
                //
                // Because this pattern is version-agnostic, ANY OTHER missing migration is a
                // bug (an accidental deletion, not an intentional one) and must be investigated
                // rather than silently tolerated. Only "missing" is ignored here —
                // checksum mismatches still fail validation.
                .ignoreMigrationPatterns("*:missing")
                .load();
    }
}
