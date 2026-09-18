package com.labex.labexagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 锁定「思考程度」的可用档位与上游折叠关系。
 *
 * <p>这些断言的作用是防止 UI 出现"看起来有 5 档、实际上有的档位发出去等于没变"的假象：
 * 只要有人改动档位表或模型家族映射，这里必须先失败，迫使改动是有意的。</p>
 */
class ReasoningEffortCatalogTest {

    @Test
    void acceptsMaxEffortLevel() {
        assertEquals("max", ReasoningEffort.normalize("MAX"));
        assertTrue(ReasoningEffort.isValid("max"));
        assertEquals("极致", ReasoningEffort.label("max"));
        assertEquals("超高", ReasoningEffort.label("xhigh"));
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"), ReasoningEffort.canonicalOrder());
    }

    @Test
    void deepseekV4DeclaresDocumentedRemapping() {
        List<ReasoningEffortCatalog.Option> options =
                ReasoningEffortCatalog.optionsFor("deepseek-v4-flash", null);
        Map<String, String> effective = options.stream().collect(Collectors.toMap(
                ReasoningEffortCatalog.Option::value, ReasoningEffortCatalog.Option::effective));

        // DeepSeek V4 官方文档：直接档位为 low/high/max，medium 与 xhigh 会被上游改写。
        assertEquals("high", effective.get("medium"), "medium 必须标注为等价于 high");
        assertEquals("max", effective.get("xhigh"), "xhigh 必须标注为等价于 max");
        assertEquals("low", effective.get("low"));
        assertEquals("high", effective.get("high"));
        assertEquals("max", effective.get("max"));
        assertEquals(5, options.size(), "档位数量必须与界面一致（低/中/高/超高/极致）");
    }

    @Test
    void deepseekV41UsesSameFamilyRule() {
        Map<String, String> effective = ReasoningEffortCatalog.optionsFor("deepseek-v4.1-flash", null).stream()
                .collect(Collectors.toMap(
                        ReasoningEffortCatalog.Option::value, ReasoningEffortCatalog.Option::effective));
        assertEquals("high", effective.get("medium"));
        assertEquals("max", effective.get("xhigh"));
    }

    @Test
    void unknownModelPassesEveryLevelThrough() {
        List<ReasoningEffortCatalog.Option> options = ReasoningEffortCatalog.optionsFor("some-unknown-model", null);
        assertEquals(5, options.size());
        for (ReasoningEffortCatalog.Option option : options) {
            assertEquals(option.value(), option.effective(), "未知模型不得声称存在折叠关系");
        }
    }

    @Test
    void blankModelNameMakesNoThinkingClaim() {
        assertTrue(ReasoningEffortCatalog.optionsFor(null, null).isEmpty());
        assertTrue(ReasoningEffortCatalog.optionsFor("  ", null).isEmpty());
    }

    @Test
    void explicitlyDisabledReasoningExposesNoSelectableLevel() {
        // requestOptionsJson 关闭 reasoning 时适配器不会写入任何档位，因此不得在界面上提供可选档位。
        String disabled = "{\"reasoning\":{\"enabled\":false}}";
        assertTrue(ReasoningEffortCatalog.optionsFor("deepseek-v4-flash", disabled).isEmpty());
    }

    @Test
    void allowedLevelsFromRequestOptionsAreRespected() {
        String restricted = "{\"reasoning\":{\"allowedLevels\":[\"low\",\"high\"]}}";
        List<String> values = ReasoningEffortCatalog.optionsFor("some-unknown-model", restricted).stream()
                .map(ReasoningEffortCatalog.Option::value)
                .toList();
        assertEquals(List.of("low", "high"), values);
    }

    @Test
    void everyOptionCarriesAChineseLabel() {
        Function<String, String> labelOf = value -> ReasoningEffort.options().stream()
                .filter(option -> option.value().equals(value))
                .map(ReasoningEffort.Option::label)
                .findFirst()
                .orElseThrow();
        for (ReasoningEffortCatalog.Option option : ReasoningEffortCatalog.optionsFor("deepseek-v4-flash", null)) {
            assertFalse(option.label().isBlank(), "档位必须有中文标签：" + option.value());
            assertEquals(labelOf.apply(option.value()), option.label());
        }
    }
}
