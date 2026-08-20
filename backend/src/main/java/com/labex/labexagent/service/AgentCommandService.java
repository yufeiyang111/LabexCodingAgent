package com.labex.labexagent.service;

import com.google.gson.Gson;
import com.labex.entity.AgentModelConfig;
import com.labex.entity.StudentProject;
import com.labex.labexagent.command.CommandInfo;
import com.labex.labexagent.command.CommandRegistry;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.dto.PromptOptimizationRequest;
import com.labex.labexagent.llm.LlmProvider;
import com.labex.labexagent.llm.LlmProviderFactory;
import com.labex.labexagent.llm.InternalReasoningBoundary;
import com.labex.service.AgentModelConfigService;
import com.labex.service.StudentProjectService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 解析 slash command 模板；不建立第二套命令副作用运行时。
 */
@Service
public class AgentCommandService {
    private static final Logger log = LoggerFactory.getLogger(AgentCommandService.class);
    private static final Gson GSON = new Gson();

    private final StudentProjectService studentProjectService;
    private final CommandRegistry commandRegistry;
    private final AgentModelConfigService modelConfigService;
    private final LlmProviderFactory providerFactory;

    public AgentCommandService(StudentProjectService studentProjectService,
                              CommandRegistry commandRegistry,
                              AgentModelConfigService modelConfigService,
                              LlmProviderFactory providerFactory) {
        this.studentProjectService = studentProjectService;
        this.commandRegistry = commandRegistry;
        this.modelConfigService = modelConfigService;
        this.providerFactory = providerFactory;

        // 初始化时加载自定义命令
        loadCustomCommands();
    }

    /**
     * 加载自定义命令
     */
    private void loadCustomCommands() {
        // 这里可以从配置文件或数据库加载自定义命令
        // 暂时留空，后续可以扩展
        log.info("命令系统初始化完成，已注册 {} 个命令", commandRegistry.getAllCommands().size());
    }

    /**
     * 执行命令
     */
    public Map<String, Object> runCommand(Integer studentId, Integer projectId, Map<String, String> request) {
        StudentProject project = this.requireProject(studentId, projectId);
        String message = this.value(request, "message");
        String requestedCommand = this.parseCommand(this.value(request, "command"), message);
        CommandInfo commandInfo = commandRegistry.getCommand(requestedCommand);
        if (commandInfo == null) {
            throw new IllegalArgumentException("未知指令 /" + requestedCommand + "。输入 /help 查看当前支持的指令。");
        }

        String command = commandInfo.name();
        String arguments = this.extractArguments(message);
        log.info("Slash command resolved: /{} dispatch={} argumentChars={}", command, commandInfo.dispatch(), arguments.length());

        if (commandInfo.dispatch() == CommandInfo.CommandDispatch.UNAVAILABLE) {
            return Map.of(
                "command", command,
                "dispatch", commandInfo.dispatch().name(),
                "success", false,
                "message", "指令 /" + command + " 尚未接通真实执行能力：" + commandInfo.unavailableReason()
            );
        }

        if (commandInfo.dispatch() == CommandInfo.CommandDispatch.CLIENT_ACTION) {
            return Map.of(
                "command", command,
                "dispatch", commandInfo.dispatch().name(),
                "action", commandInfo.clientAction().name(),
                "arguments", arguments,
                "description", commandInfo.description(),
                "success", true,
                "message", "命令 /" + command + " 已交给客户端动作处理"
            );
        }

        String resolvedTemplate = commandInfo.resolveTemplate(arguments);
        return Map.of(
            "command", command,
            "dispatch", commandInfo.dispatch().name(),
            "template", resolvedTemplate,
            "arguments", arguments,
            "description", commandInfo.description(),
            "subtask", commandInfo.subtask(),
            "success", true,
            "message", "命令 /" + command + " 已解析，准备发送给 Agent 执行"
        );
    }
    /**
     * 校验 Slash Command 的用户可见输入与 Provider 有效 Prompt，禁止客户端伪造两套语义。
     */
    public void prepareAgentStreamRequest(Integer studentId, Integer projectId, AgentStreamRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Agent stream request is required");
        }
        String displayMessage = value(request.getDisplayMessage());
        if (displayMessage.isBlank()) {
            return;
        }
        requireProject(studentId, projectId);
        if (!displayMessage.startsWith("/")) {
            throw new IllegalArgumentException("displayMessage is only supported for slash commands");
        }

        String requestedCommand = parseCommand("", displayMessage);
        CommandInfo commandInfo = commandRegistry.getCommand(requestedCommand);
        if (commandInfo == null) {
            throw new IllegalArgumentException("未知指令 /" + requestedCommand + "。输入 /help 查看当前支持的指令。");
        }
        if (commandInfo.dispatch() != CommandInfo.CommandDispatch.AGENT_PROMPT) {
            throw new IllegalArgumentException("指令 /" + commandInfo.name() + " 不能进入 Agent prompt 运行时");
        }

        String expectedPrompt = value(commandInfo.resolveTemplate(extractArguments(displayMessage)));
        String submittedPrompt = value(request.getMessage());
        String normalizedExpected = expectedPrompt.replace("\r\n", "\n").trim();
        String normalizedSubmitted = submittedPrompt.replace("\r\n", "\n").trim();
        if (normalizedExpected.isBlank() || !normalizedExpected.equals(normalizedSubmitted)) {
            throw new IllegalArgumentException("Slash Command 的 Provider prompt 与服务端模板不一致，请重新提交");
        }
        request.setDisplayMessage(displayMessage);
        request.setMessage(expectedPrompt);
    }

    /**
     * 获取所有可用命令。
     */
    public Map<String, Object> getAvailableCommands(Integer studentId, Integer projectId) {
        this.requireProject(studentId, projectId);
        return this.getAvailableCommands();
    }
    public Map<String, Object> getAvailableCommands() {
        var commands = commandRegistry.getAllCommands().stream()
            .filter(command -> command.dispatch() != CommandInfo.CommandDispatch.UNAVAILABLE)
            .sorted(java.util.Comparator.comparing(CommandInfo::name))
            .map(command -> Map.<String, Object>of(
                "name", command.name(),
                "description", command.description(),
                "source", command.source().name(),
                "hints", command.hints(),
                "aliases", command.aliases(),
                "dispatch", command.dispatch().name(),
                "action", command.clientAction() == null ? "" : command.clientAction().name(),
                "subtask", command.subtask()
            ))
            .toList();

        return Map.of(
            "commands", commands,
            "statistics", commandRegistry.getStatistics()
        );
    }
    /**
     * 获取命令信息
     */
    public Map<String, Object> getCommandInfo(String commandName) {
        CommandInfo command = commandRegistry.getCommand(commandName);
        if (command == null) {
            return Map.of("error", "命令不存在: " + commandName);
        }

        return Map.ofEntries(
            Map.entry("name", command.name()),
            Map.entry("description", command.description()),
            Map.entry("template", command.template() != null ? command.template() : ""),
            Map.entry("source", command.source().name()),
            Map.entry("agent", command.agent() != null ? command.agent() : ""),
            Map.entry("model", command.model() != null ? command.model() : ""),
            Map.entry("subtask", command.subtask()),
            Map.entry("hints", command.hints()),
            Map.entry("aliases", command.aliases()),
            Map.entry("dispatch", command.dispatch().name()),
            Map.entry("action", command.clientAction() == null ? "" : command.clientAction().name()),
            Map.entry("available", command.dispatch() != CommandInfo.CommandDispatch.UNAVAILABLE),
            Map.entry("unavailableReason", command.unavailableReason() == null ? "" : command.unavailableReason())
        );
    }
    /**
     * 注册自定义命令
     */
    public void registerCustomCommand(String name, String description, String template,
                                     String agent, String model, boolean subtask) {
        commandRegistry.registerMcpCommand(name, description, template);
        log.info("注册自定义命令: {}", name);
    }

    /**
     * 优化提示词
     */
    public Map<String, String> optimizePrompt(Integer studentId, Integer projectId, PromptOptimizationRequest request) {
        StudentProject project = this.requireProject(studentId, projectId);
        String original = this.value(request == null ? null : request.getMessage());
        if (original.isBlank()) {
            throw new IllegalArgumentException("请输入要优化的提示词");
        }
        String activePath = this.value(request == null ? null : request.getActivePath());
        String context = "项目名称: " + project.getProjectName() + "\n当前打开文件: " +
            (activePath.isBlank() ? "未指定" : activePath) + "\n你的任务是: 分析用户的编码意图" +
            "（新建功能/修复Bug/重构/代码审查/配置/其他），将原始提示词重构为结构化的任务描述，" +
            "包含明确的目标、可执行步骤、约束条件和预期结果。";
        String system = "你是 LabexAgent 编码助手的提示词优化专家。\n" +
            "你的任务是将用户原始提示词重构为结构化、可执行的任务描述，让编程 Agent 能准确理解并高效完成。\n\n" +
            "## 优化流程\n" +
            "1. 意图分析: 判断用户想做什么（新建功能 / 修复Bug / 重构代码 / 代码审查 / 项目配置 / 调试 / 其他）\n" +
            "2. 目标提炼: 用一句话概括核心目标，去除冗余表述\n" +
            "3. 任务拆解: 将目标拆成 2-5 个具体的、可验证的子任务，按执行顺序排列\n" +
            "4. 边界定义: 明确哪些文件/模块需要修改，哪些不需要动\n" +
            "5. 验收标准: 说明完成后应该看到什么结果\n\n" +
            "## 输出格式（严格遵守）\n" +
            "**目标**: [一句话核心目标]\n" +
            "**涉及范围**: [需要修改的文件/模块，根据项目名和当前文件推断]\n" +
            "**任务**:\n" +
            "1. [具体可执行步骤]\n" +
            "2. [具体可执行步骤]\n" +
            "**约束**:\n" +
            "- [技术约束如框架、语言、不可修改的文件]\n" +
            "- [风格/模式约束]\n" +
            "**预期结果**: [完成后的具体表现或验证方式]\n\n" +
            "## 核心原则\n" +
            "- 绝不添加用户未提及的功能需求\n" +
            "- 不改变用户指定的技术栈、文件路径或命名约定\n" +
            "- 保持用户原始语言（中英文与原文一致）\n" +
            "- 即使原始提示词已经很清晰，也要按上述格式结构化输出\n" +
            "- 仅输出优化后的提示词正文，不要解释、不要加 \"优化后:\" 等前缀、不要加引号包裹\n";

        AgentModelConfig modelConfig = this.modelConfigService.resolveForStudent(
                studentId, request == null ? null : request.getModelConfigId());
        if (modelConfig == null
                || !Integer.valueOf(1).equals(modelConfig.getStatus())
                || !this.modelConfigService.hasStoredApiKey(modelConfig)) {
            throw new IllegalArgumentException("请先配置一个启用的模型服务后再优化提示词");
        }

        LlmProvider provider = this.providerFactory.resolveProvider(modelConfig);
        LlmProvider.LlmConfig llmConfig = this.providerFactory.buildConfig(modelConfig);
        Map<String, Object> response = provider.chatWithTools(
                system,
                List.of(Map.<String, Object>of("role", "user", "content", context + "\n\n原始提示词:\n" + original)),
                List.of(),
                llmConfig);
        String optimized = this.extractOptimizedPrompt(response);
        optimized = this.cleanOptimizedPrompt(optimized, original);
        return Map.of("optimizedPrompt", optimized);
    }

    // ==================== 辅助方法 ====================

    private String extractArguments(String message) {
        if (message == null || message.isBlank()) return "";
        String trimmed = message.trim();
        if (trimmed.startsWith("/")) {
            int spaceIdx = trimmed.indexOf(' ');
            if (spaceIdx > 0) {
                return trimmed.substring(spaceIdx + 1).trim();
            }
        }
        return "";
    }

    private String parseCommand(String explicit, String message) {
        String raw = explicit != null && !explicit.isBlank() ? explicit : message;
        if (raw == null) return "";
        if ((raw = raw.trim()).startsWith("/")) {
            raw = raw.substring(1);
        }
        int space = raw.indexOf(' ');
        if (space >= 0) {
            raw = raw.substring(0, space);
        }
        return raw.toLowerCase(Locale.ROOT);
    }

    private StudentProject requireProject(Integer studentId, Integer projectId) {
        StudentProject project = this.studentProjectService.getOwnedProject(studentId, projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found");
        }
        return project;
    }

    private String value(Map<String, String> request, String key) {
        if (request == null) return "";
        String value = request.get(key);
        return value == null ? "" : value.trim();
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }

    private String extractOptimizedPrompt(Map<String, Object> response) {
        if (response == null) {
            throw new IllegalArgumentException("LLM 未返回有效结果，请检查模型配置");
        }
        Object content = response.get("content");
        if (content != null && !content.toString().isBlank()) {
            return content.toString();
        }
        Object message = response.get("message");
        if (message != null && !message.toString().isBlank()) {
            throw new IllegalArgumentException(message.toString());
        }
        throw new IllegalArgumentException("LLM 未返回有效结果，请检查模型配置");
    }

    private String cleanOptimizedPrompt(String optimized, String fallback) {
        if (optimized == null || optimized.isBlank()) {
            throw new IllegalArgumentException("LLM 未返回有效结果，请检查模型配置");
        }
        String trimmed = optimized.trim();
        if (trimmed.startsWith("LLM error:") || trimmed.startsWith("LLM API key not configured") || trimmed.startsWith("LLM request failed")) {
            throw new IllegalArgumentException(trimmed);
        }
        String text = InternalReasoningBoundary.stripVisible(trimmed).trim();
        if ((text = text.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim()).startsWith("\"") && text.endsWith("\"") || text.startsWith("'") && text.endsWith("'")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("LLM 返回内容为空，请检查模型配置");
        }
        return text;
    }
}
