package com.labex.labexagent.projectconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.labex.entity.AgentProjectConfigAuditEvent;
import com.labex.entity.AgentProjectConfigProposal;
import com.labex.entity.AgentProjectConfigRevision;
import com.labex.entity.StudentProject;
import com.labex.labexagent.projectconfig.AgentProjectConfigApplyRecoveryService.ApplyResult;
import com.labex.labexagent.projectconfig.AgentProjectConfigOwnership.ProjectConfigNotFoundException;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.CreateProposalRequest;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.DecisionRequest;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.ProposalResult;
import com.labex.labexagent.projectconfig.AgentProjectConfigRevisionService.ProjectConfigView;
import com.labex.mapper.AgentProjectConfigAuditEventMapper;
import com.labex.mapper.AgentProjectConfigExternalChangeMapper;
import com.labex.mapper.AgentProjectConfigProposalMapper;
import com.labex.mapper.AgentProjectConfigRevisionMapper;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentProjectConfigApplyRecoveryServiceTest {

    private static final String MANIFEST = "{"
            + "\"schemaVersion\": 1,"
            + "\"defaultAgent\": \"agents/main.json\","
            + "\"agents\": [\"agents/main.json\"],"
            + "\"tools\": [\"tools/build.json\"],"
            + "\"mcpServers\": [\"mcp/typescript.json\"],"
            + "\"skills\": [\"skills/backend.md\"],"
            + "\"environment\": \"environment.json\","
            + "\"models\": [\"model-1\"],"
            + "\"runtimeProfile\": \"strict\","
            + "\"networkPolicy\": {\"allowPublicDownloads\": true, \"allowLocalhost\": true, \"allowLan\": false},"
            + "\"verificationPolicy\": {\"requireTests\": true},"
            + "\"capabilities\": {\"enabledTools\": [\"read_file\"], \"deniedTools\": []}"
            + "}";

    private static final String AGENT_MAIN =
            "{\"id\":\"main\",\"name\":\"Main Agent\",\"modelRef\":\"model-1\",\"instructions\":\"Be helpful\"}";
    private static final String AGENT_MAIN_CANDIDATE =
            "{\"id\":\"main\",\"name\":\"Main Agent\",\"modelRef\":\"model-1\",\"instructions\":\"Be concise\"}";
    private static final String TOOL_BUILD =
            "{\"name\":\"build\",\"script\":\"scripts/build.ps1\",\"description\":\"Build the project\"}";
    private static final String MCP_TYPESCRIPT =
            "{\"id\":\"ts\",\"command\":\"npx\",\"args\":[\"tsc\",\"--noEmit\"],\"credentialAlias\":\"ts-credential\"}";
    private static final String SKILL_BACKEND = "# Backend\r\n\r\nBuild and test instructions.\r\n";
    private static final String ENVIRONMENT =
            "{\"variables\":[\"NODE_ENV\"],\"installPolicy\":\"project-only\"}";
    private static final String ENVIRONMENT_CANDIDATE =
            "{\"variables\":[\"NODE_ENV\",\"NODE_OPTIONS\"],\"installPolicy\":\"project-only\"}";

    @TempDir
    Path root;

    private StudentProjectService studentProjectService;
    private AgentProjectConfigRevisionMapper revisionMapper;
    private AgentProjectConfigProposalMapper proposalMapper;
    private AgentProjectConfigAuditEventMapper auditMapper;
    private AgentProjectConfigExternalChangeMapper externalChangeMapper;
    private AgentProjectConfigRevisionService revisionService;
    private AgentProjectConfigProposalService proposalService;
    private AgentProjectConfigApplyRecoveryService recoveryService;
    private AgentProjectConfigFileWriter fileWriter;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = dataSource();
        createTables(dataSource);
        SqlSessionFactory factory = factory(dataSource);
        SqlSession session = factory.openSession(true);
        revisionMapper = session.getMapper(AgentProjectConfigRevisionMapper.class);
        proposalMapper = session.getMapper(AgentProjectConfigProposalMapper.class);
        auditMapper = session.getMapper(AgentProjectConfigAuditEventMapper.class);
        externalChangeMapper = session.getMapper(AgentProjectConfigExternalChangeMapper.class);
        studentProjectService = mock(StudentProjectService.class);
        AgentProjectConfigExternalChangeService externalChangeService =
                new AgentProjectConfigExternalChangeService(studentProjectService, externalChangeMapper);
        revisionService = new AgentProjectConfigRevisionService(studentProjectService, revisionMapper,
                externalChangeService);
        fileWriter = new AgentProjectConfigFileWriter();
        AgentProjectConfigChangeEvidenceService evidenceService =
                new AgentProjectConfigChangeEvidenceService(studentProjectService);
        recoveryService = new AgentProjectConfigApplyRecoveryService(studentProjectService, proposalMapper,
                revisionMapper, auditMapper, externalChangeMapper, fileWriter, evidenceService);
        proposalService = new AgentProjectConfigProposalService(studentProjectService, proposalMapper,
                revisionMapper, fileWriter, recoveryService, externalChangeService);
    }

    @Test
    void reconcileCompletesApplyFromCasOnlyCrashPoint() throws Exception {
        Path project = prepareProject("cas-only");
        Long proposalId = beginApply("crash-cas", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT));
        assertThat(proposalMapper.selectById(proposalId).getStatus()).isEqualTo("applying");

        ApplyResult result = recoveryService.reconcile(7, 12, proposalId);

        assertThat(result.status()).isEqualTo("applied");
        assertThat(result.appliedRevision()).isEqualTo(2L);
        assertThat(proposalMapper.selectById(proposalId).getStatus()).isEqualTo("applied");
        assertThat(proposalMapper.selectById(proposalId).getAppliedRevision()).isEqualTo(2L);
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(2L);
        assertThat(Files.readString(configDir(project).resolve("agents/main.json")))
                .isEqualTo(AGENT_MAIN_CANDIDATE);
        ProjectConfigView view = revisionService.load(7, 12);
        assertThat(view.revision()).isEqualTo(2L);
        assertThat(view.externalChangePending()).isFalse();
        assertThat(stagingDir(project, proposalKeyOf(proposalId))).doesNotExist();
        assertThat(auditMapper.selectCount(new QueryWrapper<AgentProjectConfigAuditEvent>()
                .eq("project_id", 12).eq("event_type", "applied"))).isEqualTo(1L);
    }

    @Test
    void reconcileCompletesApplyFromEvidenceCrashPointWithoutDuplicateEvidence() throws Exception {
        Path project = prepareProject("evidence-crash");
        Long proposalId = beginApply("crash-evidence", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT));
        recoveryService.ensureStaged(7, 12, proposalId);
        recoveryService.recordEvidence(7, 12, proposalId);
        assertThat(evidenceFileCount(project)).isEqualTo(1);

        ApplyResult result = recoveryService.reconcile(7, 12, proposalId);

        assertThat(result.status()).isEqualTo("applied");
        assertThat(result.appliedRevision()).isEqualTo(2L);
        assertThat(evidenceFileCount(project)).isEqualTo(1);
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(2L);
    }

    @Test
    void reconcileCompletesApplyFromPublishCrashPoint() throws Exception {
        Path project = prepareProject("publish-crash");
        Long proposalId = beginApply("crash-publish", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT));
        recoveryService.ensureStaged(7, 12, proposalId);
        recoveryService.recordEvidence(7, 12, proposalId);
        recoveryService.publish(7, 12, proposalId);
        assertThat(Files.readString(configDir(project).resolve("agents/main.json")))
                .isEqualTo(AGENT_MAIN_CANDIDATE);
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(1L);

        ApplyResult result = recoveryService.reconcile(7, 12, proposalId);

        assertThat(result.status()).isEqualTo("applied");
        assertThat(result.appliedRevision()).isEqualTo(2L);
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(2L);
        assertThat(revisionService.load(7, 12).externalChangePending()).isFalse();
    }

    @Test
    void reconcileCompletesApplyFromRevisionInsertCrashPointWithoutDuplicateRevision() throws Exception {
        Path project = prepareProject("revision-crash");
        Long proposalId = beginApply("crash-revision", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT));
        recoveryService.ensureStaged(7, 12, proposalId);
        recoveryService.recordEvidence(7, 12, proposalId);
        recoveryService.publish(7, 12, proposalId);
        recoveryService.insertRevision(7, 12, proposalId);
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(2L);
        assertThat(proposalMapper.selectById(proposalId).getStatus()).isEqualTo("applying");

        ApplyResult result = recoveryService.reconcile(7, 12, proposalId);

        assertThat(result.status()).isEqualTo("applied");
        assertThat(result.appliedRevision()).isEqualTo(2L);
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(2L);
    }

    @Test
    void reconcileHealsPartialPublishCrashAcrossMultipleFiles() throws Exception {
        Path project = prepareProject("partial-publish");
        Long proposalId = beginApply("crash-partial", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT_CANDIDATE));
        recoveryService.ensureStaged(7, 12, proposalId);
        recoveryService.recordEvidence(7, 12, proposalId);
        String stageId = AgentProjectConfigApplyRecoveryService.stageId(12, proposalKeyOf(proposalId));
        fileWriter.publishStaged(project, stageId, List.of("agents/main.json"));
        assertThat(Files.readString(configDir(project).resolve("agents/main.json")))
                .isEqualTo(AGENT_MAIN_CANDIDATE);
        assertThat(Files.readString(configDir(project).resolve("environment.json")))
                .isEqualTo(ENVIRONMENT);

        ApplyResult result = recoveryService.reconcile(7, 12, proposalId);

        assertThat(result.status()).isEqualTo("applied");
        assertThat(Files.readString(configDir(project).resolve("environment.json")))
                .isEqualTo(ENVIRONMENT_CANDIDATE);
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(2L);
        assertThat(revisionService.load(7, 12).externalChangePending()).isFalse();
    }

    @Test
    void reconcileOnMissingStagingMarksTypedFailedWithoutRevisionOrTreeChange() throws Exception {
        Path project = prepareProject("missing-staging");
        Long proposalId = beginApply("crash-no-staging", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT));
        deleteTree(stagingDir(project, proposalKeyOf(proposalId)));

        ApplyResult result = recoveryService.reconcile(7, 12, proposalId);

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.failureReason()).isEqualTo(
                AgentProjectConfigApplyRecoveryService.FAILURE_STAGING_UNAVAILABLE);
        assertThat(proposalMapper.selectById(proposalId).getStatus()).isEqualTo("failed");
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(1L);
        assertThat(Files.readString(configDir(project).resolve("agents/main.json"))).isEqualTo(AGENT_MAIN);
        assertThat(auditMapper.selectCount(new QueryWrapper<AgentProjectConfigAuditEvent>()
                .eq("project_id", 12).eq("event_type", "failed"))).isEqualTo(1L);
    }

    @Test
    void reconcileOnInvalidStagingMarksTypedFailedWithoutFalseAppliedHead() throws Exception {
        Path project = prepareProject("invalid-staging");
        Long proposalId = beginApply("crash-bad-staging", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT));
        Files.writeString(stagingDir(project, proposalKeyOf(proposalId)).resolve("agent.json"), "not json");

        ApplyResult result = recoveryService.reconcile(7, 12, proposalId);

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.failureReason()).isEqualTo(
                AgentProjectConfigApplyRecoveryService.FAILURE_VALIDATION);
        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.reasonCode()).isEqualTo(AgentProjectConfigValidator.REASON_MALFORMED_JSON));
        assertThat(proposalMapper.selectById(proposalId).getStatus()).isEqualTo("failed");
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(1L);
        assertThat(Files.readString(configDir(project).resolve("agents/main.json"))).isEqualTo(AGENT_MAIN);
    }

    @Test
    void reconcileOnTamperedPublishedTreeConvergesToApprovedCandidateWithoutReverting() throws Exception {
        Path project = prepareProject("tampered-published");
        Long proposalId = beginApply("crash-tampered", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT));
        recoveryService.ensureStaged(7, 12, proposalId);
        recoveryService.recordEvidence(7, 12, proposalId);
        recoveryService.publish(7, 12, proposalId);
        Files.writeString(configDir(project).resolve("agents/main.json"), "garbage from a crashed editor");

        ApplyResult result = recoveryService.reconcile(7, 12, proposalId);

        assertThat(result.status()).isEqualTo("applied");
        assertThat(result.appliedRevision()).isEqualTo(2L);
        assertThat(Files.readString(configDir(project).resolve("agents/main.json")))
                .isEqualTo(AGENT_MAIN_CANDIDATE);
        assertThat(revisionService.load(7, 12).revision()).isEqualTo(2L);
        assertThat(revisionService.load(7, 12).externalChangePending()).isFalse();
    }

    @Test
    void reconcileOnConflictMarksTypedFailedWithoutFalseAppliedHead() throws Exception {
        Path project = prepareProject("conflict");
        ProposalResult winning = proposalService.createProposal(7, 12, CreateProposalRequest.of(1L,
                candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT), "winner", "win-key"));
        Long losing = beginApply("lose-key", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT_CANDIDATE));
        proposalService.decide(7, 12, winning.proposalId(), DecisionRequest.approve(1L, "win-dec"));
        assertThat(revisionService.load(7, 12).revision()).isEqualTo(2L);
        assertThat(proposalMapper.selectById(losing).getStatus()).isEqualTo("applying");

        ApplyResult result = recoveryService.reconcile(7, 12, losing);

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.failureReason()).isEqualTo(AgentProjectConfigApplyRecoveryService.FAILURE_CONFLICT);
        assertThat(proposalMapper.selectById(losing).getStatus()).isEqualTo("failed");
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(2L);
        assertThat(Files.readString(configDir(project).resolve("environment.json"))).isEqualTo(ENVIRONMENT);
        assertThat(revisionService.load(7, 12).revision()).isEqualTo(2L);
    }

    @Test
    void reconcileOnAppliedProposalIsAnIdempotentNoop() throws Exception {
        prepareProject("already-applied");
        Long proposalId = beginApply("crash-applied", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT));
        ApplyResult first = recoveryService.reconcile(7, 12, proposalId);
        assertThat(first.status()).isEqualTo("applied");

        ApplyResult second = recoveryService.reconcile(7, 12, proposalId);

        assertThat(second.status()).isEqualTo("applied");
        assertThat(second.appliedRevision()).isEqualTo(2L);
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(2L);
        assertThat(auditMapper.selectCount(new QueryWrapper<AgentProjectConfigAuditEvent>()
                .eq("project_id", 12).eq("event_type", "applied"))).isEqualTo(1L);
    }

    @Test
    void reconcileOnPendingProposalDoesNotApplyWithoutApproval() throws Exception {
        Path project = prepareProject("pending-noop");
        ProposalResult created = proposalService.createProposal(7, 12, CreateProposalRequest.of(1L,
                candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT), "pending", "pending-key"));

        ApplyResult result = recoveryService.reconcile(7, 12, created.proposalId());

        assertThat(result.status()).isEqualTo("pending");
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(1L);
        assertThat(Files.readString(configDir(project).resolve("agents/main.json"))).isEqualTo(AGENT_MAIN);
    }

    @Test
    void reconcileOnForeignProjectFailsClosed() throws Exception {
        prepareProject("foreign");
        when(studentProjectService.getOwnedProject(7, 999)).thenReturn(null);

        ProjectConfigNotFoundException error = catchThrowableOfType(
                () -> recoveryService.reconcile(7, 999, 1L), ProjectConfigNotFoundException.class);

        assertThat(error.getMessage()).isEqualTo("Project not found");
    }

    @Test
    void sameConfigDigestDifferentTreeSecondApplyFailsVerificationWithOnlyOneAppliedAndHeadMatchesDisk()
            throws Exception {
        Path project = prepareProjectWithUndeclaredFile("samecfg", "notes.md", "# Notes\r\n");
        Long omitting = beginApply("samecfg-omitting", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT));
        recoveryService.ensureStaged(7, 12, omitting);
        recoveryService.recordEvidence(7, 12, omitting);
        recoveryService.publish(7, 12, omitting);
        assertThat(configDir(project).resolve("notes.md")).doesNotExist();

        Long full = beginApply("samecfg-full", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT, "# Notes\r\n"));
        recoveryService.ensureStaged(7, 12, full);
        recoveryService.recordEvidence(7, 12, full);
        recoveryService.publish(7, 12, full);
        assertThat(Files.readString(configDir(project).resolve("notes.md"))).isEqualTo("# Notes\r\n");

        ApplyResult omittingInsert = recoveryService.insertRevision(7, 12, omitting);
        assertThat(omittingInsert.status()).isEqualTo("failed");
        assertThat(omittingInsert.failureReason())
                .isEqualTo(AgentProjectConfigApplyRecoveryService.FAILURE_VERIFICATION);
        assertThat(proposalMapper.selectById(omitting).getStatus()).isEqualTo("failed");
        assertThat(proposalMapper.selectById(omitting).getAppliedRevision()).isNull();
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(1L);

        ApplyResult fullReconcile = recoveryService.reconcile(7, 12, full);
        assertThat(fullReconcile.status()).isEqualTo("applied");
        assertThat(fullReconcile.appliedRevision()).isEqualTo(2L);

        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(2L);
        AgentProjectConfigRevision head = revisionMapper.selectOne(
                new QueryWrapper<AgentProjectConfigRevision>()
                        .eq("project_id", 12).orderByDesc("revision").last("LIMIT 1"));
        AgentProjectConfigApplyRecoveryService.TreeDigests.ScanResult disk =
                AgentProjectConfigApplyRecoveryService.TreeDigests.scanProtected(project);
        assertThat(AgentProjectConfigApplyRecoveryService.TreeDigests.treeDigestOf(head))
                .isEqualTo(disk.treeDigest());
        ProjectConfigView view = revisionService.load(7, 12);
        assertThat(view.revision()).isEqualTo(2L);
        assertThat(view.externalChangePending()).isFalse();
    }

    @Test
    void sameConfigDigestDifferentTreeCannotTakeIdempotentShortcut() throws Exception {
        prepareProject("samecfg-shortcut");
        Long proposalId = beginApply("shortcut-key", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT));
        recoveryService.ensureStaged(7, 12, proposalId);
        recoveryService.recordEvidence(7, 12, proposalId);
        recoveryService.publish(7, 12, proposalId);

        AgentProjectConfigRevision impostor = new AgentProjectConfigRevision();
        impostor.setStudentId(7);
        impostor.setProjectId(12);
        impostor.setRevision(2L);
        impostor.setConfigDigest(proposalMapper.selectById(proposalId).getCandidateConfigDigest());
        impostor.setTreeReference("tree:" + "f".repeat(64));
        impostor.setSchemaVersion("1");
        impostor.setNormalizedConfig("{}");
        impostor.setValidationStatus("valid");
        revisionMapper.insert(impostor);

        ApplyResult result = recoveryService.insertRevision(7, 12, proposalId);

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.failureReason()).isEqualTo(AgentProjectConfigApplyRecoveryService.FAILURE_CONFLICT);
        assertThat(proposalMapper.selectById(proposalId).getStatus()).isEqualTo("failed");
        assertThat(proposalMapper.selectById(proposalId).getAppliedRevision()).isNull();
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(2L);
    }

    @Test
    void reconcileFailsVerificationWhenDiskTreeDiffersBeforeRevisionInsert() throws Exception {
        Path project = prepareProject("tree-fence");
        Long proposalId = beginApply("fence-key", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT));
        recoveryService.ensureStaged(7, 12, proposalId);
        recoveryService.recordEvidence(7, 12, proposalId);
        recoveryService.publish(7, 12, proposalId);
        Files.writeString(configDir(project).resolve("agents/main.json"), "garbage from a crashed editor");

        ApplyResult result = recoveryService.insertRevision(7, 12, proposalId);

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.failureReason()).isEqualTo(AgentProjectConfigApplyRecoveryService.FAILURE_VERIFICATION);
        assertThat(proposalMapper.selectById(proposalId).getStatus()).isEqualTo("failed");
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(1L);
        assertThat(proposalMapper.selectById(proposalId).getAppliedRevision()).isNull();
        assertThat(Files.readString(configDir(project).resolve("agents/main.json")))
                .isEqualTo("garbage from a crashed editor");
    }

    @Test
    void oversizedStagingFileFailsClosedWithSizeLimitBeforeAnyRevision() throws Exception {
        Path project = prepareProject("oversized-staging");
        Long proposalId = beginApply("oversize-key", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT));
        String stageId = AgentProjectConfigApplyRecoveryService.stageId(12, proposalKeyOf(proposalId));
        Path stagingDir = project.resolve(".labex-agent/.config-staging").resolve(stageId);
        Files.writeString(stagingDir.resolve("huge.bin"), "x".repeat(300 * 1024));

        ApplyResult result = recoveryService.reconcile(7, 12, proposalId);

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.failureReason()).isEqualTo(AgentProjectConfigApplyRecoveryService.FAILURE_VALIDATION);
        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.reasonCode()).isEqualTo(AgentProjectConfigValidator.REASON_PATH_SIZE_LIMIT));
        assertThat(proposalMapper.selectById(proposalId).getStatus()).isEqualTo("failed");
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(1L);
        assertThat(Files.readString(configDir(project).resolve("agents/main.json"))).isEqualTo(AGENT_MAIN);
    }

    private Long beginApply(String proposalKey, Map<String, String> candidate) throws Exception {
        ProposalResult created = proposalService.createProposal(7, 12, CreateProposalRequest.of(1L,
                candidate, "crash test", proposalKey));
        int updated = proposalMapper.update(null, new UpdateWrapper<AgentProjectConfigProposal>()
                .eq("proposal_id", created.proposalId())
                .eq("status", "pending")
                .set("status", "applying")
                .set("decision_idempotency_key", "dec-" + proposalKey)
                .set("decision_actor", "student:7")
                .set("decision_time", LocalDateTime.now()));
        assertThat(updated).isEqualTo(1);
        return created.proposalId();
    }

    private String proposalKeyOf(Long proposalId) {
        return proposalMapper.selectById(proposalId).getProposalKey();
    }

    private Map<String, String> candidate(String agentContent, String environmentContent) {
        return candidate(agentContent, environmentContent, null);
    }

    private Map<String, String> candidate(String agentContent, String environmentContent,
                                          String extraFileContent) {
        Map<String, String> candidate = new LinkedHashMap<>();
        candidate.put("agent.json", MANIFEST);
        candidate.put("agents/main.json", agentContent);
        candidate.put("tools/build.json", TOOL_BUILD);
        candidate.put("mcp/typescript.json", MCP_TYPESCRIPT);
        candidate.put("skills/backend.md", SKILL_BACKEND);
        candidate.put("environment.json", environmentContent);
        if (extraFileContent != null) {
            candidate.put("notes.md", extraFileContent);
        }
        return candidate;
    }

    private Path prepareProjectWithUndeclaredFile(String name, String extraFile, String extraContent)
            throws Exception {
        Path project = root.resolve(name);
        Path config = configDir(project);
        Files.createDirectories(config.resolve("agents"));
        Files.createDirectories(config.resolve("tools"));
        Files.createDirectories(config.resolve("mcp"));
        Files.createDirectories(config.resolve("skills"));
        Files.writeString(config.resolve("agent.json"), MANIFEST);
        Files.writeString(config.resolve("agents/main.json"), AGENT_MAIN);
        Files.writeString(config.resolve("tools/build.json"), TOOL_BUILD);
        Files.writeString(config.resolve("mcp/typescript.json"), MCP_TYPESCRIPT);
        Files.writeString(config.resolve("skills/backend.md"), SKILL_BACKEND);
        Files.writeString(config.resolve("environment.json"), ENVIRONMENT);
        Files.writeString(config.resolve(extraFile), extraContent);
        registerOwnedProject(7, 12, project);
        ProjectConfigView view = revisionService.load(7, 12);
        assertThat(view.valid()).isTrue();
        assertThat(view.revision()).isEqualTo(1L);
        assertThat(view.externalChangePending()).isFalse();
        return project;
    }

    private Path prepareProject(String name) throws Exception {
        Path project = root.resolve(name);
        Path config = configDir(project);
        Files.createDirectories(config.resolve("agents"));
        Files.createDirectories(config.resolve("tools"));
        Files.createDirectories(config.resolve("mcp"));
        Files.createDirectories(config.resolve("skills"));
        Files.writeString(config.resolve("agent.json"), MANIFEST);
        Files.writeString(config.resolve("agents/main.json"), AGENT_MAIN);
        Files.writeString(config.resolve("tools/build.json"), TOOL_BUILD);
        Files.writeString(config.resolve("mcp/typescript.json"), MCP_TYPESCRIPT);
        Files.writeString(config.resolve("skills/backend.md"), SKILL_BACKEND);
        Files.writeString(config.resolve("environment.json"), ENVIRONMENT);
        registerOwnedProject(7, 12, project);
        ProjectConfigView view = revisionService.load(7, 12);
        assertThat(view.valid()).isTrue();
        assertThat(view.revision()).isEqualTo(1L);
        return project;
    }

    private Path configDir(Path project) {
        return project.resolve(".labex-agent").resolve("project");
    }

    private Path stagingDir(Path project, String proposalKey) {
        String stageId = AgentProjectConfigApplyRecoveryService.stageId(12, proposalKey);
        return project.resolve(".labex-agent").resolve(".config-staging").resolve(stageId);
    }

    private long evidenceFileCount(Path project) throws Exception {
        Path dir = project.resolve(".labex-agent").resolve("config-evidence");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            return stream.count();
        }
    }

    private void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private void registerOwnedProject(int studentId, int projectId, Path workspaceRoot) {
        StudentProject project = new StudentProject();
        project.setProjectId(projectId);
        project.setStudentId(studentId);
        project.setWorkspacePath(workspaceRoot.toString());
        when(studentProjectService.getOwnedProject(studentId, projectId)).thenReturn(project);
    }

    private SqlSessionFactory factory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("apply-recovery", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentProjectConfigRevisionMapper.class);
        configuration.addMapper(AgentProjectConfigProposalMapper.class);
        configuration.addMapper(AgentProjectConfigAuditEventMapper.class);
        configuration.addMapper(AgentProjectConfigExternalChangeMapper.class);
        return new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:apply_recovery_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void createTables(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_agent_project_config_revision (
                        revision_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        revision BIGINT NOT NULL,
                        config_digest VARCHAR(64) NOT NULL,
                        tree_reference VARCHAR(2048) DEFAULT NULL,
                        schema_version VARCHAR(32) NOT NULL DEFAULT '1',
                        normalized_config LONGTEXT NOT NULL,
                        validation_status VARCHAR(32) DEFAULT NULL,
                        source_actor VARCHAR(128) DEFAULT NULL,
                        create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                        update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("ALTER TABLE t_agent_project_config_revision "
                    + "ADD CONSTRAINT uk_agent_project_config_revision UNIQUE (project_id, revision)");
            statement.execute("CREATE INDEX idx_agent_project_config_revision_owner "
                    + "ON t_agent_project_config_revision (student_id, project_id, revision)");
            statement.execute("""
                    CREATE TABLE t_agent_project_config_external_change (
                        external_change_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        base_revision BIGINT NOT NULL,
                        observed_tree_digest VARCHAR(64) NOT NULL,
                        changed_path_summary TEXT DEFAULT NULL,
                        status VARCHAR(32) NOT NULL DEFAULT 'external_change_pending',
                        proposal_id BIGINT DEFAULT NULL,
                        detected_at DATETIME(3) DEFAULT NULL,
                        create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                        update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("ALTER TABLE t_agent_project_config_external_change "
                    + "ADD CONSTRAINT uk_agent_external_change_pending "
                    + "UNIQUE (project_id, base_revision, observed_tree_digest, status)");
            statement.execute("""
                    CREATE TABLE t_agent_project_config_proposal (
                        proposal_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        proposal_key VARCHAR(192) NOT NULL,
                        base_revision BIGINT NOT NULL,
                        candidate_config_digest VARCHAR(64) NOT NULL,
                        patch_reference VARCHAR(2048) DEFAULT NULL,
                        changed_path_summary TEXT DEFAULT NULL,
                        reason VARCHAR(2048) DEFAULT NULL,
                        origin_task_id BIGINT DEFAULT NULL,
                        origin_execution_epoch BIGINT DEFAULT NULL,
                        origin_tool_call_id VARCHAR(128) DEFAULT NULL,
                        source VARCHAR(64) NOT NULL DEFAULT 'agent',
                        creator VARCHAR(128) DEFAULT NULL,
                        status VARCHAR(32) NOT NULL DEFAULT 'pending',
                        expires_time DATETIME NOT NULL,
                        decision_idempotency_key VARCHAR(128) DEFAULT NULL,
                        decision_actor VARCHAR(128) DEFAULT NULL,
                        decision_time DATETIME(3) DEFAULT NULL,
                        applied_revision BIGINT DEFAULT NULL,
                        create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                        update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_agent_project_config_proposal_key (project_id, proposal_key),
                        UNIQUE KEY uk_agent_project_config_proposal_decision (project_id, decision_idempotency_key),
                        INDEX idx_agent_project_config_proposal_owner (student_id, project_id, status, create_time),
                        INDEX idx_agent_project_config_proposal_expiry (status, expires_time),
                        CHECK (expires_time <= TIMESTAMPADD(MINUTE, 1, create_time))
                    )
                    """);
            statement.execute("""
                    CREATE TABLE t_agent_project_config_audit_event (
                        event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        event_type VARCHAR(48) NOT NULL,
                        actor VARCHAR(128) DEFAULT NULL,
                        reason VARCHAR(2048) DEFAULT NULL,
                        previous_status VARCHAR(32) DEFAULT NULL,
                        next_status VARCHAR(32) DEFAULT NULL,
                        before_digest VARCHAR(64) DEFAULT NULL,
                        after_digest VARCHAR(64) DEFAULT NULL,
                        changed_path_summary TEXT DEFAULT NULL,
                        task_id BIGINT DEFAULT NULL,
                        execution_epoch BIGINT DEFAULT NULL,
                        idempotency_key VARCHAR(192) NOT NULL,
                        create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_agent_project_config_audit_idempotency (project_id, idempotency_key)
                    )
                    """);
        }
    }
}
