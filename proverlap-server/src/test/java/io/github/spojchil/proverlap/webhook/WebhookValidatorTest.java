package io.github.spojchil.proverlap.webhook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.github.spojchil.proverlap.config.GitHubProperties;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Webhook HMAC 签名验证单元测试。 */
@DisplayName("WebhookValidator 签名验证单元测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookValidatorTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String BODY = "{\"action\":\"opened\",\"pull_request\":{\"number\":1}}";

    @Mock private GitHubProperties properties;

    private WebhookValidator validator;

    @BeforeEach
    void setUp() {
        when(properties.getWebhookSecret()).thenReturn(SECRET);
        validator = new WebhookValidator(properties);
    }

    // ==================== 有效签名 ====================

    @Test
    @DisplayName("verify — 正确签名返回 true")
    void validSignature() {
        String sig = sign(BODY, SECRET);
        assertTrue(validator.verify(BODY, sig), "正确签名应通过验证");
    }

    @Test
    @DisplayName("verify — 签名不匹配返回 false")
    void invalidSignature() {
        String sig = sign("{}", SECRET); // 不同 payload 的签名
        assertFalse(validator.verify(BODY, sig), "签名不匹配应返回 false");
    }

    @Test
    @DisplayName("verify — 使用错误的 secret 签名返回 false")
    void wrongSecret() {
        String sig = sign(BODY, "wrong-secret");
        assertFalse(validator.verify(BODY, sig), "错误密钥签的名应返回 false");
    }

    // ==================== 边界条件 ====================

    @Test
    @DisplayName("verify — signature 为 null 返回 false")
    void nullSignature() {
        assertFalse(validator.verify(BODY, null), "null 签名应返回 false");
    }

    @Test
    @DisplayName("verify — signature 缺少 sha256= 前缀返回 false")
    void missingPrefix() {
        String rawHex = sign(BODY, SECRET).substring(7); // 去掉 sha256=
        assertFalse(validator.verify(BODY, rawHex), "缺少前缀的签名应返回 false");
    }

    @Test
    @DisplayName("verify — 空 payload 有效签名仍能验证")
    void emptyBody() {
        String sig = sign("", SECRET);
        assertTrue(validator.verify("", sig), "空 payload 也应正确验签");
    }

    @Test
    @DisplayName("verify — 含中文 payload 验签成功")
    void unicodeBody() {
        String body = "{\"title\":\"审查通过✅\"}";
        String sig = sign(body, SECRET);
        assertTrue(validator.verify(body, sig), "Unicode payload 验签应正常");
    }

    // ==================== 工具方法 ====================

    /** 生成 HMAC-SHA256 签名（模拟 GitHub 的签名方式） */
    private static String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
