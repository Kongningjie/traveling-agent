package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.application.lifecycle.LifecycleService;
import com.lawrence.travel.application.port.ArtifactFiles;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.DeletionLedger;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.JobStore;
import com.lawrence.travel.application.port.LifecycleStore;
import com.lawrence.travel.application.port.StorageAdmission;
import com.lawrence.travel.infrastructure.artifact.VolumeArtifactFiles;
import com.lawrence.travel.infrastructure.lifecycle.DiskAdmission;
import com.lawrence.travel.infrastructure.lifecycle.FileDeletionLedger;
import com.lawrence.travel.infrastructure.lifecycle.LifecycleMapper;
import com.lawrence.travel.infrastructure.lifecycle.LocalBackupRetention;
import com.lawrence.travel.infrastructure.lifecycle.PostgresLifecycleStore;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.time.Clock;

/** 生命周期唯一装配，恢复屏障先于Worker和私有服务；清单在业务备份之外独立保留。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@MapperScan(basePackageClasses = LifecycleMapper.class)
public class LifecycleConfiguration {
    /**
     * @param e 服务器受信卷根
     * @return 全局容量滞回，所有执行器复用同一实例
     */
    @Bean
    StorageAdmission storageAdmission(Environment e) {
        var guard =
                DiskAdmission.volumes(
                        Path.of(e.getProperty("travel.artifacts.root", ".runtime/artifacts")),
                        Path.of(e.getProperty("travel.documents.root", ".runtime/documents")));
        return guard::accepting;
    }

    /**
     * @param mapper 明确SQL
     * @return PG权威
     */
    @Bean
    LifecycleStore lifecycleStore(LifecycleMapper mapper) {
        return new PostgresLifecycleStore(mapper);
    }

    /**
     * @param e 服务端受限目录
     * @return 最小删除清单
     */
    @Bean
    DeletionLedger deletionLedger(Environment e) {
        return new FileDeletionLedger(
                Path.of(e.getProperty("travel.deletions.root", ".runtime/lifecycle")));
    }

    /**
     * @param store 墓碑
     * @param tx 事务
     * @param ledger 独立头
     * @param artifacts 既有文件卷
     * @param jobs 原取消
     * @param engine 原阶段引擎
     * @param e 原件根
     * @return 删除用例
     */
    @Bean
    LifecycleService lifecycleService(
            LifecycleStore store,
            BusinessTransactions tx,
            DeletionLedger ledger,
            ArtifactFiles artifacts,
            JobStore jobs,
            ExecutionEngineStore engine,
            Environment e) {
        return new LifecycleService(
                store,
                tx,
                ledger,
                new VolumeArtifactFiles(
                        Path.of(e.getProperty("travel.documents.root", ".runtime/documents"))),
                artifacts,
                jobs,
                engine,
                Clock.systemUTC());
    }

    /**
     * @param service 当前PG与独立清单
     * @return Flyway完成后才重放，失败中止启动；不带旧数据启动后再修复
     */
    @Bean
    @org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
    org.springframework.beans.factory.InitializingBean lifecycleReady(LifecycleService service) {
        return service::recover;
    }

    /**
     * @param service 有界清理
     * @param e 测试可关闭自动维护
     * @param json 有界备份清单解析
     * @param diagnostics 固定维护问题分类
     * @return 固定清理时钟，不驱动Agent
     */
    @Bean
    Maintenance lifecycleMaintenance(
            LifecycleService service,
            Environment e,
            InteractionCodec json,
            com.lawrence.travel.infrastructure.execution.jobs.DiagnosticMapper diagnostics) {
        return new Maintenance(
                service,
                new LocalBackupRetention(
                        Path.of(e.getProperty("travel.backups.root", ".runtime/backups")), json),
                e.getProperty("travel.lifecycle.maintenance-enabled", Boolean.class, true),
                diagnostics);
    }

    /** 清理失败仅固定错误，不输出原始路径、SQL或正文；下一周期继续。 */
    public static final class Maintenance {
        private final LifecycleService service;
        private final LocalBackupRetention backups;
        private final boolean enabled;
        private final com.lawrence.travel.infrastructure.execution.jobs.DiagnosticMapper
                diagnostics;

        /**
         * @param service 有界周期
         * @param backups 受管备份到期清理
         * @param diagnostics 固定故障记录
         * @param enabled 是否自动维护
         */
        Maintenance(
                LifecycleService service,
                LocalBackupRetention backups,
                boolean enabled,
                com.lawrence.travel.infrastructure.execution.jobs.DiagnosticMapper diagnostics) {
            this.service = service;
            this.backups = backups;
            this.enabled = enabled;
            this.diagnostics = diagnostics;
        }

        /** 每分钟最多30个原件和各1000条过期诊断；停止不经过此队列。 */
        @org.springframework.scheduling.annotation.Scheduled(
                fixedDelay = 60000,
                initialDelay = 60000)
        public void run() {
            if (enabled)
                try {
                    service.maintain();
                } catch (RuntimeException failure) {
                    problem("LIFECYCLE_MAINTENANCE_UNAVAILABLE");
                    System.getLogger(getClass().getName())
                            .log(System.Logger.Level.WARNING, "LIFECYCLE_MAINTENANCE_UNAVAILABLE");
                }
            if (enabled)
                try {
                    backups.prune(java.time.Instant.now());
                } catch (RuntimeException failure) {
                    problem("BACKUP_RETENTION_UNAVAILABLE");
                    System.getLogger(getClass().getName())
                            .log(System.Logger.Level.WARNING, "BACKUP_RETENTION_UNAVAILABLE");
                }
        }

        /**
         * @param code 固定维护分类；诊断失败不得覆盖清理状态，绝不传递原始异常或真实路径
         */
        private void problem(String code) {
            try {
                diagnostics.problem(
                        java.util.UUID.randomUUID(), null, null, null, null, "LIFECYCLE", code);
            } catch (RuntimeException ignored) {
                /* 保留固定stderr告警，下一分钟继续重试维护。 */
            }
        }
    }
}
