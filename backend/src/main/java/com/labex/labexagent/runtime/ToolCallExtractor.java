package com.labex.labexagent.runtime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * Exception performing whole class analysis ignored.
 */
public class ToolCallExtractor {
    private static final String[] KNOWN_TOOLS = new String[]{
            "list_files", "list",
            "read_file", "read",
            "write_file", "write",
            "edit_file", "edit",
            "apply_patch", "patch",
            "search_code", "grep", "glob",
            "run_command", "shell", "bash",
            "retrieve_context", "run_tests", "execute_code",
            "web_search", "websearch", "web_fetch", "webfetch",
            "understand_image", "image",
            "project_overview", "repo_overview", "repo_clone", "external_directory",
            "lsp_symbols", "diagnostics",
            "create_plan", "todo_write", "todowrite", "todo",
            "task", "question", "skill"
    };

    public static String extractToolName(String content) {
        Pattern jsonTool;
        Matcher jsonMatch;
        if (content == null || content.isEmpty()) {
            return null;
        }
        Pattern minimaxToolPattern = Pattern.compile("(?is)<\\s*(?:minimax:)?tool_call\\s*>\\s*([a-zA-Z_][\\w]*)");
        Matcher minimaxToolMatch = minimaxToolPattern.matcher(content);
        if (minimaxToolMatch.find()) {
            return minimaxToolMatch.group(1);
        }
        Pattern xmlPattern = Pattern.compile("<(?:minimax:)?invoke[^>]*\\s+name=\"([^\"]+)\"");
        Matcher xmlMatch = xmlPattern.matcher(content);
        if (xmlMatch.find()) {
            return xmlMatch.group(1);
        }
        Pattern cnPattern = Pattern.compile("\u8c03\u7528\u5de5\u5177\\s+(\\w+)\\s*[,\uff0c]");
        Matcher cnMatch = cnPattern.matcher(content);
        if (cnMatch.find()) {
            return cnMatch.group(1);
        }
        for (String tool : KNOWN_TOOLS) {
            if (!content.contains(tool + "(") && !content.contains(tool + " {") && !content.contains(tool + "\uff0c\u53c2\u6570") && !content.contains(tool + ", \u53c2\u6570") && !content.contains(tool + " \u53c2\u6570") && !content.contains(tool + " arguments")) continue;
            return tool;
        }
        if (content.contains("\"tool\"") && (jsonMatch = (jsonTool = Pattern.compile("\"tool\"\\s*:\\s*\"([^\"]+)\"")).matcher(content)).find()) {
            return jsonMatch.group(1);
        }
        return null;
    }

    public static String extractToolArgs(String content, String toolName) {
        String json;
        if (content == null || content.isEmpty()) {
            return "{}";
        }
        if (content.contains("<parameter")) {
            return ToolCallExtractor.extractXmlArgs((String)content);
        }
        int toolIdx = content.indexOf(toolName);
        if (toolIdx >= 0 && (json = ToolCallExtractor.extractFirstJsonObject((String)content, (int)(toolIdx + toolName.length()))) != null) {
            return json;
        }
        json = ToolCallExtractor.extractFirstJsonObject((String)content, 0);
        if (json != null) {
            return json;
        }
        return "{}";
    }

    private static String extractFirstJsonObject(String text, int fromIndex) {
        int start = text.indexOf("{", Math.max(0, fromIndex));
        if (start < 0) {
            return null;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); ++i) {
            char ch = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = inString;
                continue;
            }
            if (ch == '\"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (ch == '{') {
                ++depth;
                continue;
            }
            if (ch != '}' || --depth != 0) continue;
            return text.substring(start, i + 1);
        }
        return null;
    }

    private static String extractXmlArgs(String content) {
        int ne;
        int ni;
        int ps;
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        int pos = 0;
        String endTag = "</parameter>";
        while ((ps = content.indexOf("<parameter", pos)) >= 0 && (ni = content.indexOf("name=\"", ps)) >= 0 && (ne = content.indexOf("\"", ni += 6)) >= 0) {
            String pn = content.substring(ni, ne);
            int gt = content.indexOf(">", ne);
            if (gt < 0) break;
            int vs = gt + 1;
            int ve = content.indexOf(endTag, vs);
            if (ve < 0) {
                ve = content.length();
            }
            String pv = content.substring(vs, ve).trim();
            if (!first) {
                sb.append(",");
            }
            sb.append("\"").append(ToolCallExtractor.escapeJson((String)pn)).append("\":\"").append(ToolCallExtractor.escapeJson((String)pv)).append("\"");
            first = false;
            pos = ve + endTag.length();
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
