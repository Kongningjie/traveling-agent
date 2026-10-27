package com.lawrence.travel.infrastructure.tools;

import com.lawrence.travel.application.port.TravelToolPort;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;

/** 固定高德与 Tavily API 的最小 HTTP 适配；无任意 URL 抓取、重定向或隐式重试。 */
public final class TravelHttpTools implements TravelToolPort {
    private final Supplier<String> amapKey;
    private final Supplier<String> tavilyKey;
    private final Set<String> evidenceHosts;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private long lastStarted;

    /**
     * 按需读取服务凭证，不在构造或日志中展开。
     * @param amapKey 高德 Web 服务凭证
     * @param tavilyKey Tavily 凭证
     * @param evidenceHosts 已筛选官方原文域名精确白名单，不自动接受搜索结果的新域名
     */
    public TravelHttpTools(
            Supplier<String> amapKey, Supplier<String> tavilyKey, Set<String> evidenceHosts) {
        this.amapKey = amapKey;
        this.tavilyKey = tavilyKey;
        this.evidenceHosts = Set.copyOf(evidenceHosts);
    }

    /** {@inheritDoc} 单实例串行限速，所有失败只暴露固定分类。 */
    @Override
    public synchronized Evidence query(Operation operation, Map<String, String> arguments) {
        String secret =
                (operation == Operation.SEARCH || operation == Operation.EXTRACT
                                ? tavilyKey
                                : amapKey)
                        .get();
        if (secret == null || secret.isBlank())
            throw new IllegalStateException("TOOL_CREDENTIAL_MISSING");
        String source;
        HttpRequest request;
        if (operation == Operation.SEARCH || operation == Operation.EXTRACT) {
            source =
                    "https://api.tavily.com/"
                            + (operation == Operation.SEARCH ? "search" : "extract");
            Map<String, Object> body =
                    operation == Operation.SEARCH
                            ? Map.of(
                                    "query",
                                    text(arguments, "query"),
                                    "search_depth",
                                    "basic",
                                    "max_results",
                                    3,
                                    "auto_parameters",
                                    false,
                                    "include_answer",
                                    false,
                                    "include_raw_content",
                                    false,
                                    "include_usage",
                                    true)
                            : Map.of(
                                    "urls",
                                    List.of(requireEvidenceUrl(text(arguments, "url")).toString()),
                                    "extract_depth",
                                    "basic",
                                    "include_images",
                                    false,
                                    "include_usage",
                                    true);
            request =
                    HttpRequest.newBuilder(URI.create(source))
                            .header("Authorization", "Bearer " + secret)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            json.writeValueAsString(body)))
                            .timeout(Duration.ofSeconds(25))
                            .build();
        } else {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("key", secret);
            query.put("output", "JSON");
            String path;
            switch (operation) {
                case PLACE -> {
                    path = "/v3/place/text";
                    query.put("keywords", text(arguments, "keywords"));
                    query.put("city", text(arguments, "city"));
                    query.put("citylimit", "true");
                    query.put("offset", "3");
                    query.put("extensions", "base");
                }
                case WEATHER -> {
                    path = "/v3/weather/weatherInfo";
                    query.put("city", adcode(arguments, "city"));
                    query.put("extensions", "all");
                    date(arguments);
                }
                case ROUTE -> {
                    String mode = text(arguments, "mode");
                    path =
                            switch (mode) {
                                case "walking" -> "/v5/direction/walking";
                                case "driving" -> "/v5/direction/driving";
                                case "transit" -> "/v5/direction/transit/integrated";
                                default ->
                                        throw new IllegalArgumentException(
                                                "TOOL_ROUTE_MODE_INVALID");
                            };
                    query.put("origin", coordinate(arguments, "origin"));
                    query.put("destination", coordinate(arguments, "destination"));
                    query.put("show_fields", "cost");
                    if (mode.equals("transit")) {
                        query.put("city1", adcode(arguments, "city"));
                        query.put("city2", adcode(arguments, "destinationCity"));
                    }
                    date(arguments);
                }
                default -> throw new IllegalArgumentException("TOOL_OPERATION_INVALID");
            }
            source = "https://restapi.amap.com" + path;
            String encoded =
                    query.entrySet().stream()
                            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                            .collect(java.util.stream.Collectors.joining("&"));
            request =
                    HttpRequest.newBuilder(URI.create(source + "?" + encoded))
                            .GET()
                            .timeout(Duration.ofSeconds(25))
                            .build();
        }
        Map<String, Object> data = send(request, secret);
        if (operation != Operation.SEARCH
                && operation != Operation.EXTRACT
                && !"1".equals(data.get("status"))) {
            throw new IllegalStateException("TOOL_AMAP_REJECTED");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", operation.name());
        payload.put("data", data);
        payload.put("status", "AVAILABLE");
        if (operation == Operation.PLACE && !hasItems(data.get("pois")))
            payload.put("status", "UNKNOWN_NO_RESULTS");
        if (operation == Operation.WEATHER) {
            String date = arguments.get("date");
            payload.put("targetDate", date);
            Object forecast = forecastFor(data, date);
            payload.put("forecast", forecast == null ? Map.of() : forecast);
            if (forecast == null) payload.put("status", "UNKNOWN_OUTSIDE_RETURNED_FORECAST");
        }
        if (operation == Operation.ROUTE) {
            if (!hasRoute(data)) payload.put("status", "UNKNOWN_NO_ROUTE");
            payload.put("estimate", true);
            payload.put("targetDate", arguments.get("date"));
            payload.put("coordinateSystem", "GCJ-02");
        }
        if (operation == Operation.SEARCH) {
            payload.put("factStatus", "SEARCH_LEAD_NOT_VERIFIED");
            if (!hasItems(data.get("results"))) payload.put("status", "UNKNOWN_NO_RESULTS");
        }
        if (operation == Operation.EXTRACT) {
            payload.put("factStatus", "UNTRUSTED_SOURCE_TEXT");
            source = requireEvidenceUrl(arguments.get("url")).toString();
            if (!(data.get("results") instanceof List<?> results) || results.isEmpty()) {
                payload.put("status", "UNKNOWN_EXTRACTION_FAILED");
            }
        }
        return new Evidence(URI.create(source), Instant.now(), json.writeValueAsString(payload));
    }

    /**
     * 区分 API 成功响应与实际存在可用路线，公交空结果不能伪装为规划成功。
     * @param data 供应商对象
     * @return 是否有路径或换乘方案
     */
    public static boolean hasRoute(Map<String, Object> data) {
        return data.get("route") instanceof Map<?, ?> route
                && (hasItems(route.get("paths")) || hasItems(route.get("transits")));
    }

    /**
     * 空数组或供应商空串都视为没有候选。
     * @param value 候选集合
     * @return 是否至少一条
     */
    private static boolean hasItems(Object value) {
        return value instanceof List<?> list && !list.isEmpty();
    }

    /**
     * 限制原文地址为已筛选 HTTPS 官方主机，不允许凭证、IP、端口、查询或片段。
     * @param value 原文地址
     * @return 安全筛选后的 URL；交由固定 Tavily API 提取，本机不直连该地址
     */
    public URI requireEvidenceUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme())
                    || uri.getHost() == null
                    || !evidenceHosts.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))
                    || uri.getUserInfo() != null
                    || uri.getPort() != -1
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("TOOL_EVIDENCE_URL_REJECTED");
            }
            return uri;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("TOOL_EVIDENCE_URL_REJECTED");
        }
    }

    /**
     * 串行请求至少间隔 400ms，不输出可能带高德 Key 的 URL 或底层异常。
     * @param request 完整请求，仅在内存使用
     * @param secret 从供应商意外回显中移除的凭证
     * @return 解析后的供应商对象
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> send(HttpRequest request, String secret) {
        try {
            long remaining = 400_000_000L - (System.nanoTime() - lastStarted);
            if (remaining > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
            lastStarted = System.nanoTime();
            var response =
                    client.send(
                            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200)
                throw new IllegalStateException("TOOL_HTTP_" + response.statusCode());
            if (response.body().length() > 1_000_000)
                throw new IllegalStateException("TOOL_RESPONSE_TOO_LARGE");
            return json.readValue(response.body().replace(secret, "[REDACTED]"), Map.class);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TOOL_INTERRUPTED");
        } catch (IllegalStateException classified) {
            if (classified.getMessage() != null
                    && classified.getMessage().matches("TOOL_(HTTP_[0-9]{3}|RESPONSE_TOO_LARGE)"))
                throw classified;
            throw new IllegalStateException("TOOL_RESPONSE_INVALID");
        } catch (Exception failure) {
            throw new IllegalStateException("TOOL_REQUEST_FAILED");
        }
    }

    /**
     * 按实际返回日期匹配，不能推测固定预报天数或凭模型补天气。
     * @param data 供应商预报
     * @param date 目标日期
     * @return 匹配预报，缺失为 null
     */
    public static Object forecastFor(Map<String, Object> data, String date) {
        if (data.get("forecasts") instanceof List<?> forecasts) {
            for (Object item : forecasts) {
                if (item instanceof Map<?, ?> forecast
                        && forecast.get("casts") instanceof List<?> casts) {
                    for (Object cast : casts)
                        if (cast instanceof Map<?, ?> fields && date.equals(fields.get("date")))
                            return cast;
                }
            }
        }
        return null;
    }

    /**
     * 读取有界必填文本，不回显错误输入。
     * @param values 参数表
     * @param name 字段名
     * @return 非空文本
     */
    private static String text(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank() || value.length() > 1000)
            throw new IllegalArgumentException("TOOL_ARGUMENT_INVALID");
        return value;
    }

    /**
     * 校验高德行政编码。
     * @param values 参数表
     * @param name 字段名
     * @return 六位编码
     */
    private static String adcode(Map<String, String> values, String name) {
        String value = text(values, name);
        if (!value.matches("[0-9]{6}")) throw new IllegalArgumentException("TOOL_CITY_INVALID");
        return value;
    }

    /**
     * 校验日期并丢弃可能回显用户输入的解析异常。
     * @param values 工具参数
     */
    private static void date(Map<String, String> values) {
        try {
            LocalDate.parse(text(values, "date"));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("TOOL_DATE_INVALID");
        }
    }

    /**
     * 校验 GCJ-02 经纬度格式与地理范围。
     * @param values 参数表
     * @param name 字段名
     * @return 合法坐标
     */
    private static String coordinate(Map<String, String> values, String name) {
        String value = text(values, name);
        if (!value.matches("-?[0-9]{1,3}(\\.[0-9]{1,8})?,-?[0-9]{1,2}(\\.[0-9]{1,8})?"))
            throw new IllegalArgumentException("TOOL_COORDINATE_INVALID");
        String[] parts = value.split(",");
        if (Math.abs(Double.parseDouble(parts[0])) > 180
                || Math.abs(Double.parseDouble(parts[1])) > 90)
            throw new IllegalArgumentException("TOOL_COORDINATE_INVALID");
        return value;
    }

    /**
     * URL 参数编码，只用于内存请求，不记录带密钥地址。
     * @param value 参数值
     * @return UTF-8 编码值
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
