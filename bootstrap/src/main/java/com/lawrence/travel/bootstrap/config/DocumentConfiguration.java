package com.lawrence.travel.bootstrap.config;
import com.lawrence.travel.application.document.DocumentService;
import com.lawrence.travel.application.port.ArtifactStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.DialogueStore;
import com.lawrence.travel.application.port.DocumentStore;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.JobStore;
import com.lawrence.travel.application.port.ParserProcessPort;
import com.lawrence.travel.application.safety.SafetyGate;
import com.lawrence.travel.infrastructure.artifact.VolumeArtifactFiles;
import com.lawrence.travel.infrastructure.document.DocumentMapper;
import com.lawrence.travel.infrastructure.document.HttpParserProcess;
import com.lawrence.travel.infrastructure.document.PostgresDocumentStore;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
/** 附件唯一装配；runner缺失只暂停解析，不影响登录／收件，不回退Web解析。 */
@Configuration(proxyBeanMethods=false)
@Profile("local")
@MapperScan(basePackageClasses=DocumentMapper.class)
@org.springframework.scheduling.annotation.EnableScheduling
public class DocumentConfiguration {
    /** @param mapper 资料SQL @return PG权威引用，不依赖本机文件缓存 */
    @Bean com.lawrence.travel.application.port.SourceReadStore sourceReadStore(com.lawrence.travel.infrastructure.document.SourceReadMapper mapper) {
        return new com.lawrence.travel.infrastructure.document.PostgresSourceReadStore(mapper);
    }
    /** @param sources 引用 @param documents 索引 @param jobs 作业 @param tx 短事务 @param json JSON @param safety 安全 @return 本地确定性检索 */
    @Bean com.lawrence.travel.application.document.SourceReadService sourceReadService(com.lawrence.travel.application.port.SourceReadStore sources, DocumentStore documents, JobStore jobs, BusinessTransactions tx, InteractionCodec json, SafetyGate safety) {
        return new com.lawrence.travel.application.document.SourceReadService(sources, documents, jobs, tx, json, safety);
    }
    /** @param e 服务端非敏感数值配置 @return 冻结初值及收紧范围 */
    @Bean com.lawrence.travel.application.document.DocumentLimits documentLimits(Environment e) {return com.lawrence.travel.application.document.DocumentLimits.from(e::getProperty);}
    /** @param mapper SQL @param limits 有界配置 @return 权威索引 */
    @Bean DocumentStore documentStore(DocumentMapper mapper,com.lawrence.travel.application.document.DocumentLimits limits) {return new PostgresDocumentStore(mapper,limits);}
    /** @param e 本地配置 @param json JSON @return 默认回环入口，不持Docker权限 */
    @Bean ParserProcessPort parserProcessPort(Environment e,TravelJson json) {return new HttpParserProcess(java.net.URI.create(e.getProperty("travel.documents.runner","http://127.0.0.1:8766")),() -> e.getProperty("DOCUMENT_PARSER_SECRET"),json);}
    /** @param store 索引 @param artifacts 文件锁 @param chats 聊天 @param jobs 调度 @param tx 事务 @param json JSON @param parser 隔离执行 @param safety 安全 @param e 根目录 @param limits 有界配置 @param lifecycle 统一删除清单用例 @return 同一Worker自动发现的解析处理器 */
    @Bean DocumentService documentService(DocumentStore store,ArtifactStore artifacts,DialogueStore chats,JobStore jobs,BusinessTransactions tx,InteractionCodec json,ParserProcessPort parser,SafetyGate safety,Environment e,com.lawrence.travel.application.document.DocumentLimits limits,
            com.lawrence.travel.application.lifecycle.LifecycleService lifecycle) {
        var service = new DocumentService(store,new VolumeArtifactFiles(Path.of(e.getProperty("travel.documents.root",".runtime/documents"))),artifacts,chats,jobs,tx,json,parser,safety,limits);
        service.deletionService(lifecycle);
        return service;}
    /** @param service 用例 @return 每分钟清理墓碑，失败不冒充已清理，不新增业务调度器 */
    @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="travel.documents.cleanup-enabled",havingValue="true",matchIfMissing=true)
    DocumentCleanup documentCleanup(DocumentService service) {return new DocumentCleanup(service);}
    /** 仅维护清理，不领取解析作业或进行模型调用。 */
    public static class DocumentCleanup {
        private final DocumentService service;
        /** @param service 同一用例 */
        public DocumentCleanup(DocumentService service) {this.service=service;}
        /** 物理失败下轮重试，输出固定错误，不归档日志或用户正文。 */
        @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000,initialDelay=60000)
        public void clean() {try {service.cleanup();}catch(RuntimeException ignored) {System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"DOCUMENT_CLEANUP_RETRY");}}
    }
}
