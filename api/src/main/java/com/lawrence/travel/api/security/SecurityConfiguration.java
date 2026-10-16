package com.lawrence.travel.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** 非 local 的诊断安全链和空默认账号源；local 真实登录由身份安全配置装配。 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    /**
     * 装配诊断白名单和默认拒绝策略，并保留 CSRF 防护。
     *
     * @param http Spring 提供的安全构建器，不可为 null
     * @param environment 仅用于判断 smoke 诊断白名单
     * @return 非 local 模式下的安全过滤器链
     * @throws Exception 安全过滤器链无法构建时由 Spring 中止启动
     */
    @Bean
    @Profile("!local")
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment) throws Exception {
        // 采用显式诊断白名单；后续增加业务 Controller 时也不会被默认匿名放行。
        // 禁用默认认证入口并不关闭 CSRF，正式登录和退出契约在账号阶段实现。
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/api/v1/system/info").permitAll()
                        .requestMatchers(request -> environment.acceptsProfiles(Profiles.of("smoke"))
                                && request.getMethod().equals("GET")
                                && request.getServletPath().equals("/api/v1/system/probe-events")).permitAll()
                        .anyRequest().denyAll())
                .csrf(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .build();
    }

    /**
     * 提供空用户源，避免自动配置生成可登录账号或将默认密码输出到启动日志。
     *
     * @return 不含任何账号的内存用户源；local 的显式 AuthenticationManager 负责真实账号认证
     */
    @Bean
    UserDetailsService userDetailsService() {
        // 骨架阶段没有可登录账号；local 真实账号走独立认证管理器，此空源只防止自动生成默认账号。
        return new InMemoryUserDetailsManager();
    }
}
