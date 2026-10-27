package com.lawrence.travel.bootstrap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** 用真实随机端口验证离线装配及 HTTP 安全边界，不依赖容器或供应商账号。 */
@ActiveProfiles("smoke")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SmokeTest {
    @LocalServerPort private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    /**
     * 验证 HTTP 到应用端口及 Harness 适配器的装配，并保留未验证能力的显式标识。
     * @throws Exception 本机 HTTP 请求失败或被中断时使测试失败
     */
    @Test
    void startsWithoutExternalServicesAndWiresApplicationPortToHarnessAdapter() throws Exception {
        var response = get("/api/v1/system/info");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("application/json");
        assertThat(response.body())
                .contains(
                        "\"mode\":\"smoke\"",
                        "HARNESS_NOT_VALIDATED",
                        "\"externalCallsEnabled\":false",
                        "NOT_VERIFIED");
        var health = get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }

    /**
     * 验证未知私有 API 与敏感诊断路径仍受默认拒绝策略保护。
     * @throws Exception 本机 HTTP 请求失败或被中断时使测试失败
     */
    @Test
    void doesNotExposePrivateRoutesOrSensitiveActuatorEndpoints() throws Exception {
        assertThat(get("/api/v1/travel-tasks").statusCode()).isEqualTo(403);
        assertThat(get("/actuator/env").statusCode()).isEqualTo(403);
    }

    /**
     * 向本测试创建的本机端口发送 GET，不携带凭证也不访问外部服务。
     * @param path 以斜杠开头、由测试固定指定的 HTTP 路径
     * @return 原始状态、响应头和文本响应体，供具体测试断言
     * @throws Exception HTTP 传输失败或线程被中断
     */
    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
