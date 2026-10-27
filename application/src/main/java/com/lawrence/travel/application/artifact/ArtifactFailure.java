package com.lawrence.travel.application.artifact;

/** 文件缺失或正文完整性失败使用固定分类，不携带路径、正文或底层异常。 */
public final class ArtifactFailure extends RuntimeException {
    /**
     * @param code 固定分类：ARTIFACT_MISSING／ARTIFACT_CORRUPT／ARTIFACT_UNAVAILABLE
     */
    public ArtifactFailure(String code) {
        super(code);
    }
}
