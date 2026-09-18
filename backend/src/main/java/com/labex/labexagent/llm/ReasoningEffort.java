package com.labex.labexagent.llm;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 模型配置里「思考程度」（reasoning_effort）的规范取值。
 *
 * <p>五档与界面一一对应：低 / 中 / 高 / 超高 / 极致。{@code xhigh} 与 {@code max} 是两个不同取值，
 * 上游是否真正区分由模型本身决定；平台只负责如实写入用户选择的档位，并按模型家族标注已知的
 * 上游折叠关系（见 {@link ReasoningEffortCatalog}）。空值一律归一为 {@code medium}，保持历史默认。</p>
 */
public final class ReasoningEffort {
    public static final String LOW = "low";
    public static final String MEDIUM = "medium";
    public static final String HIGH = "high";
    public static final String XHIGH = "xhigh";
    public static final String MAX = "max";

    private static final List<Option> OPTIONS = List.of(
            new Option(LOW, "低", "更快、更省"),
            new Option(MEDIUM, "中", "推荐，默认档"),
            new Option(HIGH, "高", "更深入"),
            new Option(XHIGH, "超高", "昂贵的深度档"),
            new Option(MAX, "极致", "上游支持时才真正生效"));

    private static final Set<String> VALUES = Set.of(LOW, MEDIUM, HIGH, XHIGH, MAX);

    private ReasoningEffort() {
    }

    /** 档位与中文标签；界面文案的唯一来源，禁止在组件里再写一份。 */
    public record Option(String value, String label, String hint) {
    }

    public static List<Option> options() {
        return OPTIONS;
    }

    /** 规范顺序的档位取值，按模型家族筛选时保持界面顺序稳定。 */
    public static List<String> canonicalOrder() {
        return OPTIONS.stream().map(Option::value).toList();
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return MEDIUM;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!VALUES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "reasoningEffort must be one of: " + String.join(", ", canonicalOrder()));
        }
        return normalized;
    }

    public static boolean isValid(String value) {
        try {
            normalize(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** 未登记的取值原样返回，避免把未知档位伪装成「低」。 */
    public static String label(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return OPTIONS.stream()
                .filter(option -> option.value().equals(normalized))
                .map(Option::label)
                .findFirst()
                .orElse(normalized);
    }
}
