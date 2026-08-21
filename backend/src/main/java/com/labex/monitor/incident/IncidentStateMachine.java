package com.labex.monitor.incident;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 故障状态机：OPEN / ACKNOWLEDGED / MITIGATING / RESOLVED / CLOSED，只允许合法迁移，失败返回原因。 */
@Component
public class IncidentStateMachine {

    public static final String OPEN = "OPEN";
    public static final String ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String MITIGATING = "MITIGATING";
    public static final String RESOLVED = "RESOLVED";
    public static final String CLOSED = "CLOSED";

    private static final Set<String> VALID = Set.of(OPEN, ACKNOWLEDGED, MITIGATING, RESOLVED, CLOSED);
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            OPEN, Set.of(ACKNOWLEDGED, MITIGATING, RESOLVED, CLOSED),
            ACKNOWLEDGED, Set.of(MITIGATING, RESOLVED, CLOSED),
            MITIGATING, Set.of(RESOLVED, CLOSED, ACKNOWLEDGED),
            RESOLVED, Set.of(CLOSED),
            CLOSED, Set.of());

    public String transition(String current, String target) {
        String from = normalize(current);
        String to = normalize(target);
        if (!VALID.contains(from)) {
            return "unknown incident state: " + current;
        }
        if (!VALID.contains(to)) {
            return "unknown incident state: " + target;
        }
        if (from.equals(to)) {
            return null;
        }
        if (!ALLOWED.get(from).contains(to)) {
            return "illegal incident transition: " + from + " -> " + to;
        }
        return null;
    }

    public boolean isValid(String status) {
        return VALID.contains(normalize(status));
    }

    private String normalize(String status) {
        return status == null ? "" : status.toUpperCase(Locale.ROOT);
    }
}
