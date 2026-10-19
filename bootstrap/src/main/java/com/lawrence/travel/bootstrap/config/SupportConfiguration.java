package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.application.document.SourceReadService;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.SupportStore;
import com.lawrence.travel.application.safety.SafetyGate;
import com.lawrence.travel.application.support.DiagnosticBuffer;
import com.lawrence.travel.application.support.SupportService;
import com.lawrence.travel.infrastructure.support.PostgresSupportStore;
import com.lawrence.travel.infrastructure.support.SupportMapper;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;

/** 反馈与维护唯一装配；默认无维护授权，不创建第二套业务调度。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@MapperScan(basePackageClasses = SupportMapper.class)
public class SupportConfiguration {
    /**
     * @param sql 固定SQL
     * @return PG端口
     */
    @Bean
    SupportStore supportStore(SupportMapper sql) {
        return new PostgresSupportStore(sql);
    }

    /**
     * @param store 端口
     * @param tx 短事务
     * @return 最多128条固定诊断
     */
    @Bean
    DiagnosticBuffer diagnosticBuffer(SupportStore store, BusinessTransactions tx) {
        return new DiagnosticBuffer(store, tx);
    }

    /**
     * @param store 权威存储
     * @param tx 事务
     * @param json JSON
     * @param safety 安全
     * @param sources 引用 @param buffer 降级
     * @return 反馈用例
     */
    @Bean
    SupportService supportService(
            SupportStore store,
            BusinessTransactions tx,
            InteractionCodec json,
            SafetyGate safety,
            SourceReadService sources,
            DiagnosticBuffer buffer) {
        return new SupportService(store, tx, json, safety, sources, buffer);
    }

    /**
     * @param buffer 固定故障队列
     * @return 仅诊断补写计时器
     */
    @Bean
    FlushSupport flushSupport(DiagnosticBuffer buffer) {
        return new FlushSupport(buffer);
    }

    /** 固定诊断补写不驱动Agent或业务恢复。 */
    public static final class FlushSupport {
        private final DiagnosticBuffer buffer;

        /**
         * @param buffer 有界队列
         */
        FlushSupport(DiagnosticBuffer buffer) {
            this.buffer = buffer;
        }

        /** 每5秒至多8条，应用停机可丢失这部分降级诊断。 */
        @Scheduled(fixedDelay = 5000)
        public void flush() {
            buffer.flush();
        }
    }
}
