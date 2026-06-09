package io.github.spojchil.proverlap.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.spojchil.proverlap.model.dto.WebhookPayload;
import io.github.spojchil.proverlap.review.ReviewOrchestrator;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * GitHub Webhook 接收端点。
 *
 * <p>接收 GitHub 发送的 {@code pull_request} 事件，验签后提取 PR 关键信息。 当前版本（P1）仅做验签 + 解析，审查链路由 P2 串联。
 *
 * <pre>
 * POST /webhook/github
 * Headers: X-Hub-Signature-256: sha256=xxx
 *          X-GitHub-Event: pull_request
 * Body:    JSON (GitHub Webhook Payload)
 * </pre>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WebhookController {

    /** 需要处理的 PR 事件动作 */
    private static final Set<String> PR_ACTIONS = Set.of("opened", "synchronize", "reopened");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebhookValidator validator;
    private final ReviewOrchestrator reviewOrchestrator;

    @PostMapping("/webhook/github")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String rawBody,
            @RequestHeader("X-Hub-Signature-256") String signature,
            @RequestHeader("X-GitHub-Event") String event) {

        // 1. 验签
        if (!validator.verify(rawBody, signature)) {
            log.warn("Webhook 签名验证失败");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        }

        // 2. 非 PR 事件直接忽略
        if (!"pull_request".equals(event)) {
            log.debug("忽略非 PR 事件: {}", event);
            return ResponseEntity.ok("ignored");
        }

        // 3. 解析 payload
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String action = root.path("action").asText();

            if (!PR_ACTIONS.contains(action)) {
                log.debug("忽略非目标 PR 动作: {}", action);
                return ResponseEntity.ok("ignored");
            }

            JsonNode pr = root.path("pull_request");
            JsonNode repo = root.path("repository");
            JsonNode installation = root.path("installation");

            WebhookPayload payload =
                    WebhookPayload.builder()
                            .action(action)
                            .prNumber(pr.path("number").asInt())
                            .fullName(repo.path("full_name").asText())
                            .installationId(installation.path("id").asLong())
                            .commitSha(pr.path("head").path("sha").asText())
                            .prTitle(pr.path("title").asText())
                            .prDescription(pr.path("body").asText(""))
                            .build();

            log.info(
                    "收到 PR Webhook: {} #{}, action={}",
                    payload.getFullName(),
                    payload.getPrNumber(),
                    payload.getAction());

            reviewOrchestrator.review(payload);

            return ResponseEntity.ok("ok");

        } catch (Exception e) {
            log.error("Webhook payload 解析失败", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid payload");
        }
    }
}
