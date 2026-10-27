package com.lawrence.travel.bootstrap;

import com.lawrence.travel.api.system.ProbeStreamController;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/** 仅借用 smoke 的数据库排除配置；未启用 smoke profile 时诊断启动入口必须消失。 */
@ActiveProfiles("boundary")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.import=classpath:application-smoke.yml")
class ProbeSecurityBoundaryTest {
    @LocalServerPort private int port;
    @Autowired private ApplicationContext context;

    /** 非 smoke 环境没有探针 Controller，匿名请求也不获得诊断白名单。 */
    @Test
    void probeCannotBeStartedOutsideSmokeProfile() throws Exception {
        assertThat(context.getBeansOfType(ProbeStreamController.class)).isEmpty();
        var response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        "http://127.0.0.1:"
                                                                + port
                                                                + "/api/v1/system/probe-events"))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(403);
    }
}
