package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.api.event.EventStreams;
import com.lawrence.travel.application.event.RunEventService;
import com.lawrence.travel.application.identity.IdentityService;
import com.lawrence.travel.application.port.RunEventStore;
import com.lawrence.travel.application.port.SubscriptionAccess;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.infrastructure.event.EventMapper;
import com.lawrence.travel.infrastructure.event.PostgresRunEventStore;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

/** 本地持久事件与会话认证装配；没有供应商调用或第二套业务调度。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class RunEventConfiguration {
    /**
     * @param mapper 只读 SQL
     * @param json JSON
     * @return 唯一持久事件读取
     */
    @Bean RunEventStore runEventStore(EventMapper mapper, TravelJson json) { return new PostgresRunEventStore(mapper, json); }
    /**
     * @param store 权威读取
     * @return 认证归属用例
     */
    @Bean RunEventService runEventService(RunEventStore store) { return new RunEventService(store); }
    /**
     * @param sessions Redis 权威会话
     * @param identity 当前账号
     * @return 不刷新过期时间的持续认证；任何依赖异常均关闭连接
     */
    @Bean SubscriptionAccess subscriptionAccess(SessionRepository<? extends Session> sessions, IdentityService identity) {
        return (id, user) -> {
            if (id == null || user == null) return false;
            try {
                var session = sessions.findById(id); if (session == null || session.isExpired()) return false;
                Object value = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                if (!(value instanceof SecurityContext context) || context.getAuthentication() == null || !context.getAuthentication().isAuthenticated()
                        || !(context.getAuthentication().getPrincipal() instanceof UserIdentity principal) || !principal.id().equals(user.id())) return false;
                return identity.current(user.id()).id().equals(user.id());
            } catch (RuntimeException unavailable) { return false; }
        };
    }
    /**
     * @param service 持久补读
     * @param access 会话撤销检查
     * @param heartbeat 可配置心跳，默认十五秒
     * @return 与业务执行隔离的有界传输
     */
    @Bean(destroyMethod = "close") EventStreams eventStreams(RunEventService service, SubscriptionAccess access,
            @Value("${travel.events.heartbeat:PT15S}") Duration heartbeat) { return new EventStreams(service, access, heartbeat); }
}
