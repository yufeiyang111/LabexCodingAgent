package com.labex.labexagent.command;

import com.labex.labexagent.command.CommandInfo.CommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命令模板注册表；副作用统一交给 Agent 工具与审批运行时。
 */
@Component
public class CommandRegistry {
    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);

    // 命令存储
    private final Map<String, CommandInfo> commands = new ConcurrentHashMap<>();
    // 命令别名映射
    private final Map<String, String> aliasMap = new ConcurrentHashMap<>();

    public CommandRegistry() {
        registerBuiltinCommands();
    }

    /**
     * 注册所有内置命令
     */
    private void registerBuiltinCommands() {
        // ==================== 服务端LLM提示命令 ====================
        registerServiceCommands();

        // ==================== 会话管理命令 ====================
        registerSessionCommands();

        // ==================== Agent/Model管理命令 ====================
        registerAgentModelCommands();

        // ==================== 系统命令 ====================
        registerSystemCommands();

        // ==================== 开发工作流命令 ====================
        registerWorkflowCommands();

        // ==================== 文件操作命令 ====================
        registerFileCommands();

        // ==================== Git命令 ====================
        registerGitCommands();

        // ==================== 测试命令 ====================
        registerTestCommands();

        // ==================== 部署命令 ====================
        registerDeployCommands();

        // ==================== 分析命令 ====================
        registerAnalysisCommands();

        log.info("已注册 {} 个内置命令", commands.size());
    }

    /**
     * 注册服务端LLM提示命令
     */
    private void registerServiceCommands() {
        // /init - 引导式LabexAgent.md创建/更新
        String initTemplate = loadTemplate("initialize.txt");
        register(CommandInfo.builtin("init", "引导式 LabexAgent.md 创建/更新", initTemplate));

        // /review - 代码审查
        String reviewTemplate = loadTemplate("review.txt");
        register(CommandInfo.builtinSubtask("review", "代码审查 [commit|branch|pr]，默认审查未提交更改", reviewTemplate));

        // /goal - 长时自主目标
        register(CommandInfo.builtin("goal", "设定长时自主目标，不达成目标不停止执行",
            "你正在执行 /goal 长时自主目标指令。\n目标描述: $ARGUMENTS\n请自主分解并按步推进，使用必要的工具进行修改、测试与验证，直到完全达成该目标为止。"));

        // /plan - 技术实施计划
        String planTemplate = loadTemplate("plan.txt");
        register(CommandInfo.builtin("plan", "生成详细的技术实施计划与任务分解", planTemplate));

        // /browser - 网络检索与探索
        register(CommandInfo.builtin("browser", "调用浏览器与网络探索",
            "你正在执行 /browser 检索指令。\n检索目标: $ARGUMENTS\n请使用 WebSearch 与 Fetch 相关工具检索外部网络信息与最新文档，并给出详细的分析与结论。"));
    }

    /**
     * 注册会话管理命令
     */
    private void registerSessionCommands() {
        register(CommandInfo.clientAction("sessions", "切换会话", CommandInfo.ClientAction.SESSION_LIST));
        register(CommandInfo.clientAction("new", "新建会话", CommandInfo.ClientAction.SESSION_NEW, "clear"));
        register(CommandInfo.clientAction("compact", "压缩当前会话上下文",
            CommandInfo.ClientAction.CONVERSATION_COMPACT, "summarize"));
        register(CommandInfo.clientAction("fork", "分叉会话", CommandInfo.ClientAction.CONVERSATION_FORK));
        register(CommandInfo.clientAction("copy", "复制会话记录", CommandInfo.ClientAction.CONVERSATION_COPY));
        register(CommandInfo.clientAction("export", "导出会话记录", CommandInfo.ClientAction.CONVERSATION_EXPORT));
        register(CommandInfo.clientAction("timestamps", "切换时间戳显示",
            CommandInfo.ClientAction.TOGGLE_TIMESTAMPS, "toggle-timestamps"));
        register(CommandInfo.clientAction("thinking", "切换思考过程显示",
            CommandInfo.ClientAction.TOGGLE_THINKING, "toggle-thinking"));
    }

    /**
     * 注册Agent/Model管理命令
     */
    private void registerAgentModelCommands() {
        register(CommandInfo.clientAction("models", "切换模型", CommandInfo.ClientAction.MODEL_SETTINGS));
        register(CommandInfo.clientAction("mcps", "管理 MCP 服务器", CommandInfo.ClientAction.MCP_PANEL));
        register(CommandInfo.clientAction("connect", "连接 Provider", CommandInfo.ClientAction.MODEL_SETTINGS));
    }

    /**
     * 注册系统命令
     */
    private void registerSystemCommands() {
        register(CommandInfo.clientAction("status", "查看项目状态", CommandInfo.ClientAction.PROJECT_STATUS));
        register(CommandInfo.clientAction("help", "显示帮助", CommandInfo.ClientAction.COMMAND_HELP));
        register(CommandInfo.clientAction("exit", "退出工作区", CommandInfo.ClientAction.WORKSPACE_EXIT, "quit", "q"));
        register(CommandInfo.clientAction("themes", "切换主题", CommandInfo.ClientAction.THEME_SETTINGS));
        register(CommandInfo.clientAction("skills", "打开技能选择器", CommandInfo.ClientAction.SKILLS_PANEL));
        register(CommandInfo.clientAction("diff", "打开差异查看器", CommandInfo.ClientAction.CHANGES_PANEL));
    }

    /**
     * 注册开发工作流命令
     */
    private void registerWorkflowCommands() {
        // /fix - 修复问题
        String fixTemplate = loadTemplate("fix.txt");
        register(CommandInfo.builtin("fix", "诊断并修复代码问题", fixTemplate));

        // /explain - 解释代码
        String explainTemplate = loadTemplate("explain.txt");
        register(CommandInfo.builtin("explain", "深度解释代码与架构原理", explainTemplate));

        // /refactor - 重构代码
        String refactorTemplate = loadTemplate("refactor.txt");
        register(CommandInfo.builtin("refactor", "重构指定代码以提高质量", refactorTemplate));

        // /optimize - 优化性能
        String optimizeTemplate = loadTemplate("optimize.txt");
        register(CommandInfo.builtin("optimize", "分析并优化性能瓶颈", optimizeTemplate));

        // /clean - 清理项目
        register(CommandInfo.builtin("clean", "清理项目", "清理构建产物和临时文件。"));

        // /reset - 重置项目
        register(CommandInfo.builtin("reset", "重置项目", "重置项目到初始状态。"));
    }

    /**
     * 注册文件操作命令
     */
    private void registerFileCommands() {
        // /create - 创建文件/目录
        register(CommandInfo.builtin("create", "创建文件/目录", "创建新文件或目录。使用: /create file|dir 名称"));

        // /delete - 删除文件/目录
        register(CommandInfo.builtin("delete", "删除文件/目录", "删除指定文件或目录。使用: /delete 路径"));

        // /rename-file - 重命名文件
        register(CommandInfo.builtin("rename-file", "重命名文件", "重命名文件或目录。使用: /rename-file 旧名称 新名称"));

        // /search - 搜索代码
        register(CommandInfo.builtin("search", "搜索代码", "在项目中搜索代码。使用: /search 模式"));

        // /move - 移动文件
        register(CommandInfo.builtin("move", "移动文件", "移动文件到新位置。使用: /move 源路径 目标路径"));

        // /copy-file - 复制文件
        register(CommandInfo.builtin("copy-file", "复制文件", "复制文件。使用: /copy-file 源路径 目标路径"));
    }

    /**
     * 注册Git命令
     */
    private void registerGitCommands() {
        // /git - Git命令
        register(CommandInfo.builtin("git", "执行Git命令", "执行Git命令。使用: /git 命令"));

        // /commit - 提交更改
        register(CommandInfo.builtin("commit", "提交更改", "提交当前更改。使用: /commit 提交信息"));

        // /push - 推送更改
        register(CommandInfo.builtin("push", "推送更改", "推送本地更改到远程仓库。"));

        // /pull - 拉取更改
        register(CommandInfo.builtin("pull", "拉取更改", "从远程仓库拉取更改。"));

        // /branch - 分支管理
        register(CommandInfo.builtin("branch", "分支管理", "管理Git分支。使用: /branch create|delete|list 名称"));

        // /merge - 合并分支
        register(CommandInfo.builtin("merge", "合并分支", "合并指定分支到当前分支。使用: /merge 分支名"));

        // /stash - 暂存更改
        register(CommandInfo.builtin("stash", "暂存更改", "暂存当前更改。使用: /stash push|pop|list"));
    }

    /**
     * 注册测试命令
     */
    private void registerTestCommands() {
        // /test - 运行测试
        register(CommandInfo.builtin("test", "运行测试", "运行测试套件。使用: /test 模式或文件路径"));

        // /test-file - 为文件生成测试
        String testFileTemplate = loadTemplate("test_file.txt");
        register(CommandInfo.builtin("test-file", "为指定文件生成高质量自动化测试", testFileTemplate));

        // /coverage - 测试覆盖率
        register(CommandInfo.builtin("coverage", "测试覆盖率", "运行测试并生成覆盖率报告。"));

        // /benchmark - 性能基准测试
        register(CommandInfo.builtin("benchmark", "性能基准测试", "运行性能基准测试。使用: /benchmark 目标"));
    }

    /**
     * 注册部署命令
     */
    private void registerDeployCommands() {
        // /deploy - 部署项目
        register(CommandInfo.builtin("deploy", "部署项目", "部署项目到指定环境。使用: /deploy 目标环境"));

        // /build - 构建项目
        register(CommandInfo.builtin("build", "构建项目", "构建项目。"));

        // /start - 启动服务
        register(CommandInfo.builtin("start", "启动服务", "启动项目服务。"));

        // /stop - 停止服务
        register(CommandInfo.builtin("stop", "停止服务", "停止项目服务。"));

        // /restart - 重启服务
        register(CommandInfo.builtin("restart", "重启服务", "重启项目服务。"));

        // /logs - 查看日志
        register(CommandInfo.builtin("logs", "查看日志", "查看应用日志。使用: /logs 选项"));
    }

    /**
     * 注册分析命令
     */
    private void registerAnalysisCommands() {
        // /lint - 代码检查
        register(CommandInfo.builtin("lint", "代码检查", "运行代码检查工具。使用: /lint 路径"));

        // /format - 代码格式化
        register(CommandInfo.builtin("format", "代码格式化", "格式化代码。使用: /format 路径"));

        // /typecheck - 类型检查
        register(CommandInfo.builtin("typecheck", "类型检查", "运行类型检查。"));

        // /security - 安全扫描
        register(CommandInfo.builtin("security", "安全扫描", "运行安全扫描。"));

        // /deps - 依赖管理
        register(CommandInfo.builtin("deps", "依赖管理", "管理项目依赖。使用: /deps install|update|check"));

        // /env - 环境变量
        register(CommandInfo.builtin("env", "环境变量", "管理环境变量。使用: /env list|set|get"));

        // /doctor - 项目诊断
        register(CommandInfo.builtin("doctor", "项目诊断", "诊断项目配置和依赖问题。"));

        // /index - 生成项目索引
        register(CommandInfo.builtin("index", "生成项目索引", "生成项目紧凑索引，优化大项目处理。"));

        // /context - 查看上下文状态
        register(CommandInfo.clientAction("context", "查看上下文状态", CommandInfo.ClientAction.CONTEXT_USAGE));

        // /rules - 查看规则文件
        register(CommandInfo.builtin("rules", "查看规则文件", "查看项目规则文件状态。"));

        // /checkpoint - 创建检查点
        register(CommandInfo.builtin("checkpoint", "创建检查点", "创建项目检查点。使用: /checkpoint 名称"));

        // /restore - 恢复检查点
        register(CommandInfo.builtin("restore", "恢复检查点", "恢复到指定检查点。使用: /restore 名称"));

        // /memory - 查看记忆状态
        register(CommandInfo.builtin("memory", "查看记忆状态", "查看Agent的记忆状态。"));

        // /tokens - 查看token使用
        register(CommandInfo.clientAction("tokens", "查看 token 使用", CommandInfo.ClientAction.USAGE_PANEL));
    }

    /**
     * 注册命令
     */
    private void register(CommandInfo command) {
        commands.put(command.name(), command);
        for (String alias : command.aliases()) {
            aliasMap.put(alias, command.name());
        }
    }

    /**
     * 从类路径加载模板
     */
    private String loadTemplate(String templateName) {
        try {
            ClassPathResource resource = new ClassPathResource("templates/command/" + templateName);
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            }
        } catch (IOException e) {
            // 模板缺失属于预期兜底路径（命令仍会注册，只是模板为空），不刷 stacktrace
            log.warn("无法加载模板: {}（该命令将以空模板注册）", templateName);
            return "";
        }
    }

    /**
     * 获取命令
     */
    public CommandInfo getCommand(String name) {
        // 先查找直接名称
        CommandInfo command = commands.get(name);
        if (command != null) return command;

        // 再查找别名
        String realName = aliasMap.get(name);
        if (realName != null) {
            return commands.get(realName);
        }

        return null;
    }

    /**
     * 获取所有命令
     */
    public Collection<CommandInfo> getAllCommands() {
        return Collections.unmodifiableCollection(commands.values());
    }

    /**
     * 获取指定来源的命令
     */
    public List<CommandInfo> getCommandsBySource(CommandSource source) {
        return commands.values().stream()
            .filter(cmd -> cmd.source() == source)
            .toList();
    }

    /**
     * 检查命令是否存在
     */
    public boolean hasCommand(String name) {
        return commands.containsKey(name) || aliasMap.containsKey(name);
    }

    /**
     * 加载自定义命令（从JSON配置）
     */
    public void loadCustomCommands(Map<String, Map<String, Object>> customCommands) {
        if (customCommands == null) return;

        for (var entry : customCommands.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> config = entry.getValue();

            String description = (String) config.getOrDefault("description", "");
            String template = (String) config.getOrDefault("template", "");
            String agent = (String) config.get("agent");
            String model = (String) config.get("model");
            boolean subtask = (Boolean) config.getOrDefault("subtask", false);

            CommandInfo command = CommandInfo.fromConfig(name, description, template, agent, model, subtask);
            commands.put(name, command);
            log.info("加载自定义命令: {}", name);
        }
    }

    /**
     * 从Markdown文件加载命令
     */
    public void loadMarkdownCommands(Path commandsDir) {
        if (!Files.exists(commandsDir)) return;

        try (var stream = Files.list(commandsDir)) {
            stream.filter(path -> path.toString().endsWith(".md"))
                .forEach(this::loadMarkdownCommand);
        } catch (IOException e) {
            log.warn("无法加载Markdown命令目录: {}", commandsDir, e);
        }
    }

    /**
     * 加载单个Markdown命令文件
     */
    private void loadMarkdownCommand(Path file) {
        try {
            String content = Files.readString(file);
            String fileName = file.getFileName().toString();
            String commandName = fileName.substring(0, fileName.lastIndexOf('.'));

            // 解析YAML frontmatter
            if (content.startsWith("---")) {
                int endIndex = content.indexOf("---", 3);
                if (endIndex > 0) {
                    String frontmatter = content.substring(3, endIndex).trim();
                    String template = content.substring(endIndex + 3).trim();

                    // 简单解析YAML
                    String description = extractYamlValue(frontmatter, "description");
                    String agent = extractYamlValue(frontmatter, "agent");
                    String model = extractYamlValue(frontmatter, "model");
                    boolean subtask = "true".equalsIgnoreCase(extractYamlValue(frontmatter, "subtask"));

                    CommandInfo command = CommandInfo.fromConfig(commandName, description, template, agent, model, subtask);
                    commands.put(commandName, command);
                    log.info("加载Markdown命令: {}", commandName);
                }
            }
        } catch (IOException e) {
            log.warn("无法加载Markdown命令: {}", file, e);
        }
    }

    /**
     * 简单提取YAML值
     */
    private String extractYamlValue(String yaml, String key) {
        String[] lines = yaml.split("\n");
        for (String line : lines) {
            if (line.startsWith(key + ":")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return null;
    }

    /**
     * 注册MCP命令
     */
    public void registerMcpCommand(String name, String description, String template) {
        CommandInfo command = new CommandInfo(name, description, template, null, null, false,
            CommandSource.MCP, CommandInfo.extractHints(template), List.of(),
            CommandInfo.CommandDispatch.AGENT_PROMPT, null, null);
        commands.put(name, command);
    }

    /**
     * 注册Skill命令
     */
    public void registerSkillCommand(String name, String description, String template) {
        // Skill命令不会覆盖已有命令
        if (!commands.containsKey(name)) {
            CommandInfo command = new CommandInfo(name, description, template, null, null, false,
                CommandSource.SKILL, CommandInfo.extractHints(template), List.of(),
                CommandInfo.CommandDispatch.AGENT_PROMPT, null, null);
            commands.put(name, command);
        }
    }

    /**
     * 获取命令统计信息
     */
    public Map<String, Integer> getStatistics() {
        Map<String, Integer> stats = new HashMap<>();
        for (CommandSource source : CommandSource.values()) {
            stats.put(source.name(), (int) commands.values().stream()
                .filter(cmd -> cmd.source() == source)
                .count());
        }
        stats.put("TOTAL", commands.size());
        stats.put("ALIASES", aliasMap.size());
        return stats;
    }
}
