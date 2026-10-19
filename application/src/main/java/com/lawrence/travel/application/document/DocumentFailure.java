package com.lawrence.travel.application.document;
/** 固定附件错误，禁止携带原文件名、路径或解析器异常正文。 */
public final class DocumentFailure extends RuntimeException {
    /** @param code 服务端固定分类 */
    public DocumentFailure(String code) { super(code); }
}
