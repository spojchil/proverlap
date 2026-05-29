package io.github.spojchil.proverlap.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GitHubClient JWT 签名与私钥解析单元测试。
 * <p>
 * 测试 JWT RS256 签名生成、PKCS#8/PKCS#1 私钥解析、DER 二进制解析，
 * 不启动 Spring 上下文，不访问外部服务。
 */
@DisplayName("GitHubClient JWT 签名与私钥解析单元测试")
class GitHubClientTest {

    private static final String APP_ID = "3902868";
    private static final String PKCS1_HEADER = "-----BEGIN RSA PRIVATE KEY-----";
    private static final String PKCS1_FOOTER = "-----END RSA PRIVATE KEY-----";

    private KeyPair keyPair;
    private String pkcs1Pem;
    private String pkcs8Pem;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();

        // PKCS#1 格式 (RSA PRIVATE KEY)
        pkcs1Pem = PKCS1_HEADER + "\n"
                + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\n" + PKCS1_FOOTER;

        // PKCS#8 格式 (PRIVATE KEY)
        PKCS8EncodedKeySpec pkcs8Spec = new PKCS8EncodedKeySpec(keyPair.getPrivate().getEncoded());
        pkcs8Pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(pkcs8Spec.getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    // ==================== 私钥解析 ====================

    @Test
    @DisplayName("parsePrivateKey — PKCS#1 格式，解析后可正常签名")
    void parsePkcs1Key() throws Exception {
        PrivateKey pk = GitHubClient.parsePrivateKey(pkcs1Pem);

        assertNotNull(pk);
        assertEquals("RSA", pk.getAlgorithm());

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(pk);
        sig.update("test".getBytes());
        byte[] signed = sig.sign();

        Signature verify = Signature.getInstance("SHA256withRSA");
        verify.initVerify(keyPair.getPublic());
        verify.update("test".getBytes());
        assertTrue(verify.verify(signed), "PKCS#1 解析的私钥应能正常签名并被公钥验证");
    }

    @Test
    @DisplayName("parsePrivateKey — PKCS#8 格式，解析后可正常签名")
    void parsePkcs8Key() throws Exception {
        PrivateKey pk = GitHubClient.parsePrivateKey(pkcs8Pem);

        assertNotNull(pk);
        assertEquals("RSA", pk.getAlgorithm());

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(pk);
        sig.update("test".getBytes());
        byte[] signed = sig.sign();

        Signature verify = Signature.getInstance("SHA256withRSA");
        verify.initVerify(keyPair.getPublic());
        verify.update("test".getBytes());
        assertTrue(verify.verify(signed), "PKCS#8 解析的私钥应能正常签名并被公钥验证");
    }

    @Test
    @DisplayName("parsePrivateKey — 无效 PEM 抛 RuntimeException")
    void parseInvalidKey() {
        assertThrows(RuntimeException.class,
                () -> GitHubClient.parsePrivateKey("not-a-key"),
                "无法识别的格式应抛 RuntimeException");
    }

    // ==================== JWT 生成 ====================

    @Test
    @DisplayName("JWT 签名 — RS256 签名可被公钥验证")
    void jwtSignatureRoundTrip() throws Exception {
        PrivateKey pk = GitHubClient.parsePrivateKey(pkcs1Pem);
        long now = Instant.now().getEpochSecond();
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payload = "{\"iat\":" + now + ",\"exp\":" + (now + 600)
                + ",\"iss\":\"" + APP_ID + "\"}";
        String headerB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(header.getBytes());
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes());
        String toSign = headerB64 + "." + payloadB64;

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(pk);
        sig.update(toSign.getBytes());
        String signatureB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sig.sign());

        Signature verifySig = Signature.getInstance("SHA256withRSA");
        verifySig.initVerify(keyPair.getPublic());
        verifySig.update(toSign.getBytes());
        assertTrue(verifySig.verify(Base64.getUrlDecoder().decode(signatureB64)),
                "JWT 签名应被公钥验证通过");
    }

    @Test
    @DisplayName("JWT 结构 — 三段式 header.payload.signature")
    void jwtThreePartStructure() throws Exception {
        PrivateKey pk = GitHubClient.parsePrivateKey(pkcs1Pem);
        long now = Instant.now().getEpochSecond();
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payload = "{\"iat\":" + now + ",\"exp\":" + (now + 600)
                + ",\"iss\":\"" + APP_ID + "\"}";
        String headerB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(header.getBytes());
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes());
        String toSign = headerB64 + "." + payloadB64;

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(pk);
        sig.update(toSign.getBytes());
        String signatureB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sig.sign());

        String jwt = headerB64 + "." + payloadB64 + "." + signatureB64;
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length, "JWT 应为 3 段");

        String decodedHeader = new String(Base64.getUrlDecoder().decode(parts[0]));
        assertTrue(decodedHeader.contains("RS256"), "header 应包含 RS256");

        String decodedPayload = new String(Base64.getUrlDecoder().decode(parts[1]));
        assertTrue(decodedPayload.contains(APP_ID), "payload 应包含 iss(App ID)");
    }

    // ==================== DER 二进制解析 ====================

    @Test
    @DisplayName("DER readLength — 短格式 1 字节长度")
    void derShortLength() {
        byte[] data = {0x02, 0x01, 0x00};
        int[] pos = {0};
        GitHubClient.readTag(data, pos, (byte) 0x02);
        assertEquals(1, GitHubClient.readLength(data, pos));
    }

    @Test
    @DisplayName("DER readLength — 长格式 2 字节长度")
    void derLongLength() {
        byte[] data = {0x02, (byte) 0x82, 0x01, 0x00, 0x00};
        int[] pos = {0};
        GitHubClient.readTag(data, pos, (byte) 0x02);
        assertEquals(256, GitHubClient.readLength(data, pos));
    }

    @Test
    @DisplayName("DER readTag — 错误的 tag 抛 RuntimeException")
    void derWrongTag() {
        byte[] data = {0x30, 0x00};
        int[] pos = {0};
        assertThrows(RuntimeException.class,
                () -> GitHubClient.readTag(data, pos, (byte) 0x02),
                "期望 INTEGER(0x02) 但遇到 SEQUENCE(0x30)，应抛异常");
    }
}
