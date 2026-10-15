package com.lawrence.travel.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 唯一后端启动入口，扫描各模块组件，由 bootstrap 统一完成进程内装配。 */
@SpringBootApplication(scanBasePackages = "com.lawrence.travel")
public class TravelingAgentApplication {
    /**
     * 按命令行和环境配置启动 Spring 容器；不额外启动框架后台会话调度器。
     *
     * @param args Spring Boot 启动参数，不得包含真实密钥或密码
     */
    public static void main(String[] args) {
        SpringApplication.run(TravelingAgentApplication.class, args);
    }
}
