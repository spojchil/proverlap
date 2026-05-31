package io.github.spojchil.proverlap.webhook;

import io.github.spojchil.proverlap.config.GitHubProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * GitHub Webhook HMAC-SHA256 签名验证器。
 * <p>
 * 使用 Webhook Secret 对原始 payload 做 HMAC-SHA256 签名，
 * 与 GitHub 传入的 {@code X-Hub-Signature-256} 头比对。
 * 使用 {@link MessageDigest#isEqual} 做常量时间比较，防止时序攻击。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookValidator {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    private final GitHubProperties gitHubProperties;

    /**
     * 验证 Webhook 签名。
     *
     * @param rawBody   原始请求体（未做任何编码转换）
     * @param signature {@code X-Hub-Signature-256} 头的值
     * @return 签名有效返回 {@code true}
     */
    public boolean verify(String rawBody, String signature) {
        if (signature == null || !signature.startsWith(SIGNATURE_PREFIX)) {
            log.warn("Webhook 签名头缺失或格式无效");
            return false;
        }

        try {
            byte[] expected = hexToBytes(signature.substring(SIGNATURE_PREFIX.length()));
            byte[] actual = computeHmac(rawBody);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            log.error("Webhook 签名验证异常", e);
            return false;
        }
    }

    /** 对 payload 做 HMAC-SHA256 签名 */
    private byte[] computeHmac(String payload) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        SecretKeySpec keySpec = new SecretKeySpec(
                gitHubProperties.getWebhookSecret().getBytes(StandardCharsets.UTF_8),
                HMAC_ALGO);
        mac.init(keySpec);
        return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    }

    /** hex 字符串 → 字节数组 */
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return bytes;
    }
}
