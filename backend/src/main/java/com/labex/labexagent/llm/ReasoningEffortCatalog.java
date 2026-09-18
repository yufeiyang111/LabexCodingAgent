package com.labex.labexagent.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「思考程度」可选档位的唯一事实源：告诉界面哪些档位会被如实写进请求，以及哪些档位会被上游折叠成同一档。
 *
 * <p><b>为什么需要它。</b>同一个 {@code reasoning_effort} 字段在不同模型上的语义不同：有的模型只区分
 * 2~3 档，其余档位会被服务端改写。如果界面一律展示 5 档而不做区分，用户就会看到"选了超高和极致但
 * 行为完全一样"的假控件——这正是本项目此前出现过的问题。此处的职责只有两条：</p>
 * <ol>
 *   <li>决定"这一档会不会真的被写进请求"——由 {@code requestOptionsJson.reasoning} 控制，与
 *       {@link OpenAiCompatibleChatRequestAdapter#adapt} 的判定保持同一套规则：{@code enabled=false}
 *       时不写请求，因此也不该给用户可选档位；{@code allowedLevels} 是白名单。</li>
 *   <li>标注"这一档在上游会被折叠成哪一档"——只登记有官方文档依据的家族，未登记的一律按"平台如实
 *       透传、上游行为未知"处理，不编造等价关系。</li>
 * </ol>
 *
 * <p><b>DeepSeek V4 家族的依据。</b>{@code deepseek-v4-flash} / {@code deepseek-v4-pro}（含
 * {@code deepseek-v4.1-*}）官方文档给出直接档位为 {@code low | high | max}，并明确 {@code medium} 与
 * {@code xhigh} 会被上游改写（medium→high，xhigh→max）。这是目前唯一的折叠事实；其余模型不登记。</p>
 *
 * <p>本类不得引入第二条判定路径：可用档位由它统一给出，控制器与前端都只消费它的输出。</p>
 */
public final class ReasoningEffortCatalog {

    /** value = 平台写入请求的取值；effective = 上游最终生效的取值；label = 中文文案。 */
    public record Option(String value, String label, String effective) {
    }

    private ReasoningEffortCatalog() {
    }

    /**
     * 该模型配置下界面应当提供的档位。
     *
     * @param modelName          模型 ID（如 {@code deepseek-v4.1-flash}）；为空表示尚无模型，不做任何档位声明
     * @param requestOptionsJson 该配置的高级请求 JSON；{@code reasoning.enabled=false} 时返回空列表
     * @return 可选档位；空列表表示"没有会被真正写进请求的档位"
     */
    public static List<Option> optionsFor(String modelName, String requestOptionsJson) {
        if (modelName == null || modelName.isBlank()) {
            return List.of();
        }
        JsonObject reasoning = reasoningOptions(requestOptionsJson);
        if (reasoning != null && reasoning.has("enabled")
                && reasoning.get("enabled").isJsonPrimitive()
                && !reasoning.get("enabled").getAsJsonPrimitive().isBoolean()) {
            // enabled 不是布尔值属于配置错误；适配器会按"未显式关闭"处理，这里保持一致。
            reasoning.remove("enabled");
        }
        if (reasoning != null && reasoning.has("enabled")
                && reasoning.get("enabled").isJsonPrimitive()
                && !reasoning.get("enabled").getAsBoolean()) {
            return List.of();
        }

        List<String> allowed = allowedLevels(reasoning);
        String family = familyOf(modelName);
        List<Option> options = new ArrayList<>();
        for (ReasoningEffort.Option base : ReasoningEffort.options()) {
            if (!allowed.contains(base.value())) {
                continue;
            }
            options.add(new Option(base.value(), base.label(), effectiveValue(family, base.value())));
        }
        return List.copyOf(options);
    }

    /** 是否存在会被真正写入请求的档位；供界面决定要不要渲染思考程度入口。 */
    public static boolean supportsReasoning(String modelName, String requestOptionsJson) {
        return !optionsFor(modelName, requestOptionsJson).isEmpty();
    }

    private static String effectiveValue(String family, String value) {
        if ("deepseek-v4".equals(family)) {
            // 官方文档：medium 折叠为 high，xhigh 折叠为 max。
            if (ReasoningEffort.MEDIUM.equals(value)) return ReasoningEffort.HIGH;
            if (ReasoningEffort.XHIGH.equals(value)) return ReasoningEffort.MAX;
        }
        return value;
    }

    /**
     * 只登记有文档依据的家族标识；未登记返回 {@code ""}，表示平台如实透传、不声称等价关系。
     */
    private static String familyOf(String modelName) {
        String id = modelName.trim().toLowerCase(Locale.ROOT);
        int slash = id.lastIndexOf('/');
        if (slash >= 0) {
            id = id.substring(slash + 1);
        }
        // 覆盖 deepseek-v4 / deepseek-v4-flash / deepseek-v4.1-flash / deepseek-v4-pro 等写法。
        if (id.startsWith("deepseek-v4")) {
            return "deepseek-v4";
        }
        return "";
    }

    private static List<String> allowedLevels(JsonObject reasoning) {
        if (reasoning == null || !reasoning.has("allowedLevels") || !reasoning.get("allowedLevels").isJsonArray()) {
            return ReasoningEffort.canonicalOrder();
        }
        List<String> declared = new ArrayList<>();
        for (JsonElement item : reasoning.getAsJsonArray("allowedLevels")) {
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()
                    && ReasoningEffort.isValid(item.getAsString())) {
                declared.add(ReasoningEffort.normalize(item.getAsString()));
            }
        }
        // 空数组与非法内容都退回全量档位；适配器同样在解析不出白名单时使用默认档位。
        return declared.isEmpty() ? ReasoningEffort.canonicalOrder() : declared;
    }

    private static JsonObject reasoningOptions(String requestOptionsJson) {
        if (requestOptionsJson == null || requestOptionsJson.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(requestOptionsJson);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonElement reasoning = parsed.getAsJsonObject().get("reasoning");
            return reasoning != null && reasoning.isJsonObject() ? reasoning.getAsJsonObject() : null;
        } catch (RuntimeException invalidJson) {
            // 非法 JSON 在写库前已被 AgentModelConfigService 拒绝；此处容错为"未声明"。
            return null;
        }
    }
}
