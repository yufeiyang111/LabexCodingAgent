package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.entity.AgentPreviewRun;
import com.labex.entity.AgentRunPart;
import com.labex.mapper.AgentPreviewRunMapper;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class PlanVerificationEvidenceService {
    private static final Pattern EXPLICIT_TARGET = Pattern.compile(
            "(?i)\\b(?:target(?:_path)?|path|module|file)\\s*[:=]\\s*([a-z0-9_./-]+)");
    private static final Pattern FILE_TARGET = Pattern.compile(
            "(?i)(?:^|[\\s`])([a-z0-9_.-]+(?:/[a-z0-9_.-]+)+\\.(?:java|js|jsx|ts|tsx|vue|py|go|rs|rb|php|cs|kt|kts|xml|json|yml|yaml|html|css|scss))(?:$|[\\s`,])");

    private final AgentRunPartService partService;
    private final AgentPreviewRunMapper previewRunMapper;

    public PlanVerificationEvidenceService(AgentRunPartService partService,
                                           AgentPreviewRunMapper previewRunMapper) {
        this.partService = partService;
        this.previewRunMapper = previewRunMapper;
    }

    public Assessment assess(Long taskId, long executionEpoch, AgentRunPlanService.PlanItem item) {
        Requirement requirement = Requirement.from(item);
        if (!requirement.required()) {
            return new Assessment(false, true, "", requirement.kind().wireName(), requirement.target(), "");
        }
        if (taskId == null || taskId <= 0 || partService == null) {
            return new Assessment(true, false, "verification_missing", requirement.kind().wireName(), requirement.target(), "");
        }
        List<AgentRunPart> currentEpoch = partService.currentEpochToolHistory(taskId, executionEpoch);
        currentEpoch = currentEpoch == null ? List.of() : currentEpoch.stream()
                .filter(part -> "completed".equals(normalize(part.getStatus())))
                .toList();
        if (requirement.kind() == Kind.PREVIEW) {
            return assessPreview(taskId, requirement, currentEpoch);
        }
        if (requirement.kind() == Kind.GENERIC && !requirement.target().isBlank()) {
            for (AgentRunPart part : currentEpoch) {
                if (!"read_file".equals(normalize(part.getToolName())) || !manualFileProof(part.getOutputText())) {
                    continue;
                }
                if (matchesTarget(requirement.target(), targetFromInput(part.getInputJson()))) {
                    return new Assessment(true, true, "", requirement.kind().wireName(), requirement.target(), "read_file");
                }
            }
        }
        boolean anyRunTests = false;
        boolean kindMatched = false;
        for (AgentRunPart part : currentEpoch) {
            if (!"run_tests".equals(normalize(part.getToolName()))) {
                continue;
            }
            anyRunTests = true;
            if (!matchesKind(part, requirement.kind())) {
                continue;
            }
            kindMatched = true;
            if (!matchesTarget(requirement.target(), targetFromInput(part.getInputJson()))) {
                continue;
            }
            return new Assessment(true, true, "", requirement.kind().wireName(), requirement.target(), "run_tests");
        }
        String reason = !anyRunTests ? "verification_missing"
                : (!kindMatched ? "verification_kind_mismatch" : "verification_target_mismatch");
        return new Assessment(true, false, reason, requirement.kind().wireName(), requirement.target(), "");
    }

    private Assessment assessPreview(Long taskId, Requirement requirement, List<AgentRunPart> currentEpoch) {
        boolean previewPartReady = currentEpoch.stream()
                .filter(part -> "start_preview".equals(normalize(part.getToolName())))
                .anyMatch(part -> outputIsReadyPreview(part.getOutputText()));
        if (!previewPartReady || previewRunMapper == null) {
            return new Assessment(true, false, "preview_not_ready", requirement.kind().wireName(), requirement.target(), "");
        }
        List<AgentPreviewRun> previews = previewRunMapper.selectList(new LambdaQueryWrapper<AgentPreviewRun>()
                .eq(AgentPreviewRun::getTaskId, taskId)
                .eq(AgentPreviewRun::getStatus, "ready")
                .orderByDesc(AgentPreviewRun::getReadyAt));
        boolean ready = previews != null && previews.stream()
                .anyMatch(preview -> preview.getPublicUrl() != null && !preview.getPublicUrl().isBlank());
        return ready
                ? new Assessment(true, true, "", requirement.kind().wireName(), requirement.target(), "start_preview")
                : new Assessment(true, false, "preview_not_ready", requirement.kind().wireName(), requirement.target(), "");
    }

    private boolean matchesKind(AgentRunPart part, Kind kind) {
        if (kind == Kind.GENERIC) {
            return true;
        }
        String fingerprint = normalize(part.getInputJson() + "\n" + part.getOutputText());
        return switch (kind) {
            case BUILD -> fingerprint.contains("build") || fingerprint.contains("compile") || fingerprint.contains("package");
            case TEST -> fingerprint.contains("test") || fingerprint.contains("pytest") || fingerprint.contains("jest")
                    || fingerprint.contains("vitest");
            case LINT -> fingerprint.contains("lint") || fingerprint.contains("eslint") || fingerprint.contains("checkstyle");
            case PREVIEW, GENERIC -> false;
        };
    }

    private boolean matchesTarget(String requiredTarget, String evidenceTarget) {
        if (requiredTarget.isBlank()) {
            return true;
        }
        if (evidenceTarget.isBlank()) {
            return false;
        }
        String expected = normalizeTarget(requiredTarget);
        String actual = normalizeTarget(evidenceTarget);
        return expected.equals(actual) || actual.startsWith(expected + "/") || expected.startsWith(actual + "/");
    }

    private String targetFromInput(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return "";
        }
        try {
            JsonObject input = JsonParser.parseString(inputJson).getAsJsonObject();
            for (String key : List.of("target_path", "targetPath", "path", "module", "file")) {
                JsonElement value = input.get(key);
                if (value != null && !value.isJsonNull() && value.isJsonPrimitive()) {
                    String target = value.getAsString();
                    if (target != null && !target.isBlank()) {
                        return target.trim();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            return "";
        }
        return "";
    }

    private boolean outputIsReadyPreview(String output) {
        String normalized = normalize(output);
        return normalized.contains("preview_status=ready") && normalized.contains("preview_url=");
    }

    private boolean manualFileProof(String output) {
        String normalized = normalize(output);
        return normalized.contains("[read_file path=") && normalized.contains("sha256=");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeTarget(String value) {
        String normalized = normalize(value).replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized.replaceAll("/+", "/");
    }

    public record Assessment(boolean required, boolean satisfied, String reasonCode,
                             String requirementKind, String requiredTarget, String evidenceTool) {
    }

    private record Requirement(boolean required, Kind kind, String target) {
        private static Requirement from(AgentRunPlanService.PlanItem item) {
            String text = normalize((item == null ? "" : item.title()) + " "
                    + (item == null ? "" : item.description()));
            boolean required = containsAny(text, "verify", "verification", "test", "build", "compile", "lint",
                    "\u9a8c\u8bc1", "\u6d4b\u8bd5", "\u6784\u5efa", "\u7f16\u8bd1", "\u9884\u89c8", "\u53ef\u8bbf\u95ee");
            if (!required) {
                return new Requirement(false, Kind.GENERIC, "");
            }
            Kind kind = containsAny(text, "preview", "http", "url", "homepage", "home page", "\u9996\u9875", "\u4e3b\u9875", "\u9884\u89c8", "\u53ef\u8bbf\u95ee")
                    ? Kind.PREVIEW
                    : (containsAny(text, "lint", "eslint", "checkstyle") ? Kind.LINT
                    : (containsAny(text, "build", "compile", "package", "\u6784\u5efa", "\u7f16\u8bd1") ? Kind.BUILD
                    : (containsAny(text, "test", "pytest", "jest", "vitest", "\u6d4b\u8bd5") ? Kind.TEST : Kind.GENERIC)));
            return new Requirement(true, kind, extractTarget(text));
        }

        private static String extractTarget(String text) {
            Matcher explicit = EXPLICIT_TARGET.matcher(text);
            if (explicit.find()) {
                return explicit.group(1);
            }
            Matcher file = FILE_TARGET.matcher(text);
            return file.find() ? file.group(1) : "";
        }

        private static boolean containsAny(String text, String... values) {
            for (String value : values) {
                if (text.contains(value)) {
                    return true;
                }
            }
            return false;
        }
    }

    private enum Kind {
        GENERIC("generic"),
        TEST("test"),
        BUILD("build"),
        LINT("lint"),
        PREVIEW("preview");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        private String wireName() {
            return wireName;
        }
    }
}
