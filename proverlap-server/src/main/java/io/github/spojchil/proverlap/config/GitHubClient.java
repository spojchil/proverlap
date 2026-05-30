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
        return fetchDiffWithAuth(owner, repo, prNumber, token);
    }

    /**
     * 获取 PR diff — 无需安装 ID（API 模式）。
     * <p>
     * 按优先级尝试三种认证方式：
     * <ol>
     *   <li>GitHub App Installation Token（需配置 installationId，Webhook 模式）</li>
     *   <li>Personal Access Token（需配置 token，API 模式最低配置）</li>
     * </ol>
     *
     * @throws IllegalStateException 两种方式均未配置或均失败
     */
    public String getPullRequestDiff(String owner, String repo, int prNumber) {
        // 渠道 1：GitHub App Installation Token
        if (props.getInstallationId() != null) {
            try {
                return getPullRequestDiff(owner, repo, prNumber, props.getInstallationId());
            } catch (Exception e) {
                log.warn("Installation Token 方式失败: {}", e.getMessage());
            }
        }

        // 渠道 2：Personal Access Token
        if (props.getToken() != null && !props.getToken().isBlank()) {
            try {
                return fetchDiffWithAuth(owner, repo, prNumber, props.getToken());
            } catch (Exception e) {
                log.warn("PAT Token 方式失败: {}", e.getMessage());
                throw new IllegalStateException("PAT Token 认证失败，请检查 GITHUB_TOKEN 是否正确。错误: " + e.getMessage(), e);
            }
        }

        // 两种方式都未配置 → 给出清晰的配置指引
        String msg = "未配置 GitHub 认证。请配置以下任一方式：\n"
                + "  环境变量 GITHUB_TOKEN=ghp_xxx\n"
                + "    → 在 GitHub Settings → Developer settings → Personal access tokens 生成\n"
                + "    → 勾选 public_repo 权限（公开仓库）或 repo 权限（私有仓库）\n"
                + "  环境变量 GITHUB_INSTALLATION_ID=xxx\n"
                + "    → GitHub App 安装 ID（Webhook 模式自动注入，API 模式手动配置）\n"
                + "  当前配置: installationId="
                + (props.getInstallationId() != null ? "已配置" : "未配置")
                + ", token=" + (props.getToken() != null ? "已配置" : "未配置");
        throw new IllegalStateException(msg);
    }

    /** 使用指定 token 调用 GitHub API 获取 diff */
    private String fetchDiffWithAuth(String owner, String repo, int prNumber, String token) {
        return restClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{number}", owner, repo, prNumber)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ACCEPT, "application/vnd.github.v3.diff")
                .retrieve()
                .body(String.class);
    }

    /**
     * 读取仓库中指定文件的内容（App 安装令牌认证）。
     *
     * @return 文件内容文本，文件不存在时返回 {@code null}
     */
    public String getRepoFile(String owner, String repo, String path, long installationId) {
        String token = obtainToken(installationId);
        return fetchRepoFile(owner, repo, path, token);
    }

    /**
     * 读取仓库中指定文件的内容（API 模式，2 通道认证）。
     *
     * @return 文件内容文本，文件不存在时返回 {@code null}
     */
    public String getRepoFile(String owner, String repo, String path) {
        return getRepoFile(owner, repo, path, (String) null);
    }

    /**
     * 读取仓库中指定文件的内容（API 模式，指定分支）。
     *
     * @param ref 分支名或 commit SHA，为 null 时走默认分支
     */
    public String getRepoFile(String owner, String repo, String path, String ref) {
        // 渠道 1：Installation Token
        if (props.getInstallationId() != null) {
            try {
                return getRepoFile(owner, repo, path, props.getInstallationId());
            } catch (Exception e) {
                log.warn("Installation Token 读取文件失败: {}", e.getMessage());
            }
        }
        // 渠道 2：PAT
        if (props.getToken() != null && !props.getToken().isBlank()) {
            return fetchRepoFile(owner, repo, path, props.getToken(), ref);
        }
        log.warn("未配置 GitHub 认证，无法读取文件: {}/{}", repo, path);
        return null;
    }

    /**
     * 获取 PR 的 head 分支名，用于上下文文件拉取。
     */
    public String getPrBranch(String owner, String repo, int prNumber) {
        String token = null;
        if (props.getInstallationId() != null) {
            try {
                token = obtainToken(props.getInstallationId());
            } catch (Exception ignored) {}
        }
        if (token == null && props.getToken() != null) {
            token = props.getToken();
        }
        if (token == null) {
            log.warn("无法获取 PR 分支信息：未配置认证");
            return null;
        }
        try {
            String json = restClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{number}", owner, repo, prNumber)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(json).path("head").path("ref").asText();
        } catch (Exception e) {
            log.warn("获取 PR 分支信息失败: {}", e.getMessage());
            return null;
        }
    }

    private String fetchRepoFile(String owner, String repo, String path, String token, String ref) {
        try {
            String uri = "/repos/{owner}/{repo}/contents/{path}";
            if (ref != null) uri += "?ref=" + ref;
            return restClient.get()
                    .uri(uri, owner, repo, path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.info("仓库文件不存在: {}/{}", repo, path);
            return null;
        }
    }

    private String fetchRepoFile(String owner, String repo, String path, String token) {
        return fetchRepoFile(owner, repo, path, token, null);
    }

    /**
     * 在 PR 上发布 Review Comment。
     *
     * @param body Markdown 格式的审查结果文本
     */
    // ==================== Check Runs ====================

    /**
     * 创建 Check Run（审查开始）。
     *
     * @param commitSha PR head commit SHA（从 Webhook payload 获取）
     * @return check run ID，用于后续更新
     */
    public Long createCheckRun(String owner, String repo, String commitSha, long installationId) {
        String token = obtainToken(installationId);
        Map<String, Object> body = Map.of(
                "name", "PRoverlap",
                "head_sha", commitSha,
                "status", "in_progress",
                "started_at", java.time.Instant.now().toString()
        );
        var response = restClient.post()
                .uri("/repos/{owner}/{repo}/check-runs", owner, repo)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        Long id = ((Number) response.get("id")).longValue();
        log.info("Check run 已创建: id={}, sha={}", id, commitSha.substring(0, 7));
        return id;
    }

    /**
     * 更新 Check Run 状态（审查完成）。
     *
     * @param conclusion 完成结论: success / failure / neutral
     */
    public void updateCheckRun(String owner, String repo, long checkRunId, String conclusion,
                               String title, String summary, long installationId) {
        String token = obtainToken(installationId);
        Map<String, Object> body = Map.of(
                "name", "PRoverlap",
                "status", "completed",
                "conclusion", conclusion,
                "completed_at", java.time.Instant.now().toString(),
                "output", Map.of("title", title, "summary", summary)
        );
        restClient.patch()
                .uri("/repos/{owner}/{repo}/check-runs/{id}", owner, repo, checkRunId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        log.info("Check run 已更新: id={}, conclusion={}", checkRunId, conclusion);
    }

    // ==================== Review Comment ====================

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
            PrivateKey privateKey;
            if (props.getPrivateKeyB64() != null && !props.getPrivateKeyB64().isBlank()) {
                privateKey = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(props.getPrivateKeyB64())));
            } else {
                privateKey = parsePrivateKey(props.getPrivateKey());
            }
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
     * 根据 PEM 头区分格式：PKCS#1（BEGIN RSA PRIVATE KEY）走自建 DER 解析，
     * PKCS#8（BEGIN PRIVATE KEY）走标准 PKCS8EncodedKeySpec。
     */
    static PrivateKey parsePrivateKey(String pem) throws GeneralSecurityException {
        boolean isPkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        boolean isPkcs8 = pem.contains("BEGIN PRIVATE KEY");

        String cleaned = pem
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(cleaned);

        // 根据 PEM 头选择解析方式
        if (isPkcs1) {
            try {
                return parsePkcs1(decoded);
            } catch (Exception e) {
                // DER 解析失败：可能是 Runtime（格式不匹配）或 GeneralSecurity（校验失败），
                // 两种情况下都尝试 PKCS#8 回退（处理"PKCS#1 头 + PKCS#8 内容"的误标注场景）
                if (!isPkcs8) {
                    return KeyFactory.getInstance("RSA")
                            .generatePrivate(new PKCS8EncodedKeySpec(decoded));
                }
                throw e;
            }
        }

        return KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    /** 解析 PKCS#1 DER 序列: SEQUENCE { version, modulus, publicExp, privateExp, prime1, prime2, exp1, exp2, coeff } */
    private static PrivateKey parsePkcs1(byte[] decoded) throws GeneralSecurityException {
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

        // 验证 PKCS#1 结构合理性: version==0, public exponent 应在合理范围
        if (ints[0].intValue() != 0 || ints[2].bitLength() > 64) {
            throw new GeneralSecurityException("PKCS#1 结构校验失败，可能为非 PKCS#1 数据");
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
