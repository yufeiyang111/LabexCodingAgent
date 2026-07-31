package com.labex.labexagent.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 生成不可变的 Provider 请求消息，并在发送前执行原生工具协议校验。 */
@Service
public final class AgentProviderMessageProjector {
    private final AgentProviderProtocolValidator validator;

    public AgentProviderMessageProjector() {
        this(new AgentProviderProtocolValidator());
    }

    public AgentProviderMessageProjector(AgentProviderProtocolValidator validator) {
        this.validator = validator;
    }

    public List<Map<String, Object>> project(List<Map<String, Object>> messages) {
        List<Map<String, Object>> projected = new ArrayList<>();
        if (messages != null) {
            for (Map<String, Object> message : messages) {
                projected.add(copyMap(message));
            }
        }
        validator.validateOrThrow(projected);
        return List.copyOf(projected);
    }

    public Map<String, Object> copyMessage(Map<String, Object> message) {
        return copyMap(message);
    }

    /** 交互恢复的中间状态不进行严格校验，补写 role=tool 后再校验。 */
    public List<Map<String, Object>> copyMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> copied = new ArrayList<>();
        if (messages != null) {
            for (Map<String, Object> message : messages) {
                copied.add(copyMap(message));
            }
        }
        return List.copyOf(copied);
    }

    private Map<String, Object> copyMap(Map<?, ?> source) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            copy.put(String.valueOf(entry.getKey()), copyValue(entry.getValue()));
        }
        return copy;
    }

    private Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return copyMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(copyValue(item));
            }
            return List.copyOf(copy);
        }
        return value;
    }
}
