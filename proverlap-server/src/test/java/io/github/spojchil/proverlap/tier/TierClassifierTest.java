package io.github.spojchil.proverlap.tier;

import io.github.spojchil.proverlap.config.TierProperties;
import io.github.spojchil.proverlap.model.enums.TierLevel;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TierClassifier 分级判定单元测试。
 */
@DisplayName("TierClassifier 分级判定单元测试")
class TierClassifierTest {

    private TierClassifier classifier;
    private TierProperties properties;

    @BeforeEach
    void setUp() {
        properties = new TierProperties();
        properties.setT1MaxDiffLines(50);
        properties.setT2MaxDiffLines(500);
        classifier = new TierClassifier(properties);
    }

    // ==================== Tier 1 ====================

    @Test
    @DisplayName("classify — 30 行纯 .md 文件 → TIER_1")
    void pureDocs30Lines() {
        TierLevel result = classifier.classify(30,
                List.of("README.md", "docs/guide.md"));
        assertEquals(TierLevel.TIER_1, result);
    }

    @Test
    @DisplayName("classify — 10 行 .yml + .properties → TIER_1")
    void pureConfig10Lines() {
        TierLevel result = classifier.classify(10,
                List.of("application.yml", "config.properties"));
        assertEquals(TierLevel.TIER_1, result);
    }

    // ==================== Tier 2 ====================

    @Test
    @DisplayName("classify — 30 行 Java 文件 → TIER_2（非纯代码）")
    void javaFile30Lines() {
        TierLevel result = classifier.classify(30,
                List.of("src/main/Foo.java"));
        assertEquals(TierLevel.TIER_2, result);
    }

    @Test
    @DisplayName("classify — 200 行混合文件，无敏感文件 → TIER_2")
    void standard200Lines() {
        TierLevel result = classifier.classify(200,
                List.of("src/main/Foo.java", "README.md", "pom.xml"));
        assertEquals(TierLevel.TIER_2, result);
    }

    @Test
    @DisplayName("classify — 51 行 Java + .md → TIER_2（超 T1 上限但非代码混入）")
    void mixedFiles51Lines() {
        TierLevel result = classifier.classify(51,
                List.of("Foo.java", "README.md"));
        assertEquals(TierLevel.TIER_2, result);
    }

    // ==================== Tier 3 — 超大 diff ====================

    @Test
    @DisplayName("classify — 600 行 → TIER_3（超 t2MaxDiffLines）")
    void superLargeDiff() {
        TierLevel result = classifier.classify(600,
                List.of("src/main/Foo.java"));
        assertEquals(TierLevel.TIER_3, result);
    }

    // ==================== Tier 3 — 敏感文件 ====================

    @Test
    @DisplayName("classify — 10 行但含 auth/LoginService.java → TIER_3")
    void sensitiveAuthFile() {
        TierLevel result = classifier.classify(10,
                List.of("src/auth/LoginService.java"));
        assertEquals(TierLevel.TIER_3, result);
    }

    @Test
    @DisplayName("classify — SQL 迁移文件 → TIER_3")
    void sensitiveSqlFile() {
        TierLevel result = classifier.classify(5,
                List.of("migration/V1__init.sql"));
        assertEquals(TierLevel.TIER_3, result);
    }

    @Test
    @DisplayName("classify — 文件名含 Security → TIER_3")
    void sensitiveSecurityName() {
        TierLevel result = classifier.classify(5,
                List.of("src/util/SecurityUtils.java"));
        assertEquals(TierLevel.TIER_3, result);
    }

    // ==================== 边界条件 ====================

    @Test
    @DisplayName("classify — 空文件列表 → TIER_1")
    void emptyFiles() {
        TierLevel result = classifier.classify(0, List.of());
        assertEquals(TierLevel.TIER_1, result);
    }

    @Test
    @DisplayName("classify — 49 行纯 .md → TIER_1")
    void boundaryT1() {
        TierLevel result = classifier.classify(49, List.of("README.md"));
        assertEquals(TierLevel.TIER_1, result);
    }

    @Test
    @DisplayName("classify — 501 行 → TIER_3")
    void boundaryT3() {
        TierLevel result = classifier.classify(501, List.of("Foo.java"));
        assertEquals(TierLevel.TIER_3, result);
    }
}
