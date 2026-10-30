package com.lawrence.travel.bootstrap;

import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;

/** 为模型失败策略测试绑定独立数据库、会话命名空间及恢复目录。 */
final class ModelPolicyDatabase {
    /** 仅提供测试属性装配，不创建或删除数据库。 */
    private ModelPolicyDatabase() {}

    /**
     * 让硬退出测试子进程沿用父测试的隔离库和恢复目录，不通过命令参数传递连接配置。
     *
     * @param process 尚未启动的本机测试子进程
     */
    static void bindChild(ProcessBuilder process) {
        bind(
                (name, value) ->
                        process.environment()
                                .put(
                                        name.replace('.', '_')
                                                .replace('-', '_')
                                                .toUpperCase(java.util.Locale.ROOT),
                                        value.get().toString()));
    }

    /**
     * 指定专用测试库时覆盖连接及文件根，未指定则保持历史测试配置。
     *
     * @param registry Spring测试动态属性
     */
    static void bind(DynamicPropertyRegistry registry) {
        String database = System.getenv("TRAVEL_MODEL_POLICY_DATABASE");
        if (database == null) return;
        if (!database.matches("travel_r2_[a-f0-9]{16}"))
            throw new IllegalArgumentException("MODEL_POLICY_DATABASE_INVALID");
        String root = System.getenv("TRAVEL_MODEL_POLICY_ROOT");
        if (root == null || root.isBlank())
            throw new IllegalArgumentException("MODEL_POLICY_STORAGE_REQUIRED");
        String host = System.getenv().getOrDefault("POSTGRES_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("POSTGRES_PORT", "5432");
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:postgresql://" + host + ":" + port + "/" + database);
        registry.add("spring.session.redis.namespace", () -> "travel:model-policy:" + database);
        registry.add("travel.deletions.root", () -> Path.of(root, "lifecycle").toString());
        registry.add("travel.artifacts.root", () -> Path.of(root, "artifacts").toString());
        registry.add("travel.documents.root", () -> Path.of(root, "documents").toString());
    }
}
