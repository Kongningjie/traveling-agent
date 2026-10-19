package com.lawrence.travel.application.port;

import java.time.Instant;
import java.util.Set;

/** 有界必要文件端口；存储键由应用生成，路径从不交给用户或模型。 */
public interface ArtifactFiles {
    /** @param key 严格服务端键 @param bytes 至多10MiB @return 无；完整写入并原子改名后返回，不覆盖已有文件 */
    void write(String key, byte[] bytes);
    /** @param key 严格键 @return 至多10MiB；缺失、符号链接或损坏路径明确拒绝 */
    byte[] read(String key);
    /** @param references 有效引用 @param before 保留期之前的文件才可回收 @return 删除的孤儿数；不得跟随链接或删除未知文件 */
    int collect(Set<String> references, Instant before);
    /** @param key 墓碑已提交的原件；只删除受管键，重复删除幂等 */
    default void delete(String key) { throw new UnsupportedOperationException("DELETE_NOT_CONFIGURED"); }
}
