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

@ActiveProfiles("smoke")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SmokeTest {
    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void startsWithoutExternalServicesAndWiresApplicationPortToHarnessAdapter() throws Exception {
        var response = get("/api/v1/system/info");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(response.body()).contains("\"mode\":\"smoke\"", "HARNESS_NOT_VALIDATED",
                "\"externalCallsEnabled\":false", "NOT_VERIFIED");
        var health = get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void doesNotExposePrivateRoutesOrSensitiveActuatorEndpoints() throws Exception {
        assertThat(get("/api/v1/travel-tasks").statusCode()).isEqualTo(403);
        assertThat(get("/actuator/env").statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
