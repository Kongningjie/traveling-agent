package com.lawrence.travel.infrastructure.document;

import com.lawrence.travel.application.document.Documents;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 受认证的本机技术runner；固定镜像／挂载／模板，不调度业务、不读取数据库或模型凭据。 */
public final class ParserRunner {
    private final com.lawrence.travel.application.document.DocumentLimits limits=com.lawrence.travel.application.document.DocumentLimits.from(System::getenv);
    private final Path root;
    private final UUID boot=UUID.randomUUID();
    /** @return 本次启动的公开挑战值；与HMAC共同绑定，防止重启后重放旧签名 */
    public UUID boot() {return boot;}
    private final String image,secret;
    private final java.time.Duration timeout;
    private final Map<String,Long> cancelled=new ConcurrentHashMap<>();
    private final TravelJson json=new TravelJson();
    private final Semaphore slot=new Semaphore(1);
    private final Map<UUID,Long> nonces=new ConcurrentHashMap<>();
    private final Map<String,Process> active=new ConcurrentHashMap<>();
    /** @param root 操作者限定原件根 @param image 已构建的精确sha256镜像 @param secret 只在环境内传递的认证秘密 */
    public ParserRunner(Path root,String image,String secret) {
        this(root,image,secret,java.time.Duration.ofSeconds(com.lawrence.travel.application.document.DocumentLimits.from(System::getenv).seconds()));
    }
    /** @param root 原件根 @param image 固定镜像 @param secret 认证 @param timeout 测试可收紧，不得超过冻结60秒 */
    public ParserRunner(Path root,String image,String secret,java.time.Duration timeout) {
        if(timeout.isNegative() || timeout.isZero() || timeout.compareTo(java.time.Duration.ofSeconds(60))>0) throw new IllegalArgumentException("RUNNER_TIMEOUT_INVALID");this.timeout=timeout;
        try {java.nio.file.Files.createDirectories(root);this.root=root.toRealPath();} catch(java.io.IOException e) {throw new IllegalStateException("RUNNER_ROOT_INVALID");}
        if(image==null || !image.matches("sha256:[0-9a-f]{64}") || secret==null || secret.length()<32) throw new IllegalStateException("RUNNER_CONFIG_INVALID");
        this.image=image;this.secret=secret;
    }
    /** @param path 固定端点 @param body 完整有界JSON @param secret 本机共享秘密 @return HMAC十六进制，不返回秘密 */
    public static String sign(String path,String body,String secret) {
        try {var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));return java.util.HexFormat.of().formatHex(mac.doFinal((path+"\n"+body).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.GeneralSecurityException e) {throw new IllegalStateException("RUNNER_AUTH_UNAVAILABLE");}
    }
    /** @param address 默认回环，不自动公开端口 @param port 固定本机端口 @return 可关闭服务；仅接受带新nonce的签名请求 */
    public HttpServer start(String address,int port) throws java.io.IOException {
        // 技术入口限制请求接收时间、头部和处理线程；无认证请求不能无限占用虚拟线程。
        System.setProperty("sun.net.httpserver.maxReqTime","5");System.setProperty("sun.net.httpserver.maxRspTime","85");System.setProperty("sun.net.httpserver.maxReqHeaders","20");
        var server=HttpServer.create(new java.net.InetSocketAddress(address,port),8);server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4,Thread.ofVirtual().factory()));
        try { cleanupLeftovers(); } catch(RuntimeException failure) {server.stop(0);throw failure;}
        server.createContext("/",exchange -> {
            if(exchange.getRequestMethod().equals("GET") && exchange.getRequestURI().toString().equals("/challenge")) {
                byte[] challenge=json.encode(Map.of("boot",boot)).getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,challenge.length);try(var out=exchange.getResponseBody()){out.write(challenge);}exchange.close();return;
            }
            int status=200;String response;
            try {
                String path=exchange.getRequestURI().toString();byte[] bytes=exchange.getRequestBody().readNBytes(4097);String body=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
                String provided=exchange.getRequestHeaders().getFirst("X-Parser-Signature");String signedBoot=exchange.getRequestHeaders().getFirst("X-Parser-Boot");
                if(!exchange.getRequestMethod().equals("POST") || !java.util.Set.of("/parse","/cancel").contains(path) || bytes.length>4096 || provided==null || provided.length()!=64 || !boot.toString().equals(signedBoot) || !java.security.MessageDigest.isEqual(sign(path+"\n"+signedBoot,body,secret).getBytes(java.nio.charset.StandardCharsets.US_ASCII),provided.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) throw new SecurityException();
                var request=json.decode(body,Documents.Request.class);long now=Instant.now().getEpochSecond();
                nonces.entrySet().removeIf(e -> e.getValue()<now-120);cancelled.entrySet().removeIf(e -> e.getValue()<now-120);
                if(request.timestamp()<now-60 || request.timestamp()>now+60 || request.nonce()==null || nonces.size()>=1000 || nonces.putIfAbsent(request.nonce(),now)!=null) throw new SecurityException();
                if(path.equals("/cancel")) {cancelled.put(name(request),now);stop(name(request));response=json.encode(IsolatedDocumentParser.failure("DOCUMENT_CANCELLED"));}
                else response=json.encode(run(request));
            } catch(SecurityException denied) {status=403;response="{}";}
            catch(Exception invalid) {status=503;response="{}";}
            byte[] output=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,output.length);
            try(var out=exchange.getResponseBody()) {out.write(output);} finally {exchange.close();}
        });server.start();return server;
    }
    /** @param request 有效签名请求 @return 至多200万字节报告；实际退出前不释放技术槽 */
    public Documents.Report run(Documents.Request request) {
        String name=name(request);
        if(!slot.tryAcquire()) return IsolatedDocumentParser.failure("DOCUMENT_RUNNER_BUSY");
        Process process=null;
        try {
            Path file=resolve(request.key());
            if(!java.util.Set.of("pdf","docx","txt","md").contains(request.kind()) || !com.lawrence.travel.application.document.DocumentService.hash(java.nio.file.Files.readAllBytes(file)).equals(request.hash())) throw new IllegalArgumentException();
            // 所有容器参数固定；原件只读挂载，网络为空，根只读，临时展开仅在有界tmpfs中。
            var command=new java.util.ArrayList<>(java.util.List.of("docker","run","--pull=never","--name",name,"--label=traveling-agent-parser=true","--network=none","--read-only","--cap-drop=ALL","--security-opt=no-new-privileges","--user=65532:65532","--cpus=1","--memory=512m","--memory-swap=512m","--pids-limit=64","--tmpfs=/tmp:rw,noexec,nosuid,size=64m","--mount","type=bind,source="+file+",target=/input/document.bin,readonly"));
            limits.environment().forEach((key,value) -> command.add("--env="+key+"="+value));command.addAll(java.util.List.of(image,"--parse-document",request.kind()));
            process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();active.put(name,process);if(cancelled.containsKey(name)) {stop(name);return IsolatedDocumentParser.failure("DOCUMENT_CANCELLED");}
            var output=new java.util.concurrent.CompletableFuture<byte[]>();var stream=process.getInputStream();
            Thread.startVirtualThread(() -> {try(stream) {byte[] data=stream.readNBytes(2000001);if(data.length>2000000) throw new java.io.IOException();output.complete(data);}catch(Exception e) {output.completeExceptionally(new IllegalStateException("DOCUMENT_OUTPUT_LIMIT"));}});
            if(!process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS)) {stop(name);return IsolatedDocumentParser.failure("DOCUMENT_PARSE_TIMEOUT");}
            if(process.exitValue()!=0) return IsolatedDocumentParser.failure("DOCUMENT_PARSER_EXIT");
            return json.decode(new String(output.get(3,TimeUnit.SECONDS),java.nio.charset.StandardCharsets.UTF_8),Documents.Report.class);
        } catch(Exception failed) {return IsolatedDocumentParser.failure("DOCUMENT_RUNNER_FAILED");}
        finally {stop(name);active.remove(name);slot.release();}
    }
    /** @param key 严格服务器存储键 @return 根内原件，拒绝链接、目录逃逸和超过10MiB */
    private Path resolve(String key) throws java.io.IOException {
        String u="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        if(key==null || !key.matches("documents/"+u+"/"+u+"/"+u+"\\.bin")) throw new SecurityException();
        Path file=root.resolve(key);for(Path p=file;!p.equals(root);p=p.getParent()) if(java.nio.file.Files.isSymbolicLink(p) || !p.toRealPath().startsWith(root)) throw new SecurityException();
        if(!java.nio.file.Files.isRegularFile(file) || java.nio.file.Files.size(file)>limits.bytes()) throw new SecurityException();return file;
    }
    /** @param r 已签发尝试 @return 不能由用户自选容器名 */
    private String name(Documents.Request r) { if(r.job()==null || r.token()<1) throw new SecurityException();return "travel-parser-"+r.job()+"-"+r.token(); }
    /** @param name 本runner创建的固定名称；rm完成并确认docker客户端退出后才返回 */
    private void stop(String name) {
        Process running=active.get(name);
        boolean interrupted=Thread.interrupted();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        try {
            do {
                var remove=new ProcessBuilder("docker","rm","-f",name).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                while(true) {try {if(!remove.waitFor(5,TimeUnit.SECONDS)) {remove.destroyForcibly();throw new IllegalStateException("DOCUMENT_TERMINATION_UNKNOWN");}break;}catch(InterruptedException signal){interrupted=true;}}
                var inspect=new ProcessBuilder("docker","ps","-aq","--filter","name=^"+name+"$").redirectError(ProcessBuilder.Redirect.DISCARD).start();
                byte[] ids=inspect.getInputStream().readNBytes(1025);
                try {if(!inspect.waitFor(5,TimeUnit.SECONDS) || inspect.exitValue()!=0 || ids.length>1024) throw new IllegalStateException("DOCUMENT_TERMINATION_UNKNOWN");}catch(InterruptedException signal){interrupted=true;continue;}
                if(ids.length==0 && (running==null || !running.isAlive())) return;
                try {running.waitFor(100,TimeUnit.MILLISECONDS);}catch(InterruptedException signal){interrupted=true;}
            } while(System.nanoTime()<deadline);
            throw new IllegalStateException("DOCUMENT_TERMINATION_UNKNOWN");
        } catch(java.io.IOException failed) {throw new IllegalStateException("DOCUMENT_TERMINATION_UNKNOWN");}
        finally {if(interrupted)Thread.currentThread().interrupt();}
    }

    /** 重启前清理本项目专用标签的孤立解析容器；不能仅因runner内存状态丢失就重新放大物理容量。 */
    private void cleanupLeftovers() {
        try {
            var list=new ProcessBuilder("docker","ps","-aq","--filter","label=traveling-agent-parser=true").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String ids=new String(list.getInputStream().readNBytes(4097),java.nio.charset.StandardCharsets.US_ASCII);
            if(!list.waitFor(5,TimeUnit.SECONDS) || list.exitValue()!=0 || ids.length()>4096) throw new IllegalStateException("RUNNER_RECOVERY_UNAVAILABLE");
            for(String id:ids.split("\\s+")) {if(id.isBlank())continue;if(!id.matches("[0-9a-f]{12,64}")) throw new IllegalStateException("RUNNER_RECOVERY_UNAVAILABLE");var remove=new ProcessBuilder("docker","rm","-f",id).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();if(!remove.waitFor(10,TimeUnit.SECONDS) || remove.exitValue()!=0)throw new IllegalStateException("RUNNER_RECOVERY_UNAVAILABLE");}
        } catch(java.io.IOException e) {throw new IllegalStateException("RUNNER_RECOVERY_UNAVAILABLE");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("RUNNER_RECOVERY_UNAVAILABLE");}
    }
    /** @param args 未使用；环境只读取runner自身3个配置，不装配Spring或PG */
    public static void main(String[] args) throws java.io.IOException {
        var runner=new ParserRunner(Path.of(System.getenv().getOrDefault("TRAVEL_DOCUMENTS_ROOT",".runtime/documents")),System.getenv("DOCUMENT_PARSER_IMAGE"),System.getenv("DOCUMENT_PARSER_SECRET"));
        var server=runner.start(System.getenv().getOrDefault("DOCUMENT_RUNNER_BIND","127.0.0.1"),8766);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {server.stop(0);runner.active.keySet().forEach(runner::stop);}));
        System.out.println("DOCUMENT_RUNNER_READY");
    }
}
