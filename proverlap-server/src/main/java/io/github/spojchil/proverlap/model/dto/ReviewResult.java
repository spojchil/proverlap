package io.github.spojchil.proverlap.model.dto;

import io.github.spojchil.proverlap.model.enums.TierLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** API 模式的审查结果。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewResult {

    private String owner;
    private String repo;
    private int prNumber;
    private TierLevel tier;
    private String findings;
}
