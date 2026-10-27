package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.document.Documents;
import com.lawrence.travel.application.document.DocumentService;
import com.lawrence.travel.infrastructure.document.ParserRunner;
import com.lawrence.travel.infrastructure.document.HttpParserProcess;
import com.lawrence.travel.infrastructure.artifact.VolumeArtifactFiles;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

/** 真实Docker隔离测试，只有显式本地开关才运行，不读取任何真实API凭据。 */
@EnabledIfEnvironmentVariable(named = "DOCUMENT_CONTAINER_TESTS", matches = "true")
class DocumentContainerTest {
    private static final String SECRET = "synthetic-parser-test-authentication-only-0000";
    @TempDir Path root;

    /**
     * @param file 合成原件
     * @return 固定受管键的签名请求数据
     */
    private Documents.Request request(Path file) throws Exception {
        String key =
                "documents/"
                        + UUID.randomUUID()
                        + "/"
                        + UUID.randomUUID()
                        + "/"
                        + UUID.randomUUID()
                        + ".bin";
        byte[] bytes = Files.readAllBytes(file);
        new VolumeArtifactFiles(root).write(key, bytes);
        return new Documents.Request(
                UUID.randomUUID(),
                1,
                key,
                file.toString().substring(file.toString().lastIndexOf('.') + 1),
                DocumentService.hash(bytes),
                UUID.randomUUID(),
                Instant.now().getEpochSecond());
    }

    /** 四类各三份通过认证HTTP和真实隔离容器，不把直调解析器当隔离通过。 */
    @Test
    void twelveFixturesRunInIsolatedContainers() throws Exception {
        var runner = new ParserRunner(root, System.getenv("DOCUMENT_PARSER_IMAGE"), SECRET);
        var server = runner.start("127.0.0.1", 8766);
        try {
            var client =
                    new HttpParserProcess(
                            java.net.URI.create("http://127.0.0.1:8766"),
                            () -> SECRET,
                            new TravelJson());
            for (var fixture : DocumentFixtures.valid(root)) {
                var report = client.parse(request(fixture));
                assertThat(report.code()).as(fixture.getFileName().toString()).isEqualTo("OK");
                assertThat(report.sections()).isNotEmpty();
            }
        } finally {
            server.stop(0);
        }
    }

    /** 无签名、重放及路径逃逸被拒绝；临时目录之外没有解析挂载。 */
    @Test
    void authenticationReplayAndPathEscapeReject() throws Exception {
        var runner = new ParserRunner(root, System.getenv("DOCUMENT_PARSER_IMAGE"), SECRET);
        var server = runner.start("127.0.0.1", 8766);
        var client = java.net.http.HttpClient.newHttpClient();
        var json = new TravelJson();
        try {
            var malformed =
                    new Documents.Request(
                            null,
                            0,
                            "../../outside",
                            "txt",
                            "0".repeat(64),
                            UUID.randomUUID(),
                            Instant.now().getEpochSecond());
            String badBody = json.encode(malformed);
            var bad =
                    java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create("http://127.0.0.1:8766/parse"))
                            .header("X-Parser-Boot", runner.boot().toString())
                            .header(
                                    "X-Parser-Signature",
                                    ParserRunner.sign("/parse\n" + runner.boot(), badBody, SECRET))
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(badBody))
                            .build();
            assertThat(
                            client.send(bad, java.net.http.HttpResponse.BodyHandlers.discarding())
                                    .statusCode())
                    .isEqualTo(403);
            Path good = root.resolve("after-invalid.txt");
            Files.writeString(good, "合法旅行文本");
            assertThat(
                            new HttpParserProcess(
                                            java.net.URI.create("http://127.0.0.1:8766"),
                                            () -> SECRET,
                                            json)
                                    .parse(request(good))
                                    .code())
                    .isEqualTo("OK");
            var r =
                    new Documents.Request(
                            UUID.randomUUID(),
                            1,
                            "../../outside",
                            "txt",
                            "0".repeat(64),
                            UUID.randomUUID(),
                            Instant.now().getEpochSecond());
            String body = json.encode(r);
            var unsigned =
                    java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create("http://127.0.0.1:8766/parse"))
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                            .build();
            assertThat(
                            client.send(
                                            unsigned,
                                            java.net.http.HttpResponse.BodyHandlers.discarding())
                                    .statusCode())
                    .isEqualTo(403);
            var signed =
                    java.net.http.HttpRequest.newBuilder(unsigned.uri())
                            .header("X-Parser-Boot", runner.boot().toString())
                            .header(
                                    "X-Parser-Signature",
                                    ParserRunner.sign("/parse\n" + runner.boot(), body, SECRET))
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                            .build();
            var first = client.send(signed, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(json.decode(first.body(), Documents.Report.class).code()).isNotEqualTo("OK");
            assertThat(
                            client.send(
                                            signed,
                                            java.net.http.HttpResponse.BodyHandlers.discarding())
                                    .statusCode())
                    .isEqualTo(403);
            server.stop(0);
            var restarted =
                    new ParserRunner(root, System.getenv("DOCUMENT_PARSER_IMAGE"), SECRET)
                            .start("127.0.0.1", 8766);
            try {
                assertThat(
                                client.send(
                                                signed,
                                                java.net.http.HttpResponse.BodyHandlers
                                                        .discarding())
                                        .statusCode())
                        .isEqualTo(403);
            } finally {
                restarted.stop(0);
            }
        } finally {
            server.stop(0);
        }
    }

    /** 真实运行时核对网络、只读、用户、CPU／内存／PID、cap和提权限制，不能仅查看模板字符串。 */
    @Test
    void liveContainerHasEnforcedRestrictions() throws Exception {
        Path pdf = root.resolve("many.pdf");
        DocumentFixtures.pdf(pdf, 100);
        var r = request(pdf);
        var runner = new ParserRunner(root, System.getenv("DOCUMENT_PARSER_IMAGE"), SECRET);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> runner.run(r));
            String config = "";
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (!future.isDone() && System.nanoTime() < deadline) {
                var process =
                        new ProcessBuilder(
                                        "docker",
                                        "inspect",
                                        "--format",
                                        "{{.HostConfig.NetworkMode}}|{{.HostConfig.ReadonlyRootfs}}|{{.HostConfig.Memory}}|{{.HostConfig.NanoCpus}}|{{.HostConfig.PidsLimit}}|{{.Config.User}}|{{json .HostConfig.CapDrop}}|{{json .HostConfig.SecurityOpt}}|{{range .Mounts}}{{.Destination}}:{{.RW}};{{end}}|{{json .HostConfig.Tmpfs}}",
                                        "travel-parser-" + r.job() + "-1")
                                .redirectError(ProcessBuilder.Redirect.DISCARD)
                                .start();
                String line =
                        new String(
                                process.getInputStream().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                process.waitFor();
                if (line.contains("none|true")) {
                    config = line;
                    break;
                }
                Thread.sleep(50);
            }
            assertThat(config)
                    .contains(
                            "none|true|536870912|1000000000|64|65532:65532",
                            "ALL",
                            "no-new-privileges",
                            "/input/document.bin:false",
                            "size=64m");
            assertThat(future.get().code()).isEqualTo("OK");
        }
    }

    /** 同一生产墙钟算法收紧到1毫秒，真实Docker启动被终止；没有遗留容器，不能释放后台仍在运行的槽。 */
    @Test
    void wallClockTimeoutTerminatesContainer() throws Exception {
        Path text = root.resolve("slow.txt");
        Files.writeString(text, "旅行文本");
        var r = request(text);
        var runner =
                new ParserRunner(
                        root,
                        System.getenv("DOCUMENT_PARSER_IMAGE"),
                        SECRET,
                        java.time.Duration.ofMillis(1));
        assertThat(runner.run(r).code()).isEqualTo("DOCUMENT_PARSE_TIMEOUT");
        var process =
                new ProcessBuilder(
                                "docker",
                                "ps",
                                "-aq",
                                "--filter",
                                "name=travel-parser-" + r.job() + "-1")
                        .start();
        assertThat(
                        new String(
                                        process.getInputStream().readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8)
                                .trim())
                .isEmpty();
        assertThat(process.waitFor()).isZero();
    }

    /** runner内存丢失后启动必须先清理专用标签的旧容器，不能把物理槽清零后放大并发。 */
    @Test
    void runnerRestartRemovesOrphanContainerBeforeAcceptingWork() throws Exception {
        String name = "travel-parser-" + UUID.randomUUID() + "-1";
        var old =
                new ProcessBuilder(
                                "docker",
                                "run",
                                "-d",
                                "--name",
                                name,
                                "--label=traveling-agent-parser=true",
                                "--network=none",
                                "--read-only",
                                "--user=65532:65532",
                                "--cpus=1",
                                "--memory=512m",
                                "--pids-limit=64",
                                "--entrypoint=sleep",
                                System.getenv("DOCUMENT_PARSER_IMAGE"),
                                "120")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        assertThat(old.waitFor()).isZero();
        com.sun.net.httpserver.HttpServer server = null;
        try {
            server =
                    new ParserRunner(root, System.getenv("DOCUMENT_PARSER_IMAGE"), SECRET)
                            .start("127.0.0.1", 8766);
            var check =
                    new ProcessBuilder("docker", "ps", "-aq", "--filter", "name=^" + name + "$")
                            .start();
            assertThat(
                            new String(
                                            check.getInputStream().readAllBytes(),
                                            java.nio.charset.StandardCharsets.US_ASCII)
                                    .trim())
                    .isEmpty();
            assertThat(check.waitFor()).isZero();
        } finally {
            if (server != null) server.stop(0);
            var cleanup =
                    new ProcessBuilder("docker", "rm", "-f", name)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            cleanup.waitFor();
        }
    }

    /** 取消HTTP客户端工作线程后，等实际容器终止才完成调用；不把Future中断当成物理结束。 */
    @Test
    void clientCancellationWaitsForContainerExit() throws Exception {
        Path pdf = root.resolve("cancel.pdf");
        DocumentFixtures.pdf(pdf, 100);
        var r = request(pdf);
        var runner = new ParserRunner(root, System.getenv("DOCUMENT_PARSER_IMAGE"), SECRET);
        var server = runner.start("127.0.0.1", 8766);
        try {
            var client =
                    new HttpParserProcess(
                            java.net.URI.create("http://127.0.0.1:8766"),
                            () -> SECRET,
                            new TravelJson());
            var result = new java.util.concurrent.CompletableFuture<Documents.Report>();
            var worker =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            result.complete(client.parse(r));
                                        } catch (RuntimeException failure) {
                                            result.completeExceptionally(failure);
                                        }
                                    });
            String name = "travel-parser-" + r.job() + "-1";
            boolean seen = false;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (!result.isDone() && System.nanoTime() < deadline) {
                var check =
                        new ProcessBuilder("docker", "ps", "-aq", "--filter", "name=^" + name + "$")
                                .start();
                String ids =
                        new String(
                                check.getInputStream().readAllBytes(),
                                java.nio.charset.StandardCharsets.US_ASCII);
                check.waitFor();
                if (!ids.isBlank()) {
                    seen = true;
                    break;
                }
                Thread.sleep(30);
            }
            assertThat(seen).isTrue();
            worker.interrupt();
            assertThat(result.get(20, java.util.concurrent.TimeUnit.SECONDS).code())
                    .isNotEqualTo("OK");
            worker.join();
            var check =
                    new ProcessBuilder("docker", "ps", "-aq", "--filter", "name=^" + name + "$")
                            .start();
            assertThat(
                            new String(
                                            check.getInputStream().readAllBytes(),
                                            java.nio.charset.StandardCharsets.US_ASCII)
                                    .trim())
                    .isEmpty();
            check.waitFor();
        } finally {
            server.stop(0);
        }
    }
}
