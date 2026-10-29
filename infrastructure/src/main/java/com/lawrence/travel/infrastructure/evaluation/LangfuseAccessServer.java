package com.lawrence.travel.infrastructure.evaluation;

import com.lawrence.travel.application.evaluation.EvaluationPlatformAccess;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 仅供本机Docker访问闸门查询的控制端口；不接正文，不转发请求，不记录认证头。 */
public final class LangfuseAccessServer implements AutoCloseable {
    private final EvaluationPlatformAccess.Store store;
    private final Clock clock;
    private final byte[] token;
    private final HttpServer server;
    private final ExecutorService executor;

    /**
     * 关闭持久资格后创建控制服务，存储故障时不启动监听。
     *
     * @param store PG资格投影
     * @param clock 服务端时钟
     * @param address 控制端口，本地Docker需可访问宿主地址
     * @param token 64位本机随机共享令牌；不使用供应商或登录令牌
     * @throws IOException 端口创建失败
     * @throws IllegalArgumentException 配置不合法
     */
    public LangfuseAccessServer(
            EvaluationPlatformAccess.Store store,
            Clock clock,
            InetSocketAddress address,
            String token)
            throws IOException {
        if (address == null || token == null || !token.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("LANGFUSE_ACCESS_CONFIG_INVALID");
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        this.token = token.getBytes(StandardCharsets.US_ASCII);
        store.closeAccess();
        server = HttpServer.create(address, 16);
        executor =
                new ThreadPoolExecutor(
                        2,
                        2,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(16),
                        Thread.ofPlatform().daemon(true).factory(),
                        new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);
        server.createContext("/access", this::handle);
    }

    /** 开始接收控制请求；普通local配置不会创建本服务。 */
    public void start() {
        server.start();
    }

    /**
     * 获取实际控制端口，供随机端口本地测试使用。
     *
     * @return 实际绑定端口
     */
    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * 检查共享令牌和当前PG资格；异常只返回固定503，不输出底层错误。
     *
     * @param exchange 单个控制请求
     * @throws IOException 响应连接已断开
     */
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            String supplied = exchange.getRequestHeaders().getFirst("X-Evaluation-Gate");
            if (!"GET".equals(exchange.getRequestMethod())
                    || !"/access".equals(exchange.getRequestURI().toString())
                    || supplied == null
                    || supplied.length() != token.length
                    || !MessageDigest.isEqual(
                            token, supplied.getBytes(StandardCharsets.US_ASCII))) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            try {
                var decision = store.decision();
                if (decision != null && decision.allows(clock.instant())) {
                    exchange.getResponseHeaders()
                            .set(
                                    "X-Access-Not-After",
                                    Long.toString(decision.notAfter().toEpochMilli()));
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
            } catch (RuntimeException unavailable) {
                // PG断开或缺失台账时拒绝；控制接口不回显SQL、配置或异常正文。
            }
            exchange.sendResponseHeaders(503, -1);
        }
    }

    /** 关闭控制连接和线程；不删除台账，也不打开访问资格。 */
    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
