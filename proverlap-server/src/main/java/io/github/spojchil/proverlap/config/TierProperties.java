package io.github.spojchil.proverlap.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tier 分级阈值配置。
 * <p>
 * Tier 分级器依据 PR diff 总行数判定审查深度：
 * Tier 1（快速通道）≤ t1MaxDiffLines，Tier 2（标准审查）≤ t2MaxDiffLines，
 * Tier 3（深度审查）> t2MaxDiffLines。
 */
@Data
@Component
@ConfigurationProperties(prefix = "proverlap.tier")
public class TierProperties {

    /** Tier 1 最大 diff 行数，≤ 此值走快速通道（默认 50） */
    private int t1MaxDiffLines = 50;

    /** Tier 2 最大 diff 行数，≤ 此值走标准审查（默认 500） */
    private int t2MaxDiffLines = 500;
}
