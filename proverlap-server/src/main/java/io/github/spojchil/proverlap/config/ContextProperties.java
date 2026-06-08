package io.github.spojchil.proverlap.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 审查上下文配置属性。 */
@Data
@Component
@ConfigurationProperties(prefix = "proverlap.context")
public class ContextProperties {

    /** 最多拉取变更文件完整数量 */
    private int maxFiles = 10;

    /** 单文件完整内容最大行数 */
    private int maxFileLines = 3000;

    /** diff 文本最大字节数 */
    private int maxDiffSize = 80_000;

    /** 审查上下文总大小上限 */
    private int maxContextSize = 120_000;
}
