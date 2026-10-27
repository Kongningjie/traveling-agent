package com.lawrence.travel.application.knowledge;

import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.TravelToolPort;
import com.lawrence.travel.domain.knowledge.EvidenceRecord;
import com.lawrence.travel.domain.knowledge.FieldEvidence;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 字段来源、日期适用及缓存规则；模型不能延长 TTL 或把搜索摘要提升为事实。 */
public final class EvidencePolicy {
    private final InteractionCodec json;
    private final Set<String> officialHosts;
    private final Map<TravelToolPort.Operation, Duration> cacheTtls;

    /**
     * @param json 规范 JSON
     * @param officialHosts 人工筛选的精确官方主机白名单
     */
    public EvidencePolicy(InteractionCodec json, Set<String> officialHosts) {
        this(json, officialHosts, Map.of());
    }

    /**
     * @param json 规范 JSON
     * @param officialHosts 明确官方主机
     * @param overrides 正的刷新间隔覆盖，默认跟随冻结初始策略
     */
    public EvidencePolicy(
            InteractionCodec json,
            Set<String> officialHosts,
            Map<TravelToolPort.Operation, Duration> overrides) {
        this.json = json;
        this.officialHosts = Set.copyOf(officialHosts);
        this.cacheTtls = Map.copyOf(overrides);
        if (cacheTtls.values().stream()
                .anyMatch(
                        value ->
                                value.isNegative()
                                        || value.toMillis() < 1
                                        || value.compareTo(Duration.ofDays(30)) > 0))
            throw new IllegalArgumentException("EVIDENCE_TTL_INVALID");
    }

    /**
     * @param text 有界规范正文
     * @return SHA256 小写，供不可变引用与缓存使用
     */
    public static String hash(String text) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
    }

    /**
     * @param revision 确认后的完整需求，含独立存储固定安排
     * @param encoder 固定规范 JSON
     * @return 可跨运行复用的内容依赖；固定节点变化必须失效
     */
    public static String dependency(
            com.lawrence.travel.domain.travel.ConstraintRevision revision,
            java.util.function.Function<Object, String> encoder) {
        return hash(encoder.apply(List.of(revision.requirements(), revision.fixedArrangements())));
    }

    /**
     * @param query 查询
     * @return 参数、日期、坐标系及对象均参与的完整键
     */
    public String key(Research.Query query) {
        return hash(json.encode(query));
    }

    /**
     * @param query 模型建议
     * @param start 首个旅行日期
     * @param end 最后旅行日期
     * @return 通过白名单和形状校验的查询；拒绝任意参数／网页地址
     */
    public Research.Query checked(Research.Query query, LocalDate start, LocalDate end) {
        if (query == null
                || query.operation() == null
                || query.targetId() == null
                || query.targetId().isBlank()
                || query.targetId().length() > 200
                || query.fieldPath() == null
                || query.arguments().values().stream()
                        .anyMatch(v -> v.isBlank() || v.length() > 1000)
                || (query.date() != null
                        && (query.date().isBefore(start) || query.date().isAfter(end))))
            throw new IllegalArgumentException("RESEARCH_QUERY_INVALID");
        Set<String> keys =
                switch (query.operation()) {
                    case PLACE -> Set.of("city", "keywords");
                    case WEATHER -> Set.of("city", "date");
                    case ROUTE ->
                            Set.of(
                                    "origin",
                                    "destination",
                                    "mode",
                                    "date",
                                    "city",
                                    "destinationCity");
                    case SEARCH -> Set.of("query");
                    case EXTRACT -> Set.of("url");
                };
        if (!keys.containsAll(query.arguments().keySet()))
            throw new IllegalArgumentException("RESEARCH_ARGUMENT_REJECTED");
        String field = query.fieldPath();
        if (!(switch (query.operation()) {
            case PLACE -> field.equals("poi");
            case ROUTE -> field.equals("route");
            case WEATHER -> field.equals("weather");
            case SEARCH -> field.equals("searchLead");
            case EXTRACT ->
                    Set.of("openingHours", "price", "reservation", "accessibility").contains(field);
        })) throw new IllegalArgumentException("RESEARCH_FIELD_REJECTED");
        if ((query.operation() == TravelToolPort.Operation.WEATHER
                        || query.operation() == TravelToolPort.Operation.ROUTE)
                && (query.date() == null
                        || !query.date().toString().equals(query.arguments().get("date"))))
            throw new IllegalArgumentException("RESEARCH_DATE_MISMATCH");
        if (query.operation() == TravelToolPort.Operation.EXTRACT)
            requireOfficial(query.arguments().get("url"));
        if (query.operation() == TravelToolPort.Operation.EXTRACT && query.date() == null)
            throw new IllegalArgumentException("RESEARCH_DATE_REQUIRED");
        for (String required : keys)
            if (!(query.operation() == TravelToolPort.Operation.ROUTE
                            && Set.of("city", "destinationCity").contains(required))
                    && !query.arguments().containsKey(required))
                throw new IllegalArgumentException("RESEARCH_ARGUMENT_MISSING");
        return query;
    }

    /**
     * @param value 已筛选 URL
     * @return 安全的精确官方来源，禁止认证／查询／片段／端口
     */
    public URI requireOfficial(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme())
                    || uri.getHost() == null
                    || !officialHosts.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || uri.getPort() != -1) throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("RESEARCH_URL_REJECTED");
        }
    }

    /**
     * @param operation 数据类别
     * @return 初始可调刷新间隔；不保证未来真实性
     */
    public static Duration ttl(TravelToolPort.Operation operation) {
        return switch (operation) {
            case PLACE -> Duration.ofDays(7);
            case ROUTE, WEATHER -> Duration.ofMinutes(30);
            case SEARCH, EXTRACT -> Duration.ofHours(24);
        };
    }

    /**
     * @param query 已校验查询
     * @param raw 已经过供应商适配的原文
     * @param extraction 原文提取；非网页工具为空
     * @param now 当前业务时间
     * @return 一个可追溯字段；搜索和无法核实的数据永远保留未知
     */
    public FieldEvidence normalize(
            Research.Query query,
            TravelToolPort.Evidence raw,
            Research.Extraction extraction,
            Instant now) {
        if (raw == null
                || raw.retrievedAt() == null
                || raw.retrievedAt().isAfter(now.plusSeconds(5))
                || raw.payload() == null
                || raw.payload().length() > 1_000_000)
            throw new IllegalArgumentException("RESEARCH_RESPONSE_INVALID");
        var payload = json.decode(raw.payload(), Map.class);
        if (!query.operation().name().equals(payload.get("operation")))
            throw new IllegalArgumentException("RESEARCH_OPERATION_MISMATCH");
        var data = payload.get("data") instanceof Map<?, ?> map ? map : Map.of();
        String value = "", quote = "";
        Instant published = null;
        var source = FieldEvidence.SourceType.MAP_PROVIDER;
        var status = EvidenceRecord.Status.UNKNOWN;
        URI url = raw.source();
        if (query.operation() == TravelToolPort.Operation.PLACE
                || query.operation() == TravelToolPort.Operation.ROUTE
                || query.operation() == TravelToolPort.Operation.WEATHER) {
            if (!"UNKNOWN_TOOL_FAILURE".equals(payload.get("status"))
                    && (url == null || !"restapi.amap.com".equals(url.getHost())))
                throw new IllegalArgumentException("RESEARCH_SOURCE_MISMATCH");
            if ("AVAILABLE".equals(payload.get("status"))
                    && query.date() != null
                    && !query.date().toString().equals(payload.get("targetDate")))
                throw new IllegalArgumentException("RESEARCH_DATE_MISMATCH");
        }
        // 无论模型给什么，来源由实际工具和安全 URL 决定；搜索摘要仅是后续选网址的线索。
        if (query.operation() == TravelToolPort.Operation.SEARCH) {
            source = FieldEvidence.SourceType.SEARCH_LEAD;
            quote = "搜索摘要仅为线索，尚未核实原文。";
            var leads = new java.util.ArrayList<Map<String, String>>();
            if (data.get("results") instanceof List<?> results)
                for (Object result : results) {
                    if (result instanceof Map<?, ?> item
                            && item.get("url") instanceof String address) {
                        try {
                            var uri = URI.create(address);
                            if ("https".equals(uri.getScheme())
                                    && uri.getHost() != null
                                    && uri.getUserInfo() == null
                                    && uri.getQuery() == null
                                    && uri.getFragment() == null
                                    && uri.getPort() == -1) {
                                String title = item.get("title") instanceof String text ? text : "";
                                leads.add(
                                        Map.of(
                                                "url",
                                                address,
                                                "title",
                                                title.substring(0, Math.min(title.length(), 200))));
                            }
                        } catch (IllegalArgumentException invalid) {
                            /* 不合法网页仅是不可用线索，不尝试访问。 */
                        }
                    }
                    if (leads.size() == 3) break;
                }
            value = json.encode(leads);
        } else if (query.operation() == TravelToolPort.Operation.EXTRACT) {
            url = requireOfficial(query.arguments().get("url"));
            if (!url.equals(raw.source()))
                throw new IllegalArgumentException("RESEARCH_SOURCE_MISMATCH");
            source = FieldEvidence.SourceType.OFFICIAL;
            String text = extractedText(raw);
            if (extraction != null
                    && extraction.value() != null
                    && extraction.quote() != null
                    && !extraction.quote().isBlank()
                    && text.contains(extraction.quote())
                    && extraction.quote().contains(extraction.value())
                    && !extraction.value().isBlank()) {
                quote = extraction.quote();
                value = extraction.value();
                published = extraction.publishedAt();
                // 时间敏感字段不能凭检索日期推断适用于旅行日；需要摘录明确包含目标日期。
                boolean applicable =
                        query.date() != null
                                && query.date().equals(extraction.validForDate())
                                && quote.contains(query.date().toString());
                if (applicable && "AVAILABLE".equals(payload.get("status")))
                    status = EvidenceRecord.Status.KNOWN;
            }
        } else if ("AVAILABLE".equals(payload.get("status"))) {
            Object selected =
                    switch (query.operation()) {
                        case PLACE -> data.get("pois");
                        case ROUTE -> data.get("route");
                        case WEATHER -> payload.get("forecast");
                        default -> null;
                    };
            if (query.operation() == TravelToolPort.Operation.WEATHER
                    && selected instanceof Map<?, ?> forecast
                    && !query.date().toString().equals(forecast.get("date")))
                throw new IllegalArgumentException("RESEARCH_DATE_MISMATCH");
            if (selected != null
                    && !json.encode(selected).equals("[]")
                    && !json.encode(selected).equals("{}")) {
                value = json.encode(selected);
                quote = value.length() <= 4000 ? value : value.substring(0, 4000);
                status = EvidenceRecord.Status.KNOWN;
            }
            if (query.operation() == TravelToolPort.Operation.ROUTE)
                source = FieldEvidence.SourceType.ESTIMATE;
        }
        if ("UNKNOWN_TOOL_FAILURE".equals(payload.get("status")))
            source = FieldEvidence.SourceType.UNKNOWN;
        return new FieldEvidence(
                new EvidenceRecord.Reference(UUID.randomUUID(), 1, hash(raw.payload())),
                query.targetId(),
                query.fieldPath(),
                value,
                source,
                url,
                null,
                raw.retrievedAt(),
                published,
                query.date(),
                raw.retrievedAt()
                        .plus(cacheTtls.getOrDefault(query.operation(), ttl(query.operation()))),
                quote,
                null,
                status);
    }

    /**
     * @param raw 受控提取结果
     * @return 精确匹配实际来源的正文；不会把其他网页或搜索摘要当证据
     */
    public String extractedText(TravelToolPort.Evidence raw) {
        var payload = json.decode(raw.payload(), Map.class);
        if (payload.get("data") instanceof Map<?, ?> data
                && data.get("results") instanceof List<?> results) {
            for (Object result : results)
                if (result instanceof Map<?, ?> page
                        && raw.source().toString().equals(page.get("url"))
                        && page.get("raw_content") instanceof String text) return text;
        }
        return "";
    }
}
