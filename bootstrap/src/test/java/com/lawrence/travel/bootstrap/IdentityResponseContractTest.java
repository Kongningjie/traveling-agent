package com.lawrence.travel.bootstrap;

import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证认证接口实际返回类型的JSON与前端共用夹具一致；不接数据库、不创建账号、不读取凭证。 */
class IdentityResponseContractTest {
    /** 真实UserIdentity序列化为id和loginName；防止前端夹具自己假设username后又自证通过。
     * @throws IOException 固定夹具无法读取时拒绝掩盖契约验证缺失
     */
    @Test void serializedIdentityMatchesFrontendFixture() throws IOException {
        var json = new TravelJson();
        var user = new UserIdentity(UUID.fromString("00000000-0000-4000-8000-000000000099"), "fixture_user");
        var fixture = List.of(Path.of("web/tests/fixtures/identity.json"), Path.of("../web/tests/fixtures/identity.json"))
                .stream().filter(Files::isRegularFile).findFirst().orElseThrow(() -> new IllegalStateException("IDENTITY_CONTRACT_FIXTURE_MISSING"));
        assertThat(json.decode(json.encode(user), Map.class)).isEqualTo(json.decode(Files.readString(fixture), Map.class));
    }
}
