package com.labex.labexagent.permission;

import java.util.List;
import java.util.Map;

public class DefaultPermissionRuleset {

    private static final List<PermissionRule> BUILD_RULES = List.of(
            new PermissionRule("*", "*", PermissionAction.ALLOW),
            new PermissionRule("read", "*.env", PermissionAction.ASK),
            new PermissionRule("read", "*.env.*", PermissionAction.ASK),
            new PermissionRule("read_file", "*.env", PermissionAction.ASK),
            new PermissionRule("read_file", "*.env.*", PermissionAction.ASK),
            new PermissionRule("read", "*.env.example", PermissionAction.ALLOW),
            new PermissionRule("read_file", "*.env.example", PermissionAction.ALLOW),
            new PermissionRule("repo_clone", "*", PermissionAction.DENY),
            new PermissionRule("shell", "rm *", PermissionAction.ASK),
            new PermissionRule("shell", "del *", PermissionAction.ASK),
            new PermissionRule("shell", "Remove-Item *", PermissionAction.ASK),
            new PermissionRule("shell", "git push*", PermissionAction.ASK),
            new PermissionRule("bash", "rm *", PermissionAction.ASK),
            new PermissionRule("bash", "git push*", PermissionAction.ASK)
    );

    public static final Map<String, List<PermissionRule>> AGENT_RULES = Map.of(
            "build", BUILD_RULES,
            "agent", BUILD_RULES,
            "plan", List.of(
                    new PermissionRule("*", "*", PermissionAction.DENY),
                    new PermissionRule("question", "*", PermissionAction.ALLOW),
                    new PermissionRule("create_plan", "*", PermissionAction.ALLOW),
                    new PermissionRule("todo_write", "*", PermissionAction.ALLOW),
                    new PermissionRule("todowrite", "*", PermissionAction.ALLOW),
                    new PermissionRule("todo", "*", PermissionAction.ALLOW),
                    new PermissionRule("task", "*", PermissionAction.ALLOW),
                    new PermissionRule("read", "*", PermissionAction.ALLOW),
                    new PermissionRule("read_file", "*", PermissionAction.ALLOW),
                    new PermissionRule("list", "*", PermissionAction.ALLOW),
                    new PermissionRule("list_files", "*", PermissionAction.ALLOW),
                    new PermissionRule("glob", "*", PermissionAction.ALLOW),
                    new PermissionRule("grep", "*", PermissionAction.ALLOW),
                    new PermissionRule("search_code", "*", PermissionAction.ALLOW),
                    new PermissionRule("retrieve_context", "*", PermissionAction.ALLOW),
                    new PermissionRule("project_overview", "*", PermissionAction.ALLOW),
                    new PermissionRule("repo_overview", "*", PermissionAction.ALLOW),
                    new PermissionRule("lsp_symbols", "*", PermissionAction.ALLOW),
                    new PermissionRule("diagnostics", "*", PermissionAction.ALLOW),
                    new PermissionRule("web_search", "*", PermissionAction.ALLOW),
                    new PermissionRule("websearch", "*", PermissionAction.ALLOW),
                    new PermissionRule("web_fetch", "*", PermissionAction.ALLOW),
                    new PermissionRule("webfetch", "*", PermissionAction.ALLOW),
                    new PermissionRule("understand_image", "*", PermissionAction.ALLOW),
                    new PermissionRule("image", "*", PermissionAction.ALLOW),
                    new PermissionRule("skill", "*", PermissionAction.ALLOW)
            ),
            "explore", List.of(
                    new PermissionRule("*", "*", PermissionAction.DENY),
                    new PermissionRule("question", "*", PermissionAction.ALLOW),
                    new PermissionRule("read", "*", PermissionAction.ALLOW),
                    new PermissionRule("read_file", "*", PermissionAction.ALLOW),
                    new PermissionRule("list", "*", PermissionAction.ALLOW),
                    new PermissionRule("list_files", "*", PermissionAction.ALLOW),
                    new PermissionRule("glob", "*", PermissionAction.ALLOW),
                    new PermissionRule("grep", "*", PermissionAction.ALLOW),
                    new PermissionRule("search_code", "*", PermissionAction.ALLOW),
                    new PermissionRule("retrieve_context", "*", PermissionAction.ALLOW),
                    new PermissionRule("project_overview", "*", PermissionAction.ALLOW),
                    new PermissionRule("repo_overview", "*", PermissionAction.ALLOW),
                    new PermissionRule("lsp_symbols", "*", PermissionAction.ALLOW),
                    new PermissionRule("diagnostics", "*", PermissionAction.ALLOW),
                    new PermissionRule("web_search", "*", PermissionAction.ALLOW),
                    new PermissionRule("websearch", "*", PermissionAction.ALLOW),
                    new PermissionRule("web_fetch", "*", PermissionAction.ALLOW),
                    new PermissionRule("webfetch", "*", PermissionAction.ALLOW),
                    new PermissionRule("understand_image", "*", PermissionAction.ALLOW),
                    new PermissionRule("image", "*", PermissionAction.ALLOW),
                    new PermissionRule("shell", "*", PermissionAction.ALLOW),
                    new PermissionRule("bash", "*", PermissionAction.ALLOW)
            )
    );

    public static List<PermissionRule> getRulesForAgent(String mode) {
        return AGENT_RULES.getOrDefault(mode, BUILD_RULES);
    }
}
