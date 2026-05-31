package io.github.spojchil.proverlap.model.enums;

import lombok.Getter;

/**
 * Tier 审查深度分级 — T1(快速通道) → T2(标准审查) → T3(深度审查)，
 * 由 TierClassifier 依据 diff 行数 + 文件敏感度判定。
 */
@Getter
public enum TierLevel {
    TIER_1("T1", "快速通道"),
    TIER_2("T2", "标准审查"),
    TIER_3("T3", "深度审查");

    private final String code;
    private final String description;

    TierLevel(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
