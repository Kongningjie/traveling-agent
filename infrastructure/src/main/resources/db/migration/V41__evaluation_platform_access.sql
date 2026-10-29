-- 默认关闭整个平台入口。未来治理器需完成原始记录/副本/删除标记核验，才可显式开放。
-- 当前迁移及访问适配器均不提供开放入口，不能用schema初始化冒充治理通过。
CREATE TABLE evaluation_platform_access (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    verified BOOLEAN NOT NULL DEFAULT false
);
INSERT INTO evaluation_platform_access(singleton,verified) VALUES(true,false);
