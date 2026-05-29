package io.github.spojchil.proverlap.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.ResponseSpec.ErrorHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * GitHub REST API 轻量客户端。
 * <p>
 * 封装 GitHub App 认证流程：
 * <ol>
 *   <li>私钥 + App ID 生成 JWT（RS256，10 分钟有效期）</li>
 *   <li>JWT 换取安装访问令牌（60 分钟有效期，本地缓存 50 分钟）</li>
 *   <li>使用安装令牌调用 GitHub REST API</li>
 * </ol>
 * 按安装 ID 分别缓存令牌，适用于多仓库多安装场景。
 */
@Slf4j
public class GitHubClient {

    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final Duration TOKEN_CACHE_TTL = Duration.ofMinutes(50);
    private static final Duration JWT_EXPIRY = Duration.ofMinutes(10);

    private final RestClient restClient;
    private final GitHubProperties props;
    private final ObjectMapper objectMapper;
    private final Map<Long, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public GitHubClient(RestClient.Builder restClientBuilder, GitHubProperties props) {
        this.props = props;
        this.objectMapper = new ObjectMapper();
        this.restClient = restClientBuilder
                .baseUrl(GITHUB_API_BASE)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github.v3+json")
                .build();
    }

    // ==================== 公共 API ====================

    /**
     * 获取 PR 的 unified diff 文本。
     *
     * @param owner          仓库所有者
     * @param repo           仓库名称
     * @param prNumber       PR 编号
     * @param installationId GitHub App 安装 ID（从 Webhook 负载获取）
     */
    public String getPullRequestDiff(String owner, String repo, int prNumber, long installationId) {
        String token = obtainToken(installationId);
        return restClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{number}", owner, repo, prNumber)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ACCEPT, "application/vnd.github.v3.diff")
                .retrieve()
                .body(String.class);
    }

    /**
     * 读取仓库中指定文件的内容。
     *
     * @return 文件内容文本，文件不存在时返回 {@code null}
     */
    public String getRepoFile(String owner, String repo, String path, long installationId) {
        String token = obtainToken(installationId);
        try {
            return restClient.get()
                    .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.info("仓库文件不存在: {}/{} — {}", owner, repo, path);
            return null;
        }
    }

    /**
     * 在 PR 上发布 Review Comment。
     *
     * @param body Markdown 格式的审查结果文本
     */
    public void postReview(String owner, String repo, int prNumber, String body, long installationId) {
        String token = obtainToken(installationId);
        Map<String, Object> request = Map.of("event", "COMMENT", "body", body);
        restClient.post()
                .uri("/repos/{owner}/{repo}/pulls/{number}/reviews", owner, repo, prNumber)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
        log.info("Review 已发布: {}/{}/#{}", owner, repo, prNumber);
    }

    // ==================== 令牌管理 ====================

    /** 获取安装访问令牌（优先从缓存读取） */
    private String obtainToken(long installationId) {
        CachedToken cached = tokenCache.get(installationId);
        if (cached != null && !cached.isExpired()) {
            return cached.token;
        }
        String jwt = generateJwt();
        String token = requestInstallationToken(jwt, installationId);
        tokenCache.put(installationId, new CachedToken(token, Instant.now().plus(TOKEN_CACHE_TTL)));
        return token;
    }

    /** 使用 JWT 向 GitHub API 请求安装访问令牌 */
    private String requestInstallationToken(String jwt, long installationId) {
        try {
            String response = restClient.post()
                    .uri("/app/installations/{id}/access_tokens", installationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(response).get("token").asText();
        } catch (Exception e) {
            throw new RuntimeException("GitHub 安装令牌获取失败: installationId=" + installationId, e);
        }
    }

    // ==================== JWT 生成 ====================

    /**
     * 生成 GitHub App JWT（RS256 签名）。
     * <p>
     * 使用 Java 标准库 {@link Signature} 完成签名，不依赖外部 JWT 库。
     */
    private String generateJwt() {
        try {
            PrivateKey privateKey = parsePrivateKey(props.getPrivateKey());
            long now = Instant.now().getEpochSecond();

            String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
            String payload = "{\"iat\":" + now
                    + ",\"exp\":" + (now + JWT_EXPIRY.toSeconds())
                    + ",\"iss\":\"" + props.getAppId() + "\"}";

            String headerB64 = base64Url(header);
            String payloadB64 = base64Url(payload);
            String toSign = headerB64 + "." + payloadB64;

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(toSign.getBytes(StandardCharsets.UTF_8));
            String signatureB64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(sig.sign());

            return toSign + "." + signatureB64;
        } catch (Exception e) {
            throw new RuntimeException("GitHub JWT 生成失败: " + e.getMessage(), e);
        }
    }

    private static String base64Url(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 私钥解析 ====================

    /**
     * 解析 PEM 格式 RSA 私钥。
     * <p>
     * 先尝试 PKCS#8（BEGIN PRIVATE KEY），失败则回退 PKCS#1（BEGIN RSA PRIVATE KEY）。
     * PKCS#1 解析使用自建 DER 解析器，不依赖外部 JWT 库或 JDK 内部 API。
     */
    static PrivateKey parsePrivateKey(String pem) throws GeneralSecurityException {
        String cleaned = pem
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(cleaned);

        // 尝试 PKCS#8
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (InvalidKeySpecException ignored) {
            // PKCS#1 格式，手动解析 DER
        }

        // 解析 PKCS#1 DER 序列: SEQUENCE { version(0), modulus, publicExp, privateExp, prime1, prime2, exp1, exp2, coeff }
        int[] pos = {0};
        readTag(decoded, pos, (byte) 0x30); // SEQUENCE
        int seqLen = readLength(decoded, pos);
        int seqEnd = pos[0] + seqLen;

        java.math.BigInteger[] ints = new java.math.BigInteger[9];
        for (int i = 0; i < 9; i++) {
            readTag(decoded, pos, (byte) 0x02); // INTEGER
            int len = readLength(decoded, pos);
            byte[] val = new byte[len];
            System.arraycopy(decoded, pos[0], val, 0, len);
            ints[i] = new java.math.BigInteger(+1, val); // positive
            pos[0] += len;
        }

        RSAPrivateCrtKeySpec spec = new RSAPrivateCrtKeySpec(
                ints[1], ints[2], ints[3], ints[4], ints[5], ints[6], ints[7], ints[8]);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    static void readTag(byte[] data, int[] pos, byte expected) {
        if (pos[0] >= data.length || data[pos[0]] != expected) {
            throw new RuntimeException("DER 解析失败: 期望 tag " + expected + "，实际 " +
                    (pos[0] < data.length ? data[pos[0]] : "EOF"));
        }
        pos[0]++;
    }

    static int readLength(byte[] data, int[] pos) {
        int b = data[pos[0]++] & 0xFF;
        if ((b & 0x80) == 0) {
            return b; // 短格式
        }
        int numBytes = b & 0x7F;
        int length = 0;
        for (int i = 0; i < numBytes; i++) {
            length = (length << 8) | (data[pos[0]++] & 0xFF);
        }
        return length;
    }

    // ==================== 内部类型 ====================

    /** 缓存的安装令牌 */
    private record CachedToken(String token, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
