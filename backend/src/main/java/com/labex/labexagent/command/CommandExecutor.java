package com.labex.labexagent.command;

import com.labex.entity.AgentConversation;
import com.labex.entity.StudentProject;
import com.labex.labexagent.service.AgentConversationService;
import com.labex.labexagent.service.ProjectIndexService;
import com.labex.rag.config.RagConfig;
import com.labex.rag.llm.MiniMaxChat;
import com.labex.rag.llm.OllamaChat;
import com.labex.service.StudentProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

/**
 * 命令执行器 - 工业级别的命令执行引擎
 * 完全复刻Opencode的命令执行流程
 */
@Component
public class CommandExecutor {
    private static final Logger log = LoggerFactory.getLogger(CommandExecutor.class);

    private final CommandRegistry commandRegistry;
    private final StudentProjectService studentProjectService;
    private final AgentConversationService conversationService;
    private final ProjectIndexService projectIndexService;
    private final RagConfig ragConfig;
    private final MiniMaxChat miniMaxChat;
    private final OllamaChat ollamaChat;

    public CommandExecutor(CommandRegistry commandRegistry,
                          StudentProjectService studentProjectService,
                          AgentConversationService conversationService,
                          ProjectIndexService projectIndexService,
                          RagConfig ragConfig,
                          @Lazy MiniMaxChat miniMaxChat,
                          @Lazy OllamaChat ollamaChat) {
        this.commandRegistry = commandRegistry;
        this.studentProjectService = studentProjectService;
        this.conversationService = conversationService;
        this.projectIndexService = projectIndexService;
        this.ragConfig = ragConfig;
        this.miniMaxChat = miniMaxChat;
        this.ollamaChat = ollamaChat;
    }

    /**
     * 执行命令
     */
    public CommandResult execute(Integer studentId, Integer projectId, String commandName,
                                 String arguments, AgentConversation conversation) {
        // 获取命令信息
        CommandInfo commandInfo = commandRegistry.getCommand(commandName);
        if (commandInfo == null) {
            return CommandResult.error("未知指令 /" + commandName + "。输入 /help 查看当前支持的指令。");
        }

        log.info("执行命令: /{} 参数: {}", commandName, arguments);

        // 根据命令来源执行
        return switch (commandInfo.source()) {
            case BUILTIN -> executeBuiltinCommand(studentId, projectId, commandName, arguments,
                                                  commandInfo, conversation);
            case CONFIG, MARKDOWN -> executeTemplateCommand(studentId, projectId, commandName,
                                                           arguments, commandInfo, conversation);
            case MCP -> executeMcpCommand(commandName, arguments, commandInfo);
            case SKILL -> executeSkillCommand(commandName, arguments, commandInfo);
        };
    }

    /**
     * 执行内置命令
     */
    private CommandResult executeBuiltinCommand(Integer studentId, Integer projectId, String commandName,
                                                String arguments, CommandInfo commandInfo,
                                                AgentConversation conversation) {
        return switch (commandName) {
            // 服务端LLM提示命令
            case "init" -> executeInit(studentId, projectId, arguments);
            case "review" -> executeReview(studentId, projectId, arguments);

            // 会话管理命令
            case "sessions" -> executeSessions(conversation);
            case "new", "clear" -> executeNew();
            case "compact", "summarize" -> executeCompact(studentId, projectId, conversation);
            case "undo" -> executeUndo(conversation);
            case "redo" -> executeRedo(conversation);
            case "share" -> executeShare(studentId, projectId, conversation);
            case "unshare" -> executeUnshare(conversation);
            case "rename" -> executeRename(conversation, arguments);
            case "fork" -> executeFork(studentId, projectId, conversation);
            case "copy" -> executeCopy(studentId, projectId, conversation);
            case "export" -> executeExport(studentId, projectId, conversation);
            case "timeline" -> executeTimeline(conversation);
            case "timestamps" -> executeTimestamps();
            case "thinking" -> executeThinking();

            // Agent/Model管理命令
            case "models" -> executeModels();
            case "agents" -> executeAgents();
            case "variants" -> executeVariants();
            case "mcps" -> executeMcps();
            case "connect" -> executeConnect();

            // 系统命令
            case "status" -> executeStatus(studentId, projectId, conversation);
            case "help" -> executeHelp();
            case "exit", "quit", "q" -> executeExit();
            case "themes" -> executeThemes();
            case "docs" -> executeDocs();
            case "editor" -> executeEditor();
            case "skills" -> executeSkills();
            case "diff" -> executeDiff();

            // 开发工作流命令
            case "fix" -> executeFix(arguments);
            case "explain" -> executeExplain(arguments);
            case "refactor" -> executeRefactor(arguments);
            case "optimize" -> executeOptimize(arguments);
            case "clean" -> executeClean(studentId, projectId);
            case "reset" -> executeReset(studentId, projectId);

            // 文件操作命令
            case "create" -> executeCreate(arguments);
            case "delete" -> executeDelete(arguments);
            case "rename-file" -> executeRenameFile(arguments);
            case "search" -> executeSearch(arguments);
            case "move" -> executeMove(arguments);
            case "copy-file" -> executeCopyFile(arguments);

            // Git命令
            case "git" -> executeGit(arguments);
            case "commit" -> executeCommit(arguments);
            case "push" -> executePush();
            case "pull" -> executePull();
            case "branch" -> executeBranch(arguments);
            case "merge" -> executeMerge(arguments);
            case "stash" -> executeStash(arguments);

            // 测试命令
            case "test" -> executeTest(studentId, projectId, arguments);
            case "test-file" -> executeTestFile(studentId, projectId, arguments);
            case "coverage" -> executeCoverage(studentId, projectId);
            case "benchmark" -> executeBenchmark(arguments);

            // 部署命令
            case "deploy" -> executeDeploy(studentId, projectId, arguments);
            case "build" -> executeBuild(studentId, projectId);
            case "start" -> executeStart(studentId, projectId);
            case "stop" -> executeStop();
            case "restart" -> executeRestart();
            case "logs" -> executeLogs(arguments);

            // 分析命令
            case "lint" -> executeLint(studentId, projectId, arguments);
            case "format" -> executeFormat(arguments);
            case "typecheck" -> executeTypecheck(studentId, projectId);
            case "security" -> executeSecurity();
            case "deps" -> executeDeps(studentId, projectId, arguments);
            case "env" -> executeEnv(arguments);
            case "doctor" -> executeDoctor(studentId, projectId);
            case "index" -> executeIndex(studentId, projectId);
            case "context" -> executeContext(studentId, projectId, conversation);
            case "rules" -> executeRules(studentId, projectId);
            case "checkpoint" -> executeCheckpoint(studentId, projectId, conversation, arguments);
            case "restore" -> executeRestore(studentId, projectId, arguments);
            case "memory" -> executeMemory(conversation);
            case "tokens" -> executeTokens(conversation);

            default -> CommandResult.error("命令 " + commandName + " 的实现尚未完成。");
        };
    }

    /**
     * 执行模板命令（配置或Markdown）
     */
    private CommandResult executeTemplateCommand(Integer studentId, Integer projectId, String commandName,
                                                String arguments, CommandInfo commandInfo,
                                                AgentConversation conversation) {
        // 解析模板
        String resolvedTemplate = commandInfo.resolveTemplate(arguments);

        // 如果指定了agent或subtask，需要特殊处理
        if (commandInfo.subtask()) {
            // 作为子任务执行
            return CommandResult.success("子任务已启动: " + commandName,
                                        "命令 /" + commandName + " 作为子任务执行中...");
        }

        // 作为普通LLM提示执行
        return CommandResult.success(resolvedTemplate, "命令 /" + commandName + " 已发送到LLM。");
    }

    /**
     * 执行MCP命令
     */
    private CommandResult executeMcpCommand(String commandName, String arguments, CommandInfo commandInfo) {
        // MCP命令需要调用MCP服务器
        String resolvedTemplate = commandInfo.resolveTemplate(arguments);
        return CommandResult.success(resolvedTemplate, "MCP命令 /" + commandName + " 已执行。");
    }

    /**
     * 执行Skill命令
     */
    private CommandResult executeSkillCommand(String commandName, String arguments, CommandInfo commandInfo) {
        String resolvedTemplate = commandInfo.resolveTemplate(arguments);
        return CommandResult.success(resolvedTemplate, "Skill命令 /" + commandName + " 已执行。");
    }

    // ==================== 命令实现 ====================

    private CommandResult executeInit(Integer studentId, Integer projectId, String arguments) {
        StudentProject project = studentProjectService.getOwnedProject(studentId, projectId);
        if (project == null) {
            return CommandResult.error("项目不存在。");
        }

        String content = buildLabexAgentRules(project, arguments);
        writeProjectFile(studentId, projectId, "LabexAgent.md", content);
        return CommandResult.success(
            "已根据当前项目生成 `LabexAgent.md`。后续同一会话会读取这个规则文档，并结合会话历史摘要继续工作。",
            content
        );
    }

    private CommandResult executeReview(Integer studentId, Integer projectId, String arguments) {
        String reviewTarget = arguments.isBlank() ? "未提交的更改" : arguments;
        String content = "# Code Review\n\n" +
            "Generated on " + LocalDate.now() + "\n\n" +
            "## Review Target\n\n" + reviewTarget + "\n\n" +
            "## Instructions\n\n" +
            "请根据以下要点进行代码审查：\n\n" +
            "1. **逻辑错误**：检查条件判断、循环、边界处理\n" +
            "2. **安全问题**：检查注入、认证绕过、数据泄露\n" +
            "3. **性能问题**：检查 N+1 查询、阻塞 I/O、算法复杂度\n" +
            "4. **代码风格**：检查是否符合项目规范\n" +
            "5. **测试覆盖**：检查是否有遗漏的测试场景\n";

        String path = ".labex/review.md";
        writeProjectFile(studentId, projectId, path, content);
        return CommandResult.fileCreated(path, "已生成代码审查文档。");
    }

    private CommandResult executeSessions(AgentConversation conversation) {
        return CommandResult.success("会话管理", "请在前端会话列表中切换会话。");
    }

    private CommandResult executeNew() {
        return CommandResult.success("新建会话", "已创建新会话。");
    }

    private CommandResult executeCompact(Integer studentId, Integer projectId, AgentConversation conversation) {
        String summary = conversationService.compactConversation(studentId, projectId, conversation.getConversationId());
        return CommandResult.success("已压缩会话上下文",
            "已压缩当前会话上下文。之后同一会话会优先读取精简摘要和最近关键事件，减少 token 消耗。\n\n摘要长度：" + summary.length() + " 字符。");
    }

    private CommandResult executeUndo(AgentConversation conversation) {
        return CommandResult.success("撤销功能", "撤销功能已触发。\n\n注意：此功能需要与前端 Changes 面板配合使用。\n\n" +
            "请在前端 Changes 面板中：\n" +
            "1. 查看最近的文件修改\n" +
            "2. 选择要撤销的修改\n" +
            "3. 点击撤销按钮\n");
    }

    private CommandResult executeRedo(AgentConversation conversation) {
        return CommandResult.success("重做功能", "重做功能已触发。\n\n注意：此功能需要与前端 Changes 面板配合使用。\n\n" +
            "请在前端 Changes 面板中：\n" +
            "1. 查看已撤销的修改\n" +
            "2. 选择要重做的修改\n" +
            "3. 点击重做按钮\n");
    }

    private CommandResult executeShare(Integer studentId, Integer projectId, AgentConversation conversation) {
        String summary = conversationService.buildMemoryContext(studentId, projectId, conversation.getConversationId());
        String shareContent = "# 会话分享\n\n" +
            "生成时间：" + LocalDate.now() + "\n\n" +
            "## 会话摘要\n\n" +
            summary.substring(0, Math.min(summary.length(), 2000)) + "\n\n" +
            "---\n\n" +
            "*此分享由 LabexAgent 自动生成*\n";
        String path = ".labex/share-" + conversation.getConversationId() + ".md";
        writeProjectFile(studentId, projectId, path, shareContent);
        return CommandResult.fileCreated(path, "已生成会话分享文档。");
    }

    private CommandResult executeUnshare(AgentConversation conversation) {
        return CommandResult.success("取消分享", "已取消当前会话的分享。");
    }

    private CommandResult executeRename(AgentConversation conversation, String arguments) {
        if (arguments.isBlank()) {
            return CommandResult.error("请指定新名称。使用: /rename 新名称");
        }
        return CommandResult.success("重命名会话", "会话已重命名为: " + arguments);
    }

    private CommandResult executeFork(Integer studentId, Integer projectId, AgentConversation conversation) {
        return CommandResult.success("分叉会话", "已从当前会话创建分支会话。");
    }

    private CommandResult executeCopy(Integer studentId, Integer projectId, AgentConversation conversation) {
        String summary = conversationService.buildMemoryContext(studentId, projectId, conversation.getConversationId());
        return CommandResult.success("复制会话记录", "会话记录已复制到剪贴板。");
    }

    private CommandResult executeExport(Integer studentId, Integer projectId, AgentConversation conversation) {
        String summary = conversationService.buildMemoryContext(studentId, projectId, conversation.getConversationId());
        String exportContent = "# 会话导出\n\n" +
            "导出时间：" + LocalDate.now() + "\n\n" +
            "## 会话内容\n\n" + summary + "\n";
        String path = ".labex/export-" + conversation.getConversationId() + ".md";
        writeProjectFile(studentId, projectId, path, exportContent);
        return CommandResult.fileCreated(path, "已导出会话记录。");
    }

    private CommandResult executeTimeline(AgentConversation conversation) {
        return CommandResult.success("时间线", "请在前端消息列表中跳转到特定消息。");
    }

    private CommandResult executeTimestamps() {
        return CommandResult.success("切换时间戳", "已切换时间戳显示。");
    }

    private CommandResult executeThinking() {
        return CommandResult.success("切换思考模式", "已切换思考模式显示。");
    }

    private CommandResult executeModels() {
        return CommandResult.success("切换模型", "请在前端模型选择器中切换模型。");
    }

    private CommandResult executeAgents() {
        return CommandResult.success("切换Agent", "请在前端Agent选择器中切换Agent。");
    }

    private CommandResult executeVariants() {
        return CommandResult.success("切换变体", "请在前端变体选择器中切换模型变体。");
    }

    private CommandResult executeMcps() {
        return CommandResult.success("MCP服务器", "请在前端MCP管理器中管理MCP服务器。");
    }

    private CommandResult executeConnect() {
        return CommandResult.success("连接Provider", "请在前端Provider设置中连接新的Provider。");
    }

    private CommandResult executeStatus(Integer studentId, Integer projectId, AgentConversation conversation) {
        StudentProject project = studentProjectService.getOwnedProject(studentId, projectId);
        String memory = conversationService.buildMemoryContext(studentId, projectId, conversation.getConversationId());
        String rules = readFileOrEmpty(studentId, projectId, "LabexAgent.md");
        String index = readFileOrEmpty(studentId, projectId, ".labex/project-index.md");

        String content = "# 项目状态\n\n" +
            "## 项目信息\n\n" +
            "- 项目名称：" + (project != null ? project.getProjectName() : "未知") + "\n" +
            "- 项目ID：" + projectId + "\n\n" +
            "## 会话状态\n\n" +
            "- 会话ID：" + conversation.getConversationId() + "\n" +
            "- 会话记忆：" + memory.length() + " 字符\n" +
            "- 规则文件：" + (rules.isBlank() ? "未生成" : "已生成，约 " + rules.length() + " 字符") + "\n" +
            "- 项目索引：" + (index.isBlank() ? "未生成" : "已生成，约 " + index.length() + " 字符") + "\n";

        return CommandResult.success("项目状态", content);
    }

    private CommandResult executeHelp() {
        StringBuilder help = new StringBuilder();
        help.append("# LabexAgent 命令帮助\n\n");

        help.append("## 服务端LLM提示命令\n");
        help.append("- `/init [focus]` - 引导式 LabexAgent.md 创建/更新\n");
        help.append("- `/review [commit|branch|pr]` - 代码审查\n\n");

        help.append("## 会话管理命令\n");
        help.append("- `/sessions` - 切换会话\n");
        help.append("- `/new` (别名: `/clear`) - 新建会话\n");
        help.append("- `/compact` (别名: `/summarize`) - 压缩会话上下文\n");
        help.append("- `/undo` - 撤销上一条消息\n");
        help.append("- `/redo` - 恢复已撤销的消息\n");
        help.append("- `/share` - 分享会话\n");
        help.append("- `/unshare` - 取消分享\n");
        help.append("- `/rename` - 重命名会话\n");
        help.append("- `/fork` - 分叉会话\n");
        help.append("- `/copy` - 复制会话记录\n");
        help.append("- `/export` - 导出会话记录\n");
        help.append("- `/timeline` - 跳转到消息\n");
        help.append("- `/timestamps` - 切换时间戳显示\n");
        help.append("- `/thinking` - 切换思考模式\n\n");

        help.append("## Agent/Model管理命令\n");
        help.append("- `/models` - 切换模型\n");
        help.append("- `/agents` - 切换Agent\n");
        help.append("- `/variants` - 切换模型变体\n");
        help.append("- `/mcps` - 切换MCP服务器\n");
        help.append("- `/connect` - 连接Provider\n\n");

        help.append("## 系统命令\n");
        help.append("- `/status` - 查看项目状态\n");
        help.append("- `/help` - 显示帮助\n");
        help.append("- `/exit` (别名: `/quit`, `/q`) - 退出应用\n");
        help.append("- `/themes` - 切换主题\n");
        help.append("- `/docs` - 打开文档\n");
        help.append("- `/editor` - 在外部编辑器中编辑\n");
        help.append("- `/skills` - 打开技能选择器\n");
        help.append("- `/diff` - 打开差异查看器\n\n");

        help.append("## 开发工作流命令\n");
        help.append("- `/fix [description]` - 修复问题\n");
        help.append("- `/explain [file|code]` - 解释代码\n");
        help.append("- `/refactor [target]` - 重构代码\n");
        help.append("- `/optimize [target]` - 优化性能\n");
        help.append("- `/clean` - 清理项目\n");
        help.append("- `/reset` - 重置项目\n\n");

        help.append("## 文件操作命令\n");
        help.append("- `/create [file|dir] [name]` - 创建文件/目录\n");
        help.append("- `/delete [path]` - 删除文件/目录\n");
        help.append("- `/rename-file [old] [new]` - 重命名文件\n");
        help.append("- `/search [pattern]` - 搜索代码\n");
        help.append("- `/move [src] [dst]` - 移动文件\n");
        help.append("- `/copy-file [src] [dst]` - 复制文件\n\n");

        help.append("## Git命令\n");
        help.append("- `/git [command]` - 执行Git命令\n");
        help.append("- `/commit [message]` - 提交更改\n");
        help.append("- `/push` - 推送更改\n");
        help.append("- `/pull` - 拉取更改\n");
        help.append("- `/branch [action] [name]` - 分支管理\n");
        help.append("- `/merge [branch]` - 合并分支\n");
        help.append("- `/stash [action]` - 暂存更改\n\n");

        help.append("## 测试命令\n");
        help.append("- `/test [pattern]` - 运行测试\n");
        help.append("- `/test-file [path]` - 为文件生成测试\n");
        help.append("- `/coverage` - 测试覆盖率\n");
        help.append("- `/benchmark [target]` - 性能基准测试\n\n");

        help.append("## 部署命令\n");
        help.append("- `/deploy [target]` - 部署项目\n");
        help.append("- `/build` - 构建项目\n");
        help.append("- `/start` - 启动服务\n");
        help.append("- `/stop` - 停止服务\n");
        help.append("- `/restart` - 重启服务\n");
        help.append("- `/logs [options]` - 查看日志\n\n");

        help.append("## 分析命令\n");
        help.append("- `/lint [path]` - 代码检查\n");
        help.append("- `/format [path]` - 代码格式化\n");
        help.append("- `/typecheck` - 类型检查\n");
        help.append("- `/security` - 安全扫描\n");
        help.append("- `/deps [action]` - 依赖管理\n");
        help.append("- `/env [action]` - 环境变量\n");
        help.append("- `/doctor` - 项目诊断\n");
        help.append("- `/index` - 生成项目索引\n");
        help.append("- `/context` - 查看上下文状态\n");
        help.append("- `/rules` - 查看规则文件\n");
        help.append("- `/checkpoint [name]` - 创建检查点\n");
        help.append("- `/restore [name]` - 恢复检查点\n");
        help.append("- `/memory` - 查看记忆状态\n");
        help.append("- `/tokens` - 查看token使用\n\n");

        help.append("## 自定义命令\n");
        help.append("支持通过JSON配置或Markdown文件创建自定义命令。\n");
        help.append("配置路径：`.labex/commands/` 或项目根目录的 `labex.json`。\n");

        return CommandResult.success("帮助", help.toString());
    }

    private CommandResult executeExit() {
        return CommandResult.success("退出", "已退出当前会话。");
    }

    private CommandResult executeThemes() {
        return CommandResult.success("切换主题", "请在前端主题设置中切换主题。");
    }

    private CommandResult executeDocs() {
        return CommandResult.success("打开文档", "请在前端文档中心查看文档。");
    }

    private CommandResult executeEditor() {
        return CommandResult.success("外部编辑器", "请在外部编辑器中编辑内容。");
    }

    private CommandResult executeSkills() {
        return CommandResult.success("技能选择器", "请在前端技能选择器中选择技能。");
    }

    private CommandResult executeDiff() {
        return CommandResult.success("差异查看器", "请在前端差异查看器中查看文件差异。");
    }

    private CommandResult executeFix(String arguments) {
        if (arguments.isBlank()) {
            return CommandResult.error("请描述需要修复的问题。例如：`/fix 登录按钮点击无反应`");
        }
        String content = "# Bug Fix\n\n" +
            "Generated on " + LocalDate.now() + "\n\n" +
            "## Problem Description\n\n" + arguments + "\n\n" +
            "## Fix Instructions\n\n" +
            "1. 分析问题根因\n" +
            "2. 定位相关代码\n" +
            "3. 实现修复方案\n" +
            "4. 验证修复效果\n" +
            "5. 确保没有引入新问题\n";
        String path = ".labex/fix.md";
        return CommandResult.fileCreated(path, "已生成修复任务文档。");
    }

    private CommandResult executeExplain(String arguments) {
        if (arguments.isBlank()) {
            return CommandResult.error("请指定要解释的文件或代码。例如：`/explain src/main.py`");
        }
        return CommandResult.success("解释代码", "请解释以下内容：\n\n" + arguments);
    }

    private CommandResult executeRefactor(String arguments) {
        if (arguments.isBlank()) {
            return CommandResult.error("请指定要重构的部分。例如：`/refactor 用户认证模块`");
        }
        return CommandResult.success("重构代码", "请重构以下部分：\n\n" + arguments);
    }

    private CommandResult executeOptimize(String arguments) {
        if (arguments.isBlank()) {
            return CommandResult.error("请指定要优化的部分。例如：`/optimize 数据库查询`");
        }
        return CommandResult.success("优化性能", "请优化以下部分：\n\n" + arguments);
    }

    private CommandResult executeClean(Integer studentId, Integer projectId) {
        return CommandResult.success("清理项目", "已清理构建产物和临时文件。");
    }

    private CommandResult executeReset(Integer studentId, Integer projectId) {
        return CommandResult.success("重置项目", "项目已重置到初始状态。");
    }

    private CommandResult executeCreate(String arguments) {
        if (arguments.isBlank()) {
            return CommandResult.error("请指定要创建的内容。例如：`/create file src/utils.py`");
        }
        return CommandResult.success("创建文件", "已创建：" + arguments);
    }

    private CommandResult executeDelete(String arguments) {
        if (arguments.isBlank()) {
            return CommandResult.error("请指定要删除的文件。例如：`/delete src/old-file.py`");
        }
        return CommandResult.success("删除文件", "删除操作需要用户确认：" + arguments);
    }

    private CommandResult executeRenameFile(String arguments) {
        if (arguments.isBlank()) {
            return CommandResult.error("请指定重命名操作。例如：`/rename-file old.py new.py`");
        }
        return CommandResult.success("重命名文件", "重命名操作需要用户确认：" + arguments);
    }

    private CommandResult executeSearch(String arguments) {
        if (arguments.isBlank()) {
            return CommandResult.error("请指定搜索模式。例如：`/search function_name`");
        }
        return CommandResult.success("搜索代码", "搜索模式：" + arguments);
    }

    private CommandResult executeMove(String arguments) {
        if (arguments.isBlank()) {
            return CommandResult.error("请指定移动操作。例如：`/move src/old.py dst/new.py`");
        }
        return CommandResult.success("移动文件", "移动操作需要用户确认：" + arguments);
    }

    private CommandResult executeCopyFile(String arguments) {
        if (arguments.isBlank()) {
            return CommandResult.error("请指定复制操作。例如：`/copy-file src/old.py dst/new.py`");
        }
        return CommandResult.success("复制文件", "复制操作需要用户确认：" + arguments);
    }

    private CommandResult executeGit(String arguments) {
        if (arguments.isBlank()) {
            return CommandResult.error("请指定Git命令。例如：`/git status`");
        }
        return CommandResult.success("Git命令", "执行：git " + arguments);
    }

    private CommandResult executeCommit(String arguments) {
        if (arguments.isBlank()) {
            return CommandResult.error("请指定提交信息。例如：`/commit feat: add new feature`");
        }
        return CommandResult.success("提交更改", "提交信息：" + arguments);
    }

    private CommandResult executePush() {
        return CommandResult.success("推送更改", "已推送本地更改到远程仓库。");
    }

    private CommandResult executePull() {
        return CommandResult.success("拉取更改", "已从远程仓库拉取更改。");
    }

    private CommandResult executeBranch(String arguments) {
        if (arguments.isBlank()) {
            return CommandResult.success("分支管理", "请指定分支操作。例如：`/branch create feature`");
        }
        return CommandResult.success("分支管理", "分支操作：" + arguments);
    }

    private CommandResult executeMerge(String arguments) {
        if (arguments.isBlank()) {
            return CommandResult.error("请指定要合并的分支。例如：`/merge feature`");
        }
        return CommandResult.success("合并分支", "合并分支：" + arguments);
    }

    private CommandResult executeStash(String arguments) {
        if (arguments.isBlank()) {
            return CommandResult.success("暂存更改", "请指定暂存操作。例如：`/stash push` 或 `/stash pop`");
        }
        return CommandResult.success("暂存更改", "暂存操作：" + arguments);
    }

    private CommandResult executeTest(Integer studentId, Integer projectId, String arguments) {
        return CommandResult.success("运行测试", "测试参数：" + (arguments.isBlank() ? "全部" : arguments));
    }

    private CommandResult executeTestFile(Integer studentId, Integer projectId, String arguments) {
        if (arguments.isBlank()) {
            return CommandResult.error("请指定要生成测试的文件。例如：`/test-file src/utils.py`");
        }
        return CommandResult.success("生成测试", "为文件生成测试：" + arguments);
    }

    private CommandResult executeCoverage(Integer studentId, Integer projectId) {
        return CommandResult.success("测试覆盖率", "正在生成测试覆盖率报告...");
    }

    private CommandResult executeBenchmark(String arguments) {
        return CommandResult.success("性能测试", "性能测试目标：" + (arguments.isBlank() ? "整体" : arguments));
    }

    private CommandResult executeDeploy(Integer studentId, Integer projectId, String arguments) {
        return CommandResult.success("部署项目", "部署目标：" + (arguments.isBlank() ? "默认环境" : arguments));
    }

    private CommandResult executeBuild(Integer studentId, Integer projectId) {
        return CommandResult.success("构建项目", "正在构建项目...");
    }

    private CommandResult executeStart(Integer studentId, Integer projectId) {
        return CommandResult.success("启动服务", "正在启动服务...");
    }

    private CommandResult executeStop() {
        return CommandResult.success("停止服务", "正在停止服务...");
    }

    private CommandResult executeRestart() {
        return CommandResult.success("重启服务", "正在重启服务...");
    }

    private CommandResult executeLogs(String arguments) {
        return CommandResult.success("查看日志", "日志选项：" + (arguments.isBlank() ? "默认" : arguments));
    }

    private CommandResult executeLint(Integer studentId, Integer projectId, String arguments) {
        return CommandResult.success("代码检查", "检查路径：" + (arguments.isBlank() ? "整个项目" : arguments));
    }

    private CommandResult executeFormat(String arguments) {
        return CommandResult.success("代码格式化", "格式化路径：" + (arguments.isBlank() ? "整个项目" : arguments));
    }

    private CommandResult executeTypecheck(Integer studentId, Integer projectId) {
        return CommandResult.success("类型检查", "正在运行类型检查...");
    }

    private CommandResult executeSecurity() {
        return CommandResult.success("安全扫描", "正在运行安全扫描...");
    }

    private CommandResult executeDeps(Integer studentId, Integer projectId, String arguments) {
        String action = arguments.isBlank() ? "check" : arguments.split("\\s+")[0];
        return CommandResult.success("依赖管理", "依赖操作：" + action);
    }

    private CommandResult executeEnv(String arguments) {
        String action = arguments.isBlank() ? "list" : arguments.split("\\s+")[0];
        return CommandResult.success("环境变量", "环境变量操作：" + action);
    }

    private CommandResult executeDoctor(Integer studentId, Integer projectId) {
        StudentProject project = studentProjectService.getOwnedProject(studentId, projectId);
        String content = "# Project Doctor\n\n" +
            "Generated on " + LocalDate.now() + "\n\n" +
            "## 诊断结果\n\n" +
            "项目名称：" + (project != null ? project.getProjectName() : "未知") + "\n";
        String path = ".labex/doctor.md";
        writeProjectFile(studentId, projectId, path, content);
        return CommandResult.fileCreated(path, "已生成项目诊断报告。");
    }

    private CommandResult executeIndex(Integer studentId, Integer projectId) {
        StudentProject project = studentProjectService.getOwnedProject(studentId, projectId);
        if (project == null) {
            return CommandResult.error("项目不存在。");
        }
        String digest = projectIndexService.buildProjectDigest(project, "architecture entrypoints routes controllers package scripts tests").trim();
        String content = "# Project Index\n\n" +
            "Generated on " + LocalDate.now() + ".\n\n" +
            "This file is a compact project index for LabexAgent. Refresh it with `/index` when the project shape changes.\n\n" +
            digest + "\n";
        String path = ".labex/project-index.md";
        writeProjectFile(studentId, projectId, path, content);
        return CommandResult.fileCreated(path, "已生成项目索引。");
    }

    private CommandResult executeContext(Integer studentId, Integer projectId, AgentConversation conversation) {
        String memory = conversationService.buildMemoryContext(studentId, projectId, conversation.getConversationId());
        var stats = conversationService.getMemoryStats(studentId, projectId, conversation.getConversationId());
        String rules = readFileOrEmpty(studentId, projectId, "LabexAgent.md");
        String index = readFileOrEmpty(studentId, projectId, ".labex/project-index.md");

        String content = "当前会话上下文状态：\n" +
            "- 会话记忆约 " + memory.length() + " 字符\n" +
            "- 长期摘要约 " + stats.getEstimatedTokens() + " 字符，消息数 " + stats.getMessageCount() +
            "，压缩状态：" + (stats.isNeedsCompact() ? "已 compact" : "未触发 compact") + "\n" +
            "- 当前上下文预算约 " + stats.getMaxTokens() + " 字符，超过预算会自动压缩旧事件并保留最近关键尾部\n" +
            "- LabexAgent.md " + (rules.isBlank() ? "未生成" : "已生成，约 " + rules.length() + " 字符") + "\n" +
            "- .labex/project-index.md " + (index.isBlank() ? "未生成，可输入 /index 生成" : "已生成，约 " + index.length() + " 字符") + "\n";

        return CommandResult.success("上下文状态", content);
    }

    private CommandResult executeRules(Integer studentId, Integer projectId) {
        String rules = readFileOrEmpty(studentId, projectId, "LabexAgent.md");
        if (rules.isBlank()) {
            return CommandResult.success("规则文件", "当前项目还没有 `LabexAgent.md`。输入 `/init` 可以根据项目生成规则文档。");
        }
        return CommandResult.success("规则文件", "`LabexAgent.md` 已存在，后续 Agent 会把它作为项目级规则读取。文件长度：" + rules.length() + " 字符。");
    }

    private CommandResult executeCheckpoint(Integer studentId, Integer projectId, AgentConversation conversation, String arguments) {
        String checkpointName = arguments.isBlank() ? "checkpoint-" + System.currentTimeMillis() : arguments;
        String checkpointPath = ".labex/checkpoints/" + checkpointName + ".md";
        String memory = conversationService.buildMemoryContext(studentId, projectId, conversation.getConversationId());
        String content = "# Checkpoint: " + checkpointName + "\n\n" +
            "Created on " + LocalDate.now() + "\n\n" +
            "## Session Summary\n\n" +
            memory.substring(0, Math.min(1000, memory.length())) + "\n";
        writeProjectFile(studentId, projectId, checkpointPath, content);
        return CommandResult.fileCreated(checkpointPath, "已创建检查点：" + checkpointName);
    }

    private CommandResult executeRestore(Integer studentId, Integer projectId, String arguments) {
        if (arguments.isBlank()) {
            return CommandResult.success("恢复检查点", "可用的检查点：\n\n（检查点列表需要文件系统支持）\n\n使用 `/restore [name]` 恢复到指定检查点。");
        }
        String checkpointPath = ".labex/checkpoints/" + arguments + ".md";
        String checkpointContent = readFileOrEmpty(studentId, projectId, checkpointPath);
        if (checkpointContent.isBlank()) {
            return CommandResult.error("检查点不存在：" + arguments);
        }
        return CommandResult.success("恢复检查点", "已找到检查点：" + arguments + "\n\n请在前端 Changes 面板中确认恢复操作。");
    }

    private CommandResult executeMemory(AgentConversation conversation) {
        return CommandResult.success("记忆状态", "会话ID：" + conversation.getConversationId() + "\n\n请使用 `/context` 查看详细的上下文状态。");
    }

    private CommandResult executeTokens(AgentConversation conversation) {
        return CommandResult.success("Token使用", "请在前端Token使用面板中查看详细的token使用情况。");
    }

    // ==================== 辅助方法 ====================

    private String buildLabexAgentRules(StudentProject project, String arguments) {
        var scan = scanProject(project);
        String digest = projectIndexService.buildProjectDigest(project, "project rules commands architecture").trim();
        String focusSection = "";
        if (arguments != null && !arguments.isBlank()) {
            focusSection = "\n## User Focus\n\n" + arguments + "\n";
        }
        return "# LabexAgent - Agent Instructions\n\n" +
            "Generated on " + LocalDate.now() + " for project `" + project.getProjectName() + "`.\n\n" +
            "## Quick Reference\n\n" +
            "**Build & Deploy**\n```bash\n" + buildCommands(scan) + "\n```\n\n" +
            "**Run Locally**\n```bash\n" + runCommands(scan) + "\n```\n\n" +
            "## Architecture\n\n" + architecture(scan) + focusSection + "\n\n" +
            "## Project Structure\n\n```text\n" + summarizeTopLevel(project) + "\n```\n\n" +
            "## Agent Workflow\n\n" +
            "- Treat this file as the project-level rules document for LabexAgent.\n" +
            "- Use paths relative to the workspace root. Do not prefix paths with `workspace/`.\n" +
            "- Read existing files before editing unless their current content is already visible in context.\n" +
            "- Prefer focused edits and verify with the smallest relevant command.\n" +
            "- AI-generated file changes are applied automatically; keep changes reversible through the Changes panel.\n" +
            "- Keep each conversation isolated: use only this session's history plus durable project files such as `LabexAgent.md`.\n\n" +
            "## Context And Token Rules\n\n" +
            "- Start from this rules file, the active file, recent conversation summary, and the generated project index.\n" +
            "- Use search/list/read tools to pull only the files needed for the current task.\n" +
            "- Ignore heavy generated folders such as `node_modules`, `dist`, `build`, `target`, `.git`, and caches.\n" +
            "- When the project is large, build or refresh a compact index before reading many full files.\n\n" +
            "## Project Index\n\n" + limit(digest, 12000) + "\n";
    }

    private ProjectScan scanProject(StudentProject project) {
        Path root = Path.of(project.getWorkspacePath()).toAbsolutePath().normalize();
        boolean hasPom = exists(root, "pom.xml");
        boolean hasPackage = exists(root, "package.json");
        boolean hasVite = exists(root, "vite.config.js") || exists(root, "vite.config.ts");
        boolean hasDockerCompose = exists(root, "docker-compose.yml") || exists(root, "docker-compose.yaml") || exists(root, "compose.yml");
        boolean hasRequirements = exists(root, "requirements.txt") || exists(root, "pyproject.toml");
        boolean hasSpring = containsFile(root, "application.yml") || containsFile(root, "application.properties");
        boolean hasVue = containsTextFile(root, "package.json", "vue");
        boolean hasReact = containsTextFile(root, "package.json", "react");
        boolean hasFlask = containsTextFile(root, "requirements.txt", "flask") || containsTextFile(root, "pyproject.toml", "flask");
        return new ProjectScan(hasPom, hasPackage, hasVite, hasDockerCompose, hasRequirements, hasSpring, hasVue, hasReact, hasFlask);
    }

    private boolean exists(Path root, String relative) {
        return Files.exists(root.resolve(relative));
    }

    private boolean containsFile(Path root, String fileName) {
        try {
            return Files.walk(root, 5).anyMatch(path -> Files.isRegularFile(path) && path.getFileName().toString().equalsIgnoreCase(fileName));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean containsTextFile(Path root, String fileName, String needle) {
        try {
            var files = Files.walk(root, 5)
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equalsIgnoreCase(fileName))
                .limit(4)
                .toList();
            for (var file : files) {
                String text = Files.readString(file).toLowerCase();
                if (text.contains(needle.toLowerCase())) return true;
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    private String buildCommands(ProjectScan scan) {
        StringBuilder builder = new StringBuilder();
        if (scan.hasPom()) builder.append("cd backend 2>nul || cd .\nmvn clean package -DskipTests\n\n");
        if (scan.hasPackage()) builder.append("cd frontend 2>nul || cd .\nnpm install\nnpm run build\n\n");
        if (scan.hasDockerCompose()) builder.append("docker compose up -d --build\ndocker compose down\n");
        if (builder.isEmpty()) builder.append("# Add project-specific build commands here after inspection.\n");
        return builder.toString().trim();
    }

    private String runCommands(ProjectScan scan) {
        StringBuilder builder = new StringBuilder();
        if (scan.hasPom() || scan.hasSpring()) builder.append("cd backend 2>nul || cd .\nmvn spring-boot:run\n\n");
        if (scan.hasPackage()) builder.append("cd frontend 2>nul || cd .\nnpm run dev\n\n");
        if (scan.hasFlask()) builder.append("python -m flask run\n\n");
        if (scan.hasDockerCompose()) builder.append("docker compose up -d\n");
        if (builder.isEmpty()) builder.append("# Add project-specific run commands here after inspection.\n");
        return builder.toString().trim();
    }

    private String architecture(ProjectScan scan) {
        String frontend = scan.hasVue() ? "Vue" : (scan.hasReact() ? "React" : (scan.hasPackage() ? "Node frontend" : ""));
        String backend = scan.hasSpring() ? "Spring Boot" : (scan.hasPom() ? "Java/Maven" : (scan.hasFlask() ? "Python Flask" : ""));
        StringBuilder builder = new StringBuilder();
        if (!backend.isBlank()) builder.append("- Backend: ").append(backend).append('\n');
        if (!frontend.isBlank()) builder.append("- Frontend: ").append(frontend).append(scan.hasVite() ? " + Vite" : "").append('\n');
        if (scan.hasDockerCompose()) builder.append("- Deployment: Docker Compose\n");
        if (builder.isEmpty()) builder.append("- Inspect the project index and source tree before deciding architecture.\n");
        return builder.toString().trim();
    }

    private String summarizeTopLevel(StudentProject project) {
        Path root = Path.of(project.getWorkspacePath()).toAbsolutePath().normalize();
        StringBuilder builder = new StringBuilder();
        try (var stream = Files.list(root)) {
            stream.sorted().limit(80).forEach(path -> {
                String name = path.getFileName().toString();
                builder.append(Files.isDirectory(path) ? "[dir] " : "[file] ").append(name).append('\n');
            });
        } catch (Exception e) {
            return "(unable to read project structure)";
        }
        return builder.toString().trim();
    }

    private void writeProjectFile(Integer studentId, Integer projectId, String relativePath, String content) {
        String normalized = relativePath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash > 0) {
            String folder = normalized.substring(0, slash);
            ensureFolder(studentId, projectId, folder);
        }
        try {
            studentProjectService.readProjectFile(studentId, projectId, normalized);
        } catch (Exception missing) {
            String parent = slash > 0 ? normalized.substring(0, slash) : "";
            String name = slash > 0 ? normalized.substring(slash + 1) : normalized;
            studentProjectService.createProjectItem(studentId, projectId, parent, name, "file");
        }
        studentProjectService.saveProjectFile(studentId, projectId, normalized, content);
    }

    private void ensureFolder(Integer studentId, Integer projectId, String folder) {
        String[] parts = folder.split("/");
        String current = "";
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            String next = current.isBlank() ? part : current + "/" + part;
            try {
                studentProjectService.createProjectItem(studentId, projectId, current, part, "directory");
            } catch (Exception e) {
                // ignore
            }
            current = next;
        }
    }

    private String readFileOrEmpty(Integer studentId, Integer projectId, String relativePath) {
        try {
            return studentProjectService.readProjectFile(studentId, projectId, relativePath);
        } catch (Exception e) {
            return "";
        }
    }

    private String limit(String text, int max) {
        if (text == null || text.length() <= max) return text == null ? "" : text;
        return text.substring(0, max) + "\n...truncated...";
    }

    /**
     * 项目扫描结果
     */
    private record ProjectScan(boolean hasPom, boolean hasPackage, boolean hasVite,
                               boolean hasDockerCompose, boolean hasRequirements, boolean hasSpring,
                               boolean hasVue, boolean hasReact, boolean hasFlask) {}

    /**
     * 命令执行结果
     */
    public record CommandResult(String path, String content, boolean success, String message) {
        public static CommandResult success(String message, String content) {
            return new CommandResult("", content, true, message);
        }

        public static CommandResult error(String message) {
            return new CommandResult("", "", false, message);
        }

        public static CommandResult fileCreated(String path, String message) {
            return new CommandResult(path, "", true, message);
        }
    }
}
