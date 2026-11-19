package com.leyon.lumin.startup;

import jakarta.annotation.PostConstruct;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 启动迁移门禁（Spring Boot 4 未提供 Flyway 自动配置时的显式装配）：
 * 生产/本地运行在数据源可用后按 db/migration 顺序执行并校验版本；
 * 测试库由 TestDatabase 重建，故 application-test 将 spring.flyway.enabled 置 false。
 */
@Component
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class FlywayMigrationRunner {

    private final DataSource dataSource;
    private final boolean baselineOnMigrate;

    public FlywayMigrationRunner(DataSource dataSource,
                                 @Value("${spring.flyway.baseline-on-migrate:false}") boolean baselineOnMigrate) {
        this.dataSource = dataSource;
        this.baselineOnMigrate = baselineOnMigrate;
    }

    @PostConstruct
    public void migrate() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(baselineOnMigrate)
                .validateOnMigrate(true)
                .load()
                .migrate();
    }
}
