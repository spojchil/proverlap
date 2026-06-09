package io.github.spojchil.proverlap.model.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/** 交叉比对结果 — 双模型发现匹配后的分类输出。 */
@Data
@Builder
public class CrossValidationResult {

    /** 共识发现列表（双模型一致，高置信） */
    private List<Finding> consensus;

    /** 分歧发现列表（同一位置但观点不同） */
    private List<FindingPair> divergences;

    /** 仅模型 A 发现的列表 */
    private List<Finding> modelAOnly;

    /** 仅模型 B 发现的列表 */
    private List<Finding> modelBOnly;

    /** 一对分歧发现 — A 和 B 对同一位置提出不同意见。 */
    @Data
    @Builder
    public static class FindingPair {
        private Finding modelA;
        private Finding modelB;
        private String file;
        private int line;
    }
}
