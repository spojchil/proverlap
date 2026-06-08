package io.github.spojchil.proverlap.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.spojchil.proverlap.model.dto.ReviewResult;
import io.github.spojchil.proverlap.review.ReviewOrchestrator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API 审查端点 — 用户指定 PR URL，同步返回审查结果。
 *
 * <pre>
 * POST /api/review
 * Content-Type: application/json
 * Body: { "prUrl": "https://github.com/owner/repo/pull/1" }
 *
 * Response:
 * {
 *   "success": true,
 *   "data": {
 *     "owner": "owner", "repo": "repo", "prNumber": 1,
 *     "tier": "TIER_2",
 *     "findings": "> **警告** `src/Foo.java` ..."
 *   }
 * }
 * </pre>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ReviewController {

    /** PR URL 正则: https://github.com/owner/repo/pull/number */
    private static final Pattern PR_URL =
            Pattern.compile("https://github\\.com/([^/]+)/([^/]+)/pull/(\\d+)");

    private final ReviewOrchestrator reviewOrchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/api/review")
    public ResponseEntity<ApiResponse> review(@RequestBody String body) {
        String prUrl;
        try {
            prUrl = objectMapper.readTree(body).path("prUrl").asText();
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.failure("INVALID_JSON", "请求体 JSON 解析失败"));
        }

        if (prUrl == null || prUrl.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.failure("MISSING_PARAM", "缺少 prUrl 字段"));
        }

        Matcher m = PR_URL.matcher(prUrl.trim());
        if (!m.matches()) {
            return ResponseEntity.badRequest()
                    .body(
                            ApiResponse.failure(
                                    "INVALID_URL",
                                    "PR URL 格式无效，期望 https://github.com/owner/repo/pull/N"));
        }

        String owner = m.group(1);
        String repo = m.group(2);
        int prNumber = Integer.parseInt(m.group(3));

        log.info("API 审查请求: {}/{} #{}", owner, repo, prNumber);

        try {
            ReviewResult result = reviewOrchestrator.reviewSync(owner, repo, prNumber);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.failure("NOT_CONFIGURED", e.getMessage()));
        } catch (Exception e) {
            log.error("API 审查失败: {}/{} #{}", owner, repo, prNumber, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.failure("REVIEW_FAILED", e.getMessage()));
        }
    }

    /** 统一 API 响应包装 */
    public record ApiResponse(boolean success, Object data, String errorCode, String message) {
        public static ApiResponse success(Object data) {
            return new ApiResponse(true, data, null, null);
        }

        public static ApiResponse failure(String errorCode, String message) {
            return new ApiResponse(false, null, errorCode, message);
        }
    }
}
