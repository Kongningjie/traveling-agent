package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.execution.ExecutionProbe;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/** 随机端口真实 MVC SSE 验证；仅本机离线任务，不访问 Docker 或外部服务。 */
@ActiveProfiles("smoke")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProbeStreamTest {
    @LocalServerPort private int port;
    @Autowired private ExecutionProbe probe;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /** 真实客户端收到开始帧后关闭响应流，后台仍完成；新连接可独立收到完整事件流。 */
    @Test
    void disconnectDoesNotCancelExecutionAndNewStreamWorks() throws Exception {
        long before = probe.completedCount();
        var first = client.send(request(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("Content-Type").orElse(""))
                .contains("text/event-stream");
        assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
        try (var reader =
                new BufferedReader(new InputStreamReader(first.body(), StandardCharsets.UTF_8))) {
            assertThat(reader.readLine()).isEqualTo("event:started");
            assertThat(reader.readLine()).isEqualTo("data:OFFLINE_PROBE_STARTED");
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (probe.completedCount() == before && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(probe.completedCount()).isEqualTo(before + 1);
        var reconnected = client.send(request(), HttpResponse.BodyHandlers.ofString());
        assertThat(reconnected.statusCode()).isEqualTo(200);
        assertThat(reconnected.body())
                .contains("event:started", "event:completed", "OFFLINE_PROBE_COMPLETED");
        assertThat(probe.completedCount()).isEqualTo(before + 2);
    }

    /** 两条连接占满时立即拒绝第三条，不产生无界后台任务。 */
    @Test
    void rejectsExcessSubscribers() throws Exception {
        var first = client.send(request(), HttpResponse.BodyHandlers.ofInputStream());
        var second = client.send(request(), HttpResponse.BodyHandlers.ofInputStream());
        try (var bodyOne = first.body();
                var bodyTwo = second.body()) {
            var third = client.send(request(), HttpResponse.BodyHandlers.ofString());
            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(second.statusCode()).isEqualTo(200);
            assertThat(third.statusCode()).isEqualTo(429);
            // 消费至结束，保证下个用例不继承仍在执行的诊断任务。
            bodyOne.readAllBytes();
            bodyTwo.readAllBytes();
        }
    }

    /**
     * @return 仅访问测试进程，超时后不自动重试或取消应用任务
     */
    private HttpRequest request() {
        return HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/system/probe-events"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
    }
}
