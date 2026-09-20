package com.labex.labexagent.controller;

import com.labex.common.Result;
import com.labex.entity.AgentMcpServer;
import com.labex.entity.AgentSkill;
import com.labex.entity.StudentProject;
import com.labex.labexagent.workspace.ProjectWorkspace;
import com.labex.service.AgentMcpServerService;
import com.labex.service.AgentSkillService;
import com.labex.service.StudentProjectService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/student/agent/extensions")
public class AgentExtensionController {
    private final AgentSkillService skillService;
    private final AgentMcpServerService mcpServerService;
    private final StudentProjectService studentProjectService;

    public AgentExtensionController(AgentSkillService skillService, AgentMcpServerService mcpServerService,
                                    StudentProjectService studentProjectService) {
        this.skillService = skillService;
        this.mcpServerService = mcpServerService;
        this.studentProjectService = studentProjectService;
    }

    @GetMapping("/skills")
    public Result<List<AgentSkill>> listSkills(Authentication auth) {
        return Result.success(skillService.listByStudent(getStudentId(auth)));
    }

    /**
     * 合并视图：该项目工作区 {@code .skill/} 下的项目级 Skill + 该账号的用户级 Skill。
     *
     * <p>与 {@code /skills} 的区别：那个只返回用户级（可增删改的数据库记录），这里额外暴露
     * 项目级技能的名称/描述/来源，供界面展示"这个项目里放了哪些技能"。项目级只读 ——
     * 它是工作区里的普通文件，用户在文件树中直接编辑。
     *
     * <p>同名时项目级覆盖用户级，与 Agent 运行时的解析规则保持一致。
     */
    @GetMapping("/skills/catalog")
    public Result<List<AgentSkillService.CatalogEntry>> listSkillCatalog(
            @RequestParam("projectId") Integer projectId, Authentication auth) {
        Integer studentId = getStudentId(auth);
        StudentProject project = studentProjectService.getOwnedProject(studentId, projectId);
        if (project == null) {
            return Result.error("Project not found");
        }
        return Result.success(skillService.resolveCatalog(
                studentId, ProjectWorkspace.workspaceRootOrNull(project)));
    }

    @PostMapping("/skills")
    public Result<AgentSkill> createSkill(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            return Result.success(skillService.create(getStudentId(auth), body));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/skills/{skillId}")
    public Result<AgentSkill> updateSkill(@PathVariable Integer skillId, @RequestBody Map<String, Object> body, Authentication auth) {
        try {
            return Result.success(skillService.update(getStudentId(auth), skillId, body));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/skills/{skillId}")
    public Result<Void> deleteSkill(@PathVariable Integer skillId, Authentication auth) {
        try {
            skillService.delete(getStudentId(auth), skillId);
            return Result.success(null);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/mcp")
    public Result<List<Map<String, Object>>> listMcpServers(Authentication auth) {
        List<Map<String, Object>> data = mcpServerService.listByStudent(getStudentId(auth)).stream()
                .map(this::sanitizeServer)
                .toList();
        return Result.success(data);
    }

    @PostMapping("/mcp")
    public Result<Map<String, Object>> createMcpServer(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            return Result.success(sanitizeServer(mcpServerService.create(getStudentId(auth), body)));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/mcp/{serverId}")
    public Result<Map<String, Object>> updateMcpServer(@PathVariable Integer serverId, @RequestBody Map<String, Object> body, Authentication auth) {
        try {
            return Result.success(sanitizeServer(mcpServerService.update(getStudentId(auth), serverId, body)));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/mcp/{serverId}")
    public Result<Void> deleteMcpServer(@PathVariable Integer serverId, Authentication auth) {
        try {
            mcpServerService.delete(getStudentId(auth), serverId);
            return Result.success(null);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    private Map<String, Object> sanitizeServer(AgentMcpServer server) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serverId", server.getServerId());
        data.put("serverKey", server.getServerKey());
        data.put("serverName", server.getServerName());
        data.put("transport", server.getTransport());
        data.put("endpoint", server.getEndpoint());
        data.put("authConfigured", mcpServerService.hasStoredAuthHeader(server));
        data.put("toolsJson", server.getToolsJson());
        data.put("isEnabled", server.getIsEnabled());
        data.put("status", server.getStatus());
        data.put("createTime", server.getCreateTime());
        data.put("updateTime", server.getUpdateTime());
        return data;
    }

    private Integer getStudentId(Authentication auth) {
        return Integer.parseInt(auth.getName());
    }
}
