package com.labex.labexagent.service;

import com.google.gson.Gson;
import com.labex.entity.AgentConversation;
import com.labex.entity.StudentProject;
import com.labex.labexagent.command.CommandExecutor;
import com.labex.labexagent.command.CommandExecutor.CommandResult;
import com.labex.labexagent.command.CommandInfo;
import com.labex.labexagent.command.CommandRegistry;
import com.labex.rag.config.RagConfig;
import com.labex.rag.llm.MiniMaxChat;
import com.labex.rag.llm.OllamaChat;
import com.labex.service.StudentProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

/**
 * Agent命令服务 - 工业级别的命令处理系统
 * 完全复刻Opencode的命令架构
 */
@Service
public class AgentCommandService {
    private static final Logger log = LoggerFactory.getLogger(AgentCommandService.class);
    private static final Gson GSON = new Gson();

    private final StudentProjectService studentProjectService;
    private final AgentConversationService conversationService;
    private final CommandRegistry commandRegistry;
    private final CommandExecutor commandExecutor;
    private final RagConfig ragConfig;
    private final MiniMaxChat miniMaxChat;
    private final OllamaChat ollamaChat;

    public AgentCommandService(StudentProjectService studentProjectService,
                              AgentConversationService conversationService,
                              CommandRegistry commandRegistry,
                              CommandExecutor commandExecutor,
                              RagConfig ragConfig,
                              @Lazy MiniMaxChat miniMaxChat,
                              @Lazy OllamaChat ollamaChat) {
        this.studentProjectService = studentProjectService;
        this.conversationService = conversationService;
        this.commandRegistry = commandRegistry;
        this.commandExecutor = commandExecutor;
        this.ragConfig = ragConfig;
        this.miniMaxChat = miniMaxChat;
        this.ollamaChat = ollamaChat;

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
        String command = this.parseCommand(this.value(request, "command"), message);
        String mode = this.value(request, "mode").isBlank() ? "agent" : this.value(request, "mode");

        // 创建或获取会话
        AgentConversation conversation = this.conversationService.ensureConversation(
            studentId, project, this.value(request, "conversationId"), mode,
            message.isBlank() ? "/" + command : message
        );

        // 保存用户消息
        this.conversationService.saveUserMessage(conversation, message.isBlank() ? "/" + command : message);

        // 提取参数
        String arguments = this.extractArguments(message);

        log.info("执行命令: /{} 参数: {} 会话: {}", command, arguments, conversation.getConversationId());

        // 获取命令信息
        CommandInfo commandInfo = commandRegistry.getCommand(command);
        if (commandInfo == null) {
            throw new IllegalArgumentException("未知指令 /" + command + "。输入 /help 查看当前支持的指令。");
        }

        // 解析命令模板，将参数替换到模板中
        String resolvedTemplate = commandInfo.resolveTemplate(arguments);

        // 保存命令执行事件（将解析后的模板作为提示词）
        this.conversationService.saveEvent(conversation, "COMMAND",
            Map.of(
                "command", command,
                "template", resolvedTemplate,
                "arguments", arguments,
                "summary", "执行 /" + command + " 指令"
            ));

        // 返回结果，前端会将解析后的模板作为提示词发送给LLM
        return Map.of(
            "conversationId", conversation.getConversationId(),
            "command", command,
            "template", resolvedTemplate,
            "arguments", arguments,
            "description", commandInfo.description(),
            "subtask", commandInfo.subtask(),
            "success", true,
            "message", "命令 /" + command + " 已解析，准备发送给Agent执行"
        );
    }

    /**
     * 获取所有可用命令
     */
    public Map<String, Object> getAvailableCommands() {
        var commands = commandRegistry.getAllCommands().stream()
            .map(cmd -> Map.of(
                "name", cmd.name(),
                "description", cmd.description(),
                "source", cmd.source().name(),
                "hints", cmd.hints()
            ))
            .toList();

        var stats = commandRegistry.getStatistics();

        return Map.of(
            "commands", commands,
            "statistics", stats
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

        return Map.of(
            "name", command.name(),
            "description", command.description(),
            "template", command.template() != null ? command.template() : "",
            "source", command.source().name(),
            "agent", command.agent() != null ? command.agent() : "",
            "model", command.model() != null ? command.model() : "",
            "subtask", command.subtask(),
            "hints", command.hints()
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
    public Map<String, String> optimizePrompt(Integer studentId, Integer projectId, Map<String, String> request) {
        StudentProject project = this.requireProject(studentId, projectId);
        String original = this.value(request, "message");
        if (original.isBlank()) {
            throw new IllegalArgumentException("请输入要优化的提示词");
        }
        String activePath = this.value(request, "activePath");
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

        String optimized = "ollama".equalsIgnoreCase(this.ragConfig.getLlmProvider())
            ? this.ollamaChat.chat(system, context, original)
            : this.miniMaxChat.chat(system, context, original);
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

    private String cleanOptimizedPrompt(String optimized, String fallback) {
        if (optimized == null || optimized.isBlank()) {
            throw new IllegalArgumentException("LLM 未返回有效结果，请检查模型配置");
        }
        String trimmed = optimized.trim();
        if (trimmed.startsWith("LLM error:") || trimmed.startsWith("LLM API key not configured") || trimmed.startsWith("LLM request failed")) {
            throw new IllegalArgumentException(trimmed);
        }
        String text = trimmed;
        text = text.replaceAll("(?s)<think>.*?</think>", "").trim();
        if ((text = text.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim()).startsWith("\"") && text.endsWith("\"") || text.startsWith("'") && text.endsWith("'")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("LLM 返回内容为空，请检查模型配置");
        }
        return text;
    }
}
