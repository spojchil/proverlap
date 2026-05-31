package io.github.spojchil.proverlap.model.enums;

/**
 * 审查模式 — 控制审查结果对 PR 合并的影响程度。
 * <ul>
 *   <li>COMMENT_ONLY — 仅发评论，不创建 check run</li>
 *   <li>BLOCK_UNTIL_REVIEWED — 审查完成前阻塞合并</li>
 *   <li>BLOCK_ON_FINDINGS — 有阻断级问题时阻塞合并</li>
 * </ul>
 */
public enum ReviewMode {
    COMMENT_ONLY,
    BLOCK_UNTIL_REVIEWED,
    BLOCK_ON_FINDINGS
}
