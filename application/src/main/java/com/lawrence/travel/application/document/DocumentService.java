package com.lawrence.travel.application.document;

import com.lawrence.travel.application.execution.Jobs;
import com.lawrence.travel.application.port.ArtifactFiles;
import com.lawrence.travel.application.port.ArtifactStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.DialogueStore;
import com.lawrence.travel.application.port.DocumentStore;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.JobHandler;
import com.lawrence.travel.application.port.JobStore;
import com.lawrence.travel.application.port.ParserProcessPort;
import com.lawrence.travel.application.safety.SafetyGate;
import com.lawrence.travel.domain.execution.BackgroundJob;
import com.lawrence.travel.domain.identity.UserIdentity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 有界原件、配额和解析用例；所有业务提交由PG围栏决定，不信任runner状态。 */
public final class DocumentService implements JobHandler {
    private final DocumentStore store;
    private final DocumentLimits limits;
    private final ArtifactFiles files;
    private final ArtifactStore artifacts;
    private final DialogueStore chats;
    private final JobStore jobs;
    private final BusinessTransactions tx;
    private final InteractionCodec json;
    private final ParserProcessPort parser;
    private final SafetyGate safety;
    /** @param store 文档 @param files 原件 @param artifacts 文件锁及孤儿协议 @param chats 独立聊天 @param jobs 唯一调度 @param tx 短事务 @param json JSON @param parser 隔离执行 @param safety 文本边界 */
    public DocumentService(DocumentStore store,ArtifactFiles files,ArtifactStore artifacts,DialogueStore chats,JobStore jobs,BusinessTransactions tx,InteractionCodec json,ParserProcessPort parser,SafetyGate safety) {
        this(store,files,artifacts,chats,jobs,tx,json,parser,safety,DocumentLimits.DEFAULT);
    }
    /** @param store 索引 @param files 原件 @param artifacts 文件锁 @param chats 聊天 @param jobs 调度 @param tx 事务 @param json JSON @param parser 隔离入口 @param safety 安全 @param limits 受信配置 */
    public DocumentService(DocumentStore store,ArtifactFiles files,ArtifactStore artifacts,DialogueStore chats,JobStore jobs,BusinessTransactions tx,InteractionCodec json,ParserProcessPort parser,SafetyGate safety,DocumentLimits limits) {
        this.limits=limits;this.store=store;this.files=files;this.artifacts=artifacts;this.chats=chats;this.jobs=jobs;this.tx=tx;this.json=json;this.parser=parser;this.safety=safety;
    }
    /** @param bytes 原件 @return SHA256，文件名不参与完整性判断 */
    public static String hash(byte[] bytes) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA256_UNAVAILABLE"); } }
    /** @param actor 当前身份 @param chat 客户端稳定聊天 @param id 稳定上传ID @param name 原文件显示名 @param bytes 至多10MiB @return 已可靠登记的状态；同ID异内容冲突 */
    public Documents.View upload(UserIdentity actor,UUID chat,UUID id,String name,byte[] bytes) {
        if(actor==null || chat==null || id==null || name==null || name.isBlank() || name.length()>160 || name.chars().anyMatch(c -> c<32 || c=='/' || c=='\\') || bytes==null || bytes.length==0 || bytes.length>limits.bytes()) throw new DocumentFailure("DOCUMENT_INPUT_LIMIT");
        String kind=name.substring(name.lastIndexOf('.')+1).toLowerCase(java.util.Locale.ROOT);
        if(!List.of("pdf","docx","txt","md").contains(kind)) throw new DocumentFailure("DOCUMENT_UNSUPPORTED");
        if(kind.equals("pdf") && !new String(bytes,0,Math.min(5,bytes.length),java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-") || kind.equals("docx") && (bytes.length<4 || bytes[0]!=80 || bytes[1]!=75 || bytes[2]!=3 || bytes[3]!=4)) throw new DocumentFailure("DOCUMENT_FORMAT_MISMATCH");
        byte[] stable=bytes.clone();String fingerprint=hash(stable);var scope=new Jobs.Scope(actor.id(),chat);
        return tx.required(() -> {
            // 聊天可只含附件，不创建旅行占位。所有容量检查在同一用户锁下，失败整体回滚。
            try { chats.read(actor.id(),chat); } catch(com.lawrence.travel.application.travel.TravelStorageFailure missing) { if(missing.code()!=com.lawrence.travel.application.travel.TravelStorageFailure.Code.NOT_FOUND) throw missing;chats.create(actor.id(),chat,"旅行资料"); }
            store.lock(scope);
            Documents.Entry old=null;try { old=store.get(scope,id); } catch(DocumentFailure missing) { if(!missing.getMessage().equals("DOCUMENT_NOT_FOUND")) throw missing; }
            if(old!=null) { if(old.deleted() || !old.hash().equals(fingerprint) || !old.name().equals(name)) throw new DocumentFailure("DOCUMENT_IDEMPOTENCY_CONFLICT");return view(scope,old); }
            store.capacity(scope,stable.length);artifacts.lockFiles(false);
            String key="documents/"+actor.id()+"/"+chat+"/"+id+".bin";
            // 文件先完整写入再提交引用。事务失败的孤儿保留24小时，由共用排他回收处理。
            boolean exists=false;try { var existing=files.read(key);if(!hash(existing).equals(fingerprint)) throw new DocumentFailure("DOCUMENT_IDEMPOTENCY_CONFLICT");exists=true; }
            catch(com.lawrence.travel.application.artifact.ArtifactFailure missing) {if(!missing.getMessage().equals("ARTIFACT_MISSING")) throw missing;}
            if(!exists) files.write(key,stable);
            var job=jobs.submit(new Jobs.Submit(scope,UUID.nameUUIDFromBytes(("document-parse/"+actor.id()+"/"+chat+"/"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8)),kind(),"parse",json.encode(Map.of("document",id)),null,null));
            var entry=new Documents.Entry(id,actor.id(),chat,job.id(),name,kind,fingerprint,stable.length,key,false,null);store.insert(entry);return view(scope,entry);
        });
    }
    /** @param actor 身份 @param chat 私有聊天 @return 不含原文及路径的附件列表 */
    public List<Documents.View> list(UserIdentity actor,UUID chat) { return tx.required(() -> { var s=new Jobs.Scope(actor.id(),chat);store.lock(s);return store.list(s).stream().map(e -> view(s,e)).toList(); }); }
    /** @param actor 身份 @param chat 私有聊天 @param id 原件 @return 完整性校验后下载；始终作为附件不内联 */
    public byte[] download(UserIdentity actor,UUID chat,UUID id) { return tx.required(() -> { var s=new Jobs.Scope(actor.id(),chat);store.lock(s);var e=visible(s,id);var bytes=files.read(e.key());if(!hash(bytes).equals(e.hash()) || bytes.length!=e.size()) throw new DocumentFailure("DOCUMENT_CORRUPT");return bytes; }); }
    /** @param actor 身份 @param chat 聊天 @param id 原件 @param action STOP／RETRY／DELETE，均为显式用户动作 */
    public void action(UserIdentity actor,UUID chat,UUID id,String action) { tx.required(() -> {
        var s=new Jobs.Scope(actor.id(),chat);store.lock(s);var e=store.get(s,id);
        if(action.equals("DELETE")) {
            if(!e.deleted()) {
                store.delete(s,id);
                // 删除触发来源撤销后，通过原作业用例取消已读旧资料的输入并结算；不让失效作业等待租约后自动重跑。
                // 只取消受影响CHAT_INPUT，不使其他附件解析失去资格，也不绕过JobStore的预算与投影处理。
                for (UUID reader : store.revokedReaders(s)) jobs.control(s,reader,false);
                var job=jobs.read(s,e.jobId());if(!BackgroundJob.terminal(job.state())) jobs.control(s,e.jobId(),false);
            }
        } else {
            if(e.deleted()) throw new DocumentFailure("DOCUMENT_NOT_FOUND");
            var job=jobs.read(s,e.jobId());
            if(action.equals("STOP")) { if(!BackgroundJob.terminal(job.state())) jobs.control(s,e.jobId(),false); }
            else if(action.equals("RETRY")) { if(job.state()!=BackgroundJob.State.PAUSED && job.state()!=BackgroundJob.State.WAITING_USER) throw new DocumentFailure("DOCUMENT_STATE_CHANGED");store.retryCapacity(s,id);store.report(s,id,null);jobs.control(s,e.jobId(),true); }
            else throw new DocumentFailure("DOCUMENT_INPUT_LIMIT");
        } return null;
    }); }
    /** {@inheritDoc} 解析容量由既有Worker持有。 */
    @Override public BackgroundJob.Kind kind() { return BackgroundJob.Kind.DOCUMENT_PARSE; }
    /** {@inheritDoc} 不持事务等待Docker；这里只返回临时结果，不能直接成为可读正文。 */
    @Override public Jobs.Output execute(Jobs.Claim claim) {
        var id=UUID.fromString(json.decode(claim.payload(),Map.class).get("document").toString());
        var entry=tx.required(() -> {store.lock(claim.fence().scope());if(!jobs.live(claim.fence())) throw new DocumentFailure("DOCUMENT_STALE");return visible(claim.fence().scope(),id);});
        Documents.Report report;
        try {
            if(!hash(files.read(entry.key())).equals(entry.hash())) throw new DocumentFailure("DOCUMENT_CORRUPT");
            report=parser.parse(new Documents.Request(claim.job().id(),claim.fence().token(),entry.key(),entry.kind(),entry.hash(),UUID.randomUUID(),Instant.now().getEpochSecond()));
            validate(report);safety.require("CONTEXT",report.notice());
            if(report.code().equals("OK")) for(var section:report.sections()) safety.require("CONTEXT",section.text());
        } catch(SafetyGate.Failure rejected) { report=failed(rejected.result().status()==SafetyGate.Status.UNAVAILABLE?"DOCUMENT_SAFETY_UNAVAILABLE":"DOCUMENT_UNSAFE_TEXT"); }
        catch(DocumentFailure failure) { report=failed(failure.getMessage()); }
        return new Jobs.Output(report.code().equals("OK")?BackgroundJob.State.COMPLETED:BackgroundJob.State.PAUSED,json.encode(report),List.of(),report.code().equals("OK")?"":report.code());
    }
    /** {@inheritDoc} PG全文与作业终态同事务；取消、删除或过期结果全部拒绝，不产生半份READY。 */
    @Override public void commit(Jobs.Claim claim,Jobs.Output output,Runnable unused) { tx.required(() -> {
        var s=claim.fence().scope();store.lock(s);if(!jobs.live(claim.fence())) throw new DocumentFailure("DOCUMENT_STALE");
        var id=UUID.fromString(json.decode(claim.payload(),Map.class).get("document").toString());visible(s,id);
        var report=json.decode(output.artifact(),Documents.Report.class);validate(report);store.report(s,id,output.artifact());
        jobs.finish(claim,new Jobs.Output(output.state(),json.encode(Map.of("document",id,"parser",report.parserVersion(),"code",report.code())),List.of(),output.reason()));return null;
    }); }
    /** 墓碑提交后每分钟尝试清理；物理失败保留引用和容量，下轮重试，未开放用户维护接口。 */
    public void cleanup() { for(var entry:store.garbage()) tx.required(() -> {
        var s=new Jobs.Scope(entry.userId(),entry.chatId());store.lock(s);var fresh=store.get(s,entry.id());if(fresh.deleted()) {artifacts.lockFiles(false);files.delete(fresh.key());store.purged(fresh.id());}return null;
    }); tx.required(() -> {artifacts.lockFiles(true);files.collect(store.references(),Instant.now().minus(java.time.Duration.ofHours(24)));return null;}); }
    /** @param s 私有归属 @param id 原件 @return 当前仍可读索引 */
    private Documents.Entry visible(Jobs.Scope s,UUID id) { var e=store.get(s,id);if(e.deleted()) throw new DocumentFailure("DOCUMENT_NOT_FOUND");return e; }
    /** @param s 私有范围 @param e 权威元数据 @return READY只来自已提交且检查通过的OK报告 */
    private Documents.View view(Jobs.Scope s,Documents.Entry e) {
        var job=jobs.read(s,e.jobId());var r=e.report()==null?null:json.decode(e.report(),Documents.Report.class);
        String state=job.state()==BackgroundJob.State.COMPLETED && r!=null && r.code().equals("OK")?"READY":job.state().name();
        return new Documents.View(e.id(),e.name(),e.kind(),e.size(),state,r==null?job.error():r.code(),r==null?0:r.sections().size(),r==null?"原件已保存，尚未完成读取。":r.notice());
    }
    /** @param report 隔离进程不可信结果；位置、字符、状态及版本全部验证 */
    private void validate(Documents.Report report) {
        if(report==null || report.parserVersion()==null || report.notice()==null || report.code()==null || !report.parserVersion().equals("pdfbox-3.0.8_poi-5.5.1_v1") || !java.util.Set.of("OK","DOCUMENT_INPUT_LIMIT","DOCUMENT_FORMAT_MISMATCH","DOCUMENT_UNSUPPORTED","DOCUMENT_ENCODING","DOCUMENT_COMPLEXITY_LIMIT","DOCUMENT_ENCRYPTED","DOCUMENT_ACTIVE_CONTENT","DOCUMENT_NO_TEXT","DOCUMENT_TEXT_LIMIT","DOCUMENT_EXPANSION_LIMIT","DOCUMENT_CORRUPT","DOCUMENT_RUNNER_BUSY","DOCUMENT_PARSE_TIMEOUT","DOCUMENT_PARSER_EXIT","DOCUMENT_RUNNER_FAILED","DOCUMENT_CANCELLED","DOCUMENT_PARSER_PROTOCOL","DOCUMENT_RUNNER_UNAVAILABLE","DOCUMENT_UNSAFE_TEXT","DOCUMENT_SAFETY_UNAVAILABLE").contains(report.code()) || report.notice().length()>500 || report.sections().size()>10000 || report.sections().stream().anyMatch(s -> s==null || s.text()==null) || report.sections().stream().mapToLong(s -> s.text().length()).sum()>limits.text()) throw new DocumentFailure("DOCUMENT_PARSER_PROTOCOL");
        if(!report.code().equals("OK") && !report.sections().isEmpty()) throw new DocumentFailure("DOCUMENT_PARSER_PROTOCOL");
        int last=0;for(var section:report.sections()) {if(section.position()<=last || section.text()==null) throw new DocumentFailure("DOCUMENT_PARSER_PROTOCOL");last=section.position();}
        if(report.code().equals("OK") && report.sections().stream().allMatch(s -> s.text().isBlank())) throw new DocumentFailure("DOCUMENT_NO_TEXT");
    }
    /** @param code 固定失败 @return 不保存异常或原件正文的报告 */
    private Documents.Report failed(String code) { return new Documents.Report("pdfbox-3.0.8_poi-5.5.1_v1",code,List.of(),"解析未完成。请检查格式、文件完整性或解析服务状态；不支持扫描件及OCR。"); }
}
