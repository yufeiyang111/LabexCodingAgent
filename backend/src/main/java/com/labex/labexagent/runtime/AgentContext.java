package com.labex.labexagent.runtime;

import com.labex.entity.StudentProject;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.workspace.ProjectWorkspace;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class AgentContext {
    private String sessionId;
    private Integer studentId;
    private StudentProject project;
    private String conversationId;
    private Long taskId;
    /** 当前 worker 从 AgentTask lease 获得的 execution epoch。 */
    private long executionEpoch;
    /** 执行者从 lease authority 取得任务所有权后获得的不可变 fence；未取得租约时为 null。 */
    private ExecutionFence executionFence;
    private Integer modelConfigId;
    private Path workspaceRoot;
    private List<PlanItem> plan;
    private int currentPlanIndex;
    /** 数据库计划快照的修订号；仅为本轮派生缓存。 */
    private long planRevision;
    /** 最近一次计划变更对应的已持久化事件序号；发送后即清空。 */
    private Long planEventSequence;
    private String planEventSource = "agent_plan";
    private String mode = "agent";
    private String stage = "intake";
    private boolean environmentRecovery;
    /** 当前工具调用是否已消费一次性网络授权；仅在本轮执行期间有效。 */
    private boolean networkEnabled;
    private CancellationToken cancellationToken = CancellationToken.none();
    private boolean unverifiedChanges;
    private int writeCount;
    private int verificationCount;
    private Set<String> trustedVerificationSources = new LinkedHashSet<>();
    private Set<String> unverifiedChangeTargets = new LinkedHashSet<>();
    private Set<String> selectedToolNames = Set.of();

    public boolean isPlanMode() {
        return "plan".equals(mode);
    }

    public boolean isExploreMode() {
        return "explore".equals(mode);
    }

    public static AgentContext create(String sessionId, Integer studentId, StudentProject project, String conversationId, Long taskId) {
        return new AgentContext(sessionId, studentId, project, conversationId, taskId,
                ProjectWorkspace.paths(project).workspaceRoot(), new ArrayList(), 0);
    }

    public String getPlanSummary() {
        if (this.plan == null || this.plan.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\u5f53\u524d\u6267\u884c\u8ba1\u5212:\n");
        for (int i = 0; i < this.plan.size(); ++i) {
            PlanItem item = (PlanItem)this.plan.get(i);
            String status = item.isCompleted() ? "[DONE]" : (i == this.currentPlanIndex ? "[NEXT]" : "[TODO]");
            sb.append(status).append(" ").append(i + 1).append(". ").append(item.getTitle());
            if (item.isCompleted()) {
                sb.append(" [\u5df2\u5b8c\u6210]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public String getPlanJson() {
        if (this.plan == null || this.plan.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < this.plan.size(); ++i) {
            PlanItem item = (PlanItem)this.plan.get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"index\":").append(i + 1).append(",\"title\":\"").append(this.escapeJson(item.getTitle())).append("\"").append(",\"description\":\"").append(this.escapeJson(item.getDescription() != null ? item.getDescription() : "")).append("\"").append(",\"completed\":").append(item.isCompleted()).append(",\"current\":").append(i == this.currentPlanIndex).append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    public String getSessionId() {
        return this.sessionId;
    }

    public Integer getStudentId() {
        return this.studentId;
    }

    public StudentProject getProject() {
        return this.project;
    }

    public String getConversationId() {
        return this.conversationId;
    }

    public Long getTaskId() {
        return this.taskId;
    }

    public long getExecutionEpoch() {
        return this.executionEpoch;
    }

    public void setExecutionEpoch(long executionEpoch) {
        this.executionEpoch = Math.max(0L, executionEpoch);
    }

    public ExecutionFence getExecutionFence() {
        return this.executionFence;
    }

    /** 只在执行者从 lease authority 取得任务所有权后设置；不得从请求 JSON 或过期任务对象推导。 */
    public void setExecutionFence(ExecutionFence executionFence) {
        this.executionFence = executionFence;
    }

    public long getPlanRevision() {
        return this.planRevision;
    }

    public Long consumePlanEventSequence() {
        Long sequence = this.planEventSequence;
        this.planEventSequence = null;
        return sequence;
    }

    public String getPlanEventSource() {
        return this.planEventSource;
    }

    public Integer getModelConfigId() {
        return this.modelConfigId;
    }

    public Path getWorkspaceRoot() {
        return this.workspaceRoot;
    }

    public List<PlanItem> getPlan() {
        return this.plan;
    }

    public int getCurrentPlanIndex() {
        return this.currentPlanIndex;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setStudentId(Integer studentId) {
        this.studentId = studentId;
    }

    public void setProject(StudentProject project) {
        this.project = project;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public void setModelConfigId(Integer modelConfigId) {
        this.modelConfigId = modelConfigId;
    }

    public void setWorkspaceRoot(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }


    /** 使用数据库计划快照刷新内存投影；此方法不创建新的计划事实。 */
    public void applyPlanProjection(List<PlanItem> plan, int currentPlanIndex, long revision,
                                    Long eventSequence, String eventSource) {
        this.plan = plan == null ? new ArrayList<>() : new ArrayList<>(plan);
        this.currentPlanIndex = this.plan.isEmpty() ? -1
                : Math.max(-1, Math.min(currentPlanIndex, this.plan.size() - 1));
        this.planRevision = Math.max(0L, revision);
        this.planEventSequence = eventSequence != null && eventSequence > 0L ? eventSequence : null;
        this.planEventSource = eventSource == null || eventSource.isBlank() ? "agent_plan" : eventSource;
    }

    /** 当前已持久化计划事件的前端协议投影。 */
    public java.util.Map<String, Object> getPlanEventPayload() {
        java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
        List<PlanItem> currentPlan = this.plan == null ? List.of() : this.plan;
        for (int index = 0; index < currentPlan.size(); index++) {
            PlanItem item = currentPlan.get(index);
            java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("index", index + 1);
            row.put("title", item.getTitle() == null ? "" : item.getTitle());
            row.put("description", item.getDescription() == null ? "" : item.getDescription());
            row.put("status", item.isCompleted() ? "completed"
                    : (index == this.currentPlanIndex ? "in_progress" : "pending"));
            row.put("completed", item.isCompleted());
            row.put("current", index == this.currentPlanIndex);
            items.add(java.util.Map.copyOf(row));
        }
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("taskId", this.taskId);
        payload.put("executionEpoch", this.executionEpoch);
        payload.put("planRevision", this.planRevision);
        payload.put("source", this.planEventSource);
        payload.put("plan", java.util.List.copyOf(items));
        payload.put("summary", this.getPlanSummary());
        payload.put("planJson", this.getPlanJson());
        return java.util.Map.copyOf(payload);
    }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getStage() { return stage == null || stage.isBlank() ? "intake" : stage; }
    public void setStage(String stage) { this.stage = stage == null || stage.isBlank() ? "intake" : stage; }
    public boolean isEnvironmentRecovery() { return environmentRecovery; }
    public void setEnvironmentRecovery(boolean value) { this.environmentRecovery = value; }
    public boolean isNetworkEnabled() { return networkEnabled; }
    public void setNetworkEnabled(boolean value) { this.networkEnabled = value; }
    public CancellationToken getCancellationToken() { return cancellationToken; }
    public void setCancellationToken(CancellationToken cancellationToken) {
        this.cancellationToken = cancellationToken == null ? CancellationToken.none() : cancellationToken;
    }
    public void setSelectedToolNames(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            this.selectedToolNames = Set.of();
            return;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        toolNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .forEach(normalized::add);
        this.selectedToolNames = Set.copyOf(normalized);
    }
    public Set<String> getSelectedToolNames() { return this.selectedToolNames; }
    public boolean isToolSelected(String toolName) {
        return toolName != null && this.selectedToolNames.contains(toolName);
    }

    public boolean hasUnverifiedChanges() { return unverifiedChanges; }
    public int getWriteCount() { return writeCount; }
    public int getVerificationCount() { return verificationCount; }
    public boolean hasTrustedVerification() { return !this.trustedVerificationSources.isEmpty(); }
    public Set<String> getTrustedVerificationSources() { return Set.copyOf(this.trustedVerificationSources); }
    public Set<String> getUnverifiedChangeTargets() { return Set.copyOf(this.unverifiedChangeTargets); }

    /** 使用 durable Tool Part/Event 重建的执行进度刷新内存投影。 */
    public void applyExecutionProgressProjection(String stage,
                                                int writeCount, int verificationCount,
                                                boolean unverifiedChanges,
                                                Set<String> trustedVerificationSources,
                                                Set<String> unverifiedChangeTargets) {
        this.setStage(stage);
        this.writeCount = Math.max(0, writeCount);
        this.verificationCount = Math.max(0, verificationCount);
        this.trustedVerificationSources = trustedVerificationSources == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(trustedVerificationSources);
        this.unverifiedChangeTargets = unverifiedChangeTargets == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(unverifiedChangeTargets);
        this.unverifiedChanges = unverifiedChanges || !this.unverifiedChangeTargets.isEmpty();
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof AgentContext)) {
            return false;
        }
        AgentContext other = (AgentContext)o;
        if (!other.canEqual(this)) {
            return false;
        }
        if (this.getCurrentPlanIndex() != other.getCurrentPlanIndex()) {
            return false;
        }
        Integer this$studentId = this.getStudentId();
        Integer other$studentId = other.getStudentId();
        if (this$studentId == null ? other$studentId != null : !(this$studentId).equals(other$studentId)) {
            return false;
        }
        Long this$taskId = this.getTaskId();
        Long other$taskId = other.getTaskId();
        if (this$taskId == null ? other$taskId != null : !(this$taskId).equals(other$taskId)) {
            return false;
        }
        String this$sessionId = this.getSessionId();
        String other$sessionId = other.getSessionId();
        if (this$sessionId == null ? other$sessionId != null : !this$sessionId.equals(other$sessionId)) {
            return false;
        }
        StudentProject this$project = this.getProject();
        StudentProject other$project = other.getProject();
        if (this$project == null ? other$project != null : !this$project.equals(other$project)) {
            return false;
        }
        String this$conversationId = this.getConversationId();
        String other$conversationId = other.getConversationId();
        if (this$conversationId == null ? other$conversationId != null : !this$conversationId.equals(other$conversationId)) {
            return false;
        }
        Path this$workspaceRoot = this.getWorkspaceRoot();
        Path other$workspaceRoot = other.getWorkspaceRoot();
        if (this$workspaceRoot == null ? other$workspaceRoot != null : !(this$workspaceRoot).equals(other$workspaceRoot)) {
            return false;
        }
        List this$plan = this.getPlan();
        List other$plan = other.getPlan();
        return !(this$plan == null ? other$plan != null : !(this$plan).equals(other$plan));
    }

    protected boolean canEqual(Object other) {
        return other instanceof AgentContext;
    }

    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        result = result * 59 + this.getCurrentPlanIndex();
        Integer $studentId = this.getStudentId();
        result = result * 59 + ($studentId == null ? 43 : ($studentId).hashCode());
        Long $taskId = this.getTaskId();
        result = result * 59 + ($taskId == null ? 43 : ($taskId).hashCode());
        String $sessionId = this.getSessionId();
        result = result * 59 + ($sessionId == null ? 43 : $sessionId.hashCode());
        StudentProject $project = this.getProject();
        result = result * 59 + ($project == null ? 43 : $project.hashCode());
        String $conversationId = this.getConversationId();
        result = result * 59 + ($conversationId == null ? 43 : $conversationId.hashCode());
        Path $workspaceRoot = this.getWorkspaceRoot();
        result = result * 59 + ($workspaceRoot == null ? 43 : ($workspaceRoot).hashCode());
        List $plan = this.getPlan();
        result = result * 59 + ($plan == null ? 43 : ($plan).hashCode());
        return result;
    }

    public String toString() {
        return "AgentContext(sessionId=" + this.getSessionId() + ", studentId=" + this.getStudentId() + ", project=" + String.valueOf(this.getProject()) + ", conversationId=" + this.getConversationId() + ", taskId=" + this.getTaskId() + ", workspaceRoot=" + String.valueOf(this.getWorkspaceRoot()) + ", plan=" + String.valueOf(this.getPlan()) + ", currentPlanIndex=" + this.getCurrentPlanIndex() + ")";
    }

    public AgentContext(String sessionId, Integer studentId, StudentProject project, String conversationId, Long taskId, Path workspaceRoot, List<PlanItem> plan, int currentPlanIndex) {
        this.sessionId = sessionId;
        this.studentId = studentId;
        this.project = project;
        this.conversationId = conversationId;
        this.taskId = taskId;
        this.workspaceRoot = workspaceRoot;
        this.plan = plan;
        this.currentPlanIndex = currentPlanIndex;
        this.mode = "agent";
    }

    public static class PlanItem {
        private String title;
        private String description;
        private boolean completed;

        public PlanItem() {}
        public PlanItem(String title, String description, boolean completed) {
            this.title = title; this.description = description; this.completed = completed;
        }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public boolean isCompleted() { return completed; }
        public void setCompleted(boolean completed) { this.completed = completed; }
    }
}
