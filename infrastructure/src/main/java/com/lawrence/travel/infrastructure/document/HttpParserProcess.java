package com.lawrence.travel.infrastructure.document;
import com.lawrence.travel.application.document.Documents;
import com.lawrence.travel.application.document.DocumentFailure;
import com.lawrence.travel.application.port.ParserProcessPort;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
/** 只连接配置的本机runner；取消发送认证终止并等待原请求实际退出，不回退Web解析。 */
public final class HttpParserProcess implements ParserProcessPort {
    private final URI endpoint;
    private final Supplier<String> secret;
    private final TravelJson json;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    /** @param endpoint 操作者设置的本机受限入口 @param secret 仅环境读取 @param json 严格JSON */
    public HttpParserProcess(URI endpoint,Supplier<String> secret,TravelJson json) { this.endpoint=endpoint;this.secret=secret;this.json=json;if(!java.util.Set.of("127.0.0.1","localhost","host.docker.internal").contains(endpoint.getHost()) || !endpoint.getScheme().equals("http") || endpoint.getPort()!=8766 || endpoint.getUserInfo()!=null) throw new IllegalArgumentException("RUNNER_ENDPOINT_INVALID"); }
    /** {@inheritDoc} 中断不会直接释放仍在解析的容量，必须等待runner给出结束回应。 */
    @Override public Documents.Report parse(Documents.Request request) {
        String key=secret.get();if(key==null || key.length()<32) throw new DocumentFailure("DOCUMENT_RUNNER_UNAVAILABLE");
        String boot=challenge();boolean interrupted=false;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(80);var pending=client.sendAsync(build("/parse",request,key,boot),limited());
        try {
            HttpResponse<byte[]> response;
            while(true) {try {response=pending.get(Math.max(1,deadline-System.nanoTime()),java.util.concurrent.TimeUnit.NANOSECONDS);break;}catch(InterruptedException signal) {interrupted=true;var cancel=new Documents.Request(request.job(),request.token(),request.key(),request.kind(),request.hash(),UUID.randomUUID(),Instant.now().getEpochSecond());client.sendAsync(build("/cancel",cancel,key,boot),HttpResponse.BodyHandlers.discarding());}}
            byte[] bytes=response.body();if(response.statusCode()!=200 || bytes.length>2000000) throw new DocumentFailure("DOCUMENT_RUNNER_UNAVAILABLE");return json.decode(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),Documents.Report.class);
        } catch(Exception failed) {pending.cancel(true);throw new DocumentFailure("DOCUMENT_RUNNER_UNAVAILABLE");}
        finally {if(interrupted) Thread.currentThread().interrupt();}
    }
    /** @return 本机启动挑战，旧请求不能跨runner重启重放；获取失败不启动解析 */
    private String challenge() {
        try {var response=client.sendAsync(HttpRequest.newBuilder(endpoint.resolve("/challenge")).timeout(Duration.ofSeconds(3)).GET().build(),limited()).get(3,java.util.concurrent.TimeUnit.SECONDS);if(response.statusCode()!=200)throw new DocumentFailure("DOCUMENT_RUNNER_UNAVAILABLE");return java.util.UUID.fromString(json.decode(new String(response.body(),java.nio.charset.StandardCharsets.UTF_8),java.util.Map.class).get("boot").toString()).toString();}
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new DocumentFailure("DOCUMENT_CANCELLED");}
        catch(Exception failure) {throw new DocumentFailure("DOCUMENT_RUNNER_UNAVAILABLE");}
    }
    /** @return 消费期间即限制2MiB，整体Future涵盖正文完成，不能只对响应头设置超时 */
    private HttpResponse.BodyHandler<byte[]> limited() {return info -> new HttpResponse.BodySubscriber<byte[]>() {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private java.util.concurrent.Flow.Subscription subscription;
        private long received;
        /** {@inheritDoc} 返回实际有界正文的完成阶段。 */
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() {return delegate.getBody();}
        /** {@inheritDoc} 保存取消句柄并使用标准背压。 */
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) {subscription=s;delegate.onSubscribe(s);}
        /** {@inheritDoc} 分配前按字节检查，拒绝不能继续累计。 */
        @Override public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {received+=buffers.stream().mapToLong(java.nio.ByteBuffer::remaining).sum();if(received>2000000) {subscription.cancel();delegate.onError(new IllegalStateException("DOCUMENT_OUTPUT_LIMIT"));}else delegate.onNext(buffers);}
        /** {@inheritDoc} 传播失败，不保留正文副本。 */
        @Override public void onError(Throwable error) {delegate.onError(error);}
        /** {@inheritDoc} 只有完整正文才完成Future。 */
        @Override public void onComplete() {delegate.onComplete();}
    };}
    /** @param path 固定端点 @param request 受控请求 @param key 本机秘密 @param boot 本次runner挑战 @return 不记录身份头的有界HTTP请求 */
    private HttpRequest build(String path,Documents.Request request,String key,String boot) {String body=json.encode(request);return HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(80)).header("Content-Type","application/json").header("X-Parser-Boot",boot).header("X-Parser-Signature",ParserRunner.sign(path+"\n"+boot,body,key)).POST(HttpRequest.BodyPublishers.ofString(body)).build();}
}
