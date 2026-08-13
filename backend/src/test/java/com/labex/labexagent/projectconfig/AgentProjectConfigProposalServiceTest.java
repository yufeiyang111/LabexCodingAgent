package com.labex.labexagent.projectconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.labex.entity.AgentProjectConfigAuditEvent;
import com.labex.entity.AgentProjectConfigExternalChange;
import com.labex.entity.AgentProjectConfigProposal;
import com.labex.entity.AgentProjectConfigRevision;
import com.labex.entity.StudentProject;
import com.labex.labexagent.projectconfig.AgentProjectConfigOwnership.ProjectConfigNotFoundException;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.CreateProposalRequest;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.DecisionRequest;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.DecisionResult;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.ProposalDetail;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.ProposalException;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.ProposalResult;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.ProposalSummary;
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

class AgentProjectConfigProposalServiceTest {

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

    @TempDir
    Path root;

    private StudentProjectService studentProjectService;
    private AgentProjectConfigRevisionMapper revisionMapper;
    private AgentProjectConfigProposalMapper proposalMapper;
    private AgentProjectConfigAuditEventMapper auditMapper;
    private AgentProjectConfigExternalChangeMapper externalChangeMapper;
    private AgentProjectConfigRevisionService revisionService;
    private AgentProjectConfigProposalService proposalService;
    private AgentProjectConfigFileWriter fileWriter;
    private AgentProjectConfigApplyRecoveryService recoveryService;
    private AgentProjectConfigExternalChangeService externalChangeService;

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
        externalChangeService =
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
    void createProposalPersistsPendingProposalWithStagedCandidateAndOneMinuteExpiry() throws Exception {
        Path project = prepareProject("create-pending");
        LocalDateTime before = LocalDateTime.now();

        ProposalResult result = createProposal("key-create", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT), null);

        LocalDateTime after = LocalDateTime.now();
        assertThat(result.status()).isEqualTo("pending");
        assertThat(result.baseRevision()).isEqualTo(1L);
        assertThat(result.candidateConfigDigest()).matches("[0-9a-f]{64}");
        assertThat(result.expiresTime()).isAfter(before.plusSeconds(59));
        assertThat(result.expiresTime()).isBefore(after.plusSeconds(61));

        AgentProjectConfigProposal row = proposalMapper.selectById(result.proposalId());
        assertThat(row.getProposalKey()).isEqualTo("key-create");
        assertThat(row.getSource()).isEqualTo("agent");
        assertThat(row.getCreator()).isEqualTo("student:7");
        assertThat(row.getChangedPathSummary()).isEqualTo("agents/main.json");
        assertThat(row.getPatchReference()).startsWith("tree:");
        assertThat(row.getExpiresTime()).isNotNull();

        Path staged = project.resolve(".labex-agent/.config-staging/"
                + AgentProjectConfigApplyRecoveryService.stageId(12, "key-create"));
        assertThat(staged.resolve("agent.json")).exists();
        assertThat(staged.resolve("agents/main.json")).exists();
        assertThat(Files.readString(staged.resolve("agents/main.json"))).isEqualTo(AGENT_MAIN_CANDIDATE);
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(1L);
        assertThat(auditMapper.selectCount(new QueryWrapper<AgentProjectConfigAuditEvent>()
                .eq("project_id", 12).eq("event_type", "proposed"))).isEqualTo(1L);
        assertThat(Files.readString(configDir(project).resolve("agents/main.json"))).isEqualTo(AGENT_MAIN);
    }

    @Test
    void duplicateProposalKeyReturnsFirstProposalWithoutDuplicateRow() throws Exception {
        prepareProject("duplicate-key");
        ProposalResult first = createProposal("same-key", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT), null);

        ProposalResult second = createProposal("same-key", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT), null);

        assertThat(second.proposalId()).isEqualTo(first.proposalId());
        assertThat(proposalMapper.selectCount(new QueryWrapper<AgentProjectConfigProposal>()
                .eq("project_id", 12))).isEqualTo(1L);
    }

    @Test
    void createProposalWithStaleExpectedRevisionReturns409() throws Exception {
        prepareProject("stale-create");

        ProposalException error = catchThrowableOfType(() -> createProposal("stale-key",
                candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT), 0L), ProposalException.class);

        assertThat(error.httpStatus()).isEqualTo(409);
        assertThat(error.reasonCode()).isEqualTo("STALE_REVISION");
        assertThat(proposalMapper.selectCount(new QueryWrapper<AgentProjectConfigProposal>()
                .eq("project_id", 12))).isZero();
    }

    @Test
    void createProposalRejectsCapabilityAboveCeilingWithStructuredErrors() throws Exception {
        prepareProject("capability-ceiling");
        String manifest = MANIFEST.replace("\"capabilities\": {\"enabledTools\": [\"read_file\"], \"deniedTools\": []}",
                "\"capabilities\": {\"enabledTools\": [\"read_file\"], \"deniedTools\": [], \"allowWslShutdown\": true}");

        ProposalException error = catchThrowableOfType(() -> createProposal("cap-key",
                candidate(manifest, AGENT_MAIN_CANDIDATE, ENVIRONMENT), null), ProposalException.class);

        assertThat(error.httpStatus()).isEqualTo(400);
        assertThat(error.reasonCode()).isEqualTo("INVALID_CANDIDATE");
        assertThat(error.errors()).anySatisfy(validation ->
                assertThat(validation.reasonCode()).isEqualTo(
                        AgentProjectConfigValidator.REASON_CAPABILITY_ABOVE_CEILING));
        assertThat(proposalMapper.selectCount(new QueryWrapper<AgentProjectConfigProposal>()
                .eq("project_id", 12))).isZero();
        assertThat(root.resolve("capability-ceiling").resolve(".labex-agent/.config-staging")).doesNotExist();
    }

    @Test
    void createProposalRejectsTraversalAndAbsoluteCandidatePaths() throws Exception {
        prepareProject("bad-paths");

        Map<String, String> traversal = candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT);
        traversal.put("../outside.json", "{}");
        ProposalException traversalError = catchThrowableOfType(() -> createProposal("traversal-key",
                traversal, null), ProposalException.class);
        assertThat(traversalError.httpStatus()).isEqualTo(400);
        assertThat(traversalError.errors()).anySatisfy(validation ->
                assertThat(validation.reasonCode()).isEqualTo(
                        AgentProjectConfigValidator.REASON_PATH_TRAVERSAL));

        Map<String, String> absolute = candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT);
        absolute.put("C:/outside.json", "{}");
        ProposalException absoluteError = catchThrowableOfType(() -> createProposal("absolute-key",
                absolute, null), ProposalException.class);
        assertThat(absoluteError.httpStatus()).isEqualTo(400);
        assertThat(absoluteError.errors()).anySatisfy(validation ->
                assertThat(validation.reasonCode()).isEqualTo(
                        AgentProjectConfigValidator.REASON_PATH_ABSOLUTE));
        assertThat(proposalMapper.selectCount(new QueryWrapper<AgentProjectConfigProposal>()
                .eq("project_id", 12))).isZero();
    }

    @Test
    void createProposalRejectsMissingManifestAndUndeclaredReferences() throws Exception {
        prepareProject("missing-references");

        Map<String, String> noManifest = candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT);
        noManifest.remove("agent.json");
        ProposalException manifestError = catchThrowableOfType(() -> createProposal("no-manifest",
                noManifest, null), ProposalException.class);
        assertThat(manifestError.httpStatus()).isEqualTo(400);
        assertThat(manifestError.errors()).anySatisfy(validation ->
                assertThat(validation.reasonCode()).isEqualTo(
                        AgentProjectConfigValidator.REASON_MISSING_REQUIRED_KEY));

        Map<String, String> missingChild = candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT);
        missingChild.remove("agents/main.json");
        ProposalException childError = catchThrowableOfType(() -> createProposal("no-child",
                missingChild, null), ProposalException.class);
        assertThat(childError.httpStatus()).isEqualTo(400);
        assertThat(childError.errors()).anySatisfy(validation ->
                assertThat(validation.reasonCode()).isEqualTo(
                        AgentProjectConfigValidator.REASON_REFERENCE_UNRESOLVABLE));
        assertThat(proposalMapper.selectCount(new QueryWrapper<AgentProjectConfigProposal>()
                .eq("project_id", 12))).isZero();
    }

    @Test
    void foreignProjectIsIndistinguishableFromNotFoundOnEveryEntryPoint() throws Exception {
        prepareProject("foreign");

        when(studentProjectService.getOwnedProject(7, 999)).thenReturn(null);

        ProposalException createError = catchThrowableOfType(() -> createProposalOn(7, 999,
                "foreign-key", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT)), ProposalException.class);
        assertThat(createError.httpStatus()).isEqualTo(404);
        assertThat(createError.getMessage()).isEqualTo("Project not found");

        ProposalException listError = catchThrowableOfType(
                () -> proposalService.listProposals(7, 999), ProposalException.class);
        assertThat(listError.httpStatus()).isEqualTo(404);
        assertThat(listError.getMessage()).isEqualTo("Project not found");

        ProposalException detailError = catchThrowableOfType(
                () -> proposalService.getProposal(7, 999, 1L), ProposalException.class);
        assertThat(detailError.httpStatus()).isEqualTo(404);
        assertThat(detailError.getMessage()).isEqualTo("Project not found");

        ProposalException decisionError = catchThrowableOfType(
                () -> proposalService.decide(7, 999, 1L, DecisionRequest.approve(1L, "foreign-dec")),
                ProposalException.class);
        assertThat(decisionError.httpStatus()).isEqualTo(404);
    }

    @Test
    void approveDecisionAppliesRevisionUpdatesTreeAndAudits() throws Exception {
        Path project = prepareProject("approve-apply");
        ProposalResult created = createProposal("approve-key", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT), null);

        DecisionResult decision = proposalService.decide(7, 12, created.proposalId(),
                DecisionRequest.approve(1L, "approve-dec"));

        assertThat(decision.status()).isEqualTo("applied");
        assertThat(decision.appliedRevision()).isEqualTo(2L);
        assertThat(decision.decisionTime()).isNotNull();
        assertThat(Files.readString(configDir(project).resolve("agents/main.json")))
                .isEqualTo(AGENT_MAIN_CANDIDATE);
        AgentProjectConfigRevision revision = revisionMapper.selectOne(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12).eq("revision", 2L));
        assertThat(revision).isNotNull();
        assertThat(revision.getConfigDigest()).isEqualTo(created.candidateConfigDigest());
        assertThat(revision.getSourceActor()).isEqualTo("student:7");
        assertThat(revision.getNormalizedConfig()).contains("\"document\"", "\"treeDigest\"", "\"files\"");
        List<String> events = auditEvents();
        assertThat(events).contains("proposed", "approved", "applied");
        assertThat(project.resolve(".labex-agent/config-evidence")).exists();
        ProjectConfigView view = revisionService.load(7, 12);
        assertThat(view.revision()).isEqualTo(2L);
        assertThat(view.externalChangePending()).isFalse();
    }

    @Test
    void rejectDecisionKeepsTreeAndAuditsRejection() throws Exception {
        Path project = prepareProject("reject");
        ProposalResult created = createProposal("reject-key", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT), null);

        DecisionResult decision = proposalService.decide(7, 12, created.proposalId(),
                DecisionRequest.reject(1L, "reject-dec"));

        assertThat(decision.status()).isEqualTo("rejected");
        assertThat(decision.appliedRevision()).isNull();
        assertThat(Files.readString(configDir(project).resolve("agents/main.json"))).isEqualTo(AGENT_MAIN);
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(1L);
        assertThat(auditEvents()).contains("proposed", "rejected");
        assertThat(project.resolve(".labex-agent/config-evidence")).doesNotExist();
    }

    @Test
    void expiredProposalIsCasToExpiredAndReturns410() throws Exception {
        Path project = prepareProject("expired");
        ProposalResult created = createProposal("expire-key", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT), null);
        proposalMapper.update(null, new UpdateWrapper<AgentProjectConfigProposal>()
                .eq("proposal_id", created.proposalId())
                .set("expires_time", LocalDateTime.now().minusSeconds(5)));

        ProposalException error = catchThrowableOfType(() -> proposalService.decide(7, 12,
                created.proposalId(), DecisionRequest.approve(1L, "expire-dec")), ProposalException.class);

        assertThat(error.httpStatus()).isEqualTo(410);
        assertThat(error.reasonCode()).isEqualTo("PROPOSAL_EXPIRED");
        assertThat(proposalMapper.selectById(created.proposalId()).getStatus()).isEqualTo("expired");
        assertThat(auditEvents()).contains("proposed", "expired");
        assertThat(Files.readString(configDir(project).resolve("agents/main.json"))).isEqualTo(AGENT_MAIN);
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(1L);
    }

    @Test
    void staleProposalIsCasToStaleAndReturns409() throws Exception {
        Path project = prepareProject("stale-decision");
        ProposalResult first = createProposal("stale-first", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT), null);
        ProposalResult second = createProposal("stale-second", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT), null);
        proposalService.decide(7, 12, second.proposalId(), DecisionRequest.approve(1L, "second-dec"));
        assertThat(revisionService.load(7, 12).revision()).isEqualTo(2L);

        ProposalException error = catchThrowableOfType(() -> proposalService.decide(7, 12,
                first.proposalId(), DecisionRequest.approve(2L, "first-dec")), ProposalException.class);

        assertThat(error.httpStatus()).isEqualTo(409);
        assertThat(error.reasonCode()).isEqualTo("STALE_REVISION");
        assertThat(proposalMapper.selectById(first.proposalId()).getStatus()).isEqualTo("stale");
        assertThat(auditEvents()).contains("conflicted");
        assertThat(Files.readString(configDir(project).resolve("agents/main.json")))
                .isEqualTo(AGENT_MAIN_CANDIDATE);
    }

    @Test
    void repeatedDecisionKeyReturnsFirstDurableResultWithoutReapply() throws Exception {
        Path project = prepareProject("repeat-key");
        ProposalResult created = createProposal("repeat-proposal", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT), null);

        DecisionResult first = proposalService.decide(7, 12, created.proposalId(),
                DecisionRequest.approve(1L, "repeat-dec"));
        DecisionResult second = proposalService.decide(7, 12, created.proposalId(),
                DecisionRequest.approve(1L, "repeat-dec"));

        assertThat(first.status()).isEqualTo("applied");
        assertThat(first.appliedRevision()).isEqualTo(2L);
        assertThat(second.status()).isEqualTo("applied");
        assertThat(second.appliedRevision()).isEqualTo(2L);
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(2L);
        assertThat(auditEvents().stream().filter("applied"::equals).count()).isEqualTo(1);
        assertThat(Files.readString(configDir(project).resolve("agents/main.json")))
                .isEqualTo(AGENT_MAIN_CANDIDATE);
    }

    @Test
    void secondProposalWithSameDecisionKeyReturnsFirstDurableResult() throws Exception {
        prepareProject("shared-decision-key");
        ProposalResult first = createProposal("shared-p1", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT), null);
        ProposalResult second = createProposal("shared-p2", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT), null);

        DecisionResult d1 = proposalService.decide(7, 12, first.proposalId(),
                DecisionRequest.approve(1L, "shared-dec"));
        DecisionResult d2 = proposalService.decide(7, 12, second.proposalId(),
                DecisionRequest.approve(1L, "shared-dec"));

        assertThat(d1.status()).isEqualTo("applied");
        assertThat(d2.proposalId()).isEqualTo(first.proposalId());
        assertThat(d2.status()).isEqualTo("applied");
        assertThat(d2.appliedRevision()).isEqualTo(2L);
        assertThat(proposalMapper.selectById(second.proposalId()).getStatus()).isEqualTo("pending");
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(2L);
    }

    @Test
    void applyFailureMarksProposalFailedWithoutFalseAppliedHead() throws Exception {
        Path project = prepareProject("apply-failure");
        ProposalResult created = createProposal("fail-key", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT), null);
        String stageId = AgentProjectConfigApplyRecoveryService.stageId(12, "fail-key");
        Files.writeString(project.resolve(".labex-agent/.config-staging").resolve(stageId)
                .resolve("agent.json"), "not json");

        ProposalException error = catchThrowableOfType(() -> proposalService.decide(7, 12,
                created.proposalId(), DecisionRequest.approve(1L, "fail-dec")), ProposalException.class);

        assertThat(error.httpStatus()).isEqualTo(400);
        assertThat(error.reasonCode()).isEqualTo("APPLY_FAILED");
        assertThat(proposalMapper.selectById(created.proposalId()).getStatus()).isEqualTo("failed");
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(1L);
        assertThat(Files.readString(configDir(project).resolve("agents/main.json"))).isEqualTo(AGENT_MAIN);
        assertThat(auditEvents()).contains("approved", "failed");
    }

    @Test
    void materializeExternalChangeCreatesProposalLinksObservationAndApplyResolvesIt() throws Exception {
        Path project = prepareProject("materialize");
        Files.writeString(configDir(project).resolve("agents/main.json"), AGENT_MAIN_CANDIDATE);
        ProjectConfigView observed = revisionService.load(7, 12);
        assertThat(observed.externalChangePending()).isTrue();
        Long externalChangeId = observed.externalChange().id();

        ProposalResult materialized = proposalService.materializeExternalChange(7, 12, externalChangeId,
                "materialize-key", "adopt external edit");

        assertThat(materialized.status()).isEqualTo("pending");
        assertThat(materialized.baseRevision()).isEqualTo(1L);
        AgentProjectConfigProposal row = proposalMapper.selectById(materialized.proposalId());
        assertThat(row.getSource()).isEqualTo("external_change");
        assertThat(row.getPatchReference()).isEqualTo("tree:" + observed.externalChange().observedTreeDigest());
        assertThat(row.getChangedPathSummary()).contains("agents/main.json");
        AgentProjectConfigExternalChange linked = externalChangeMapper.selectById(externalChangeId);
        assertThat(linked.getProposalId()).isEqualTo(materialized.proposalId());
        assertThat(linked.getStatus()).isEqualTo("external_change_pending");
        assertThat(auditEvents()).contains("external-change-detected");

        DecisionResult decision = proposalService.decide(7, 12, materialized.proposalId(),
                DecisionRequest.approve(1L, "materialize-dec"));

        assertThat(decision.status()).isEqualTo("applied");
        assertThat(decision.appliedRevision()).isEqualTo(2L);
        AgentProjectConfigExternalChange applied = externalChangeMapper.selectById(externalChangeId);
        assertThat(applied.getStatus()).isEqualTo("applied");
        assertThat(Files.readString(configDir(project).resolve("agents/main.json")))
                .isEqualTo(AGENT_MAIN_CANDIDATE);
        ProjectConfigView view = revisionService.load(7, 12);
        assertThat(view.revision()).isEqualTo(2L);
        assertThat(view.externalChangePending()).isFalse();
    }

    @Test
    void listAndDetailReturnRedactedProjectionWithoutCandidateContent() throws Exception {
        prepareProject("redacted");
        ProposalResult created = createProposal("redacted-key", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT), null);

        List<ProposalSummary> list = proposalService.listProposals(7, 12);
        assertThat(list).hasSize(1);
        ProposalSummary summary = list.get(0);
        assertThat(summary.proposalId()).isEqualTo(created.proposalId());
        assertThat(summary.status()).isEqualTo("pending");
        assertThat(summary.candidateConfigDigest()).matches("[0-9a-f]{64}");
        assertThat(summary.changedPathSummary()).contains("agents/main.json");

        ProposalDetail detail = proposalService.getProposal(7, 12, created.proposalId());
        assertThat(detail.status()).isEqualTo("pending");
        assertThat(detail.patchReference()).startsWith("tree:");
        assertThat(detail.auditEventIds()).isNotEmpty();
        assertThat(detail.originTaskId()).isNull();
        String rendered = String.valueOf(list) + detail;
        assertThat(rendered).doesNotContain("Be helpful", "Be concise", "instructions");
    }

    @Test
    void changedFileTreeIsReflectedInChangedPathSummary() throws Exception {
        prepareProject("changed-tree");
        Map<String, String> multi = candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT);
        multi.put("environment.json", "{\"variables\":[\"NODE_ENV\",\"NODE_OPTIONS\"],\"installPolicy\":\"project-only\"}");

        ProposalResult result = createProposal("multi-key", multi, null);

        AgentProjectConfigProposal row = proposalMapper.selectById(result.proposalId());
        assertThat(row.getChangedPathSummary()).contains("agents/main.json", "environment.json");
    }

    @Test
    void invalidDecisionValueIsRejectedWith400() throws Exception {
        prepareProject("bad-decision");
        ProposalResult created = createProposal("bad-dec-key", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT), null);

        ProposalException error = catchThrowableOfType(() -> proposalService.decide(7, 12,
                created.proposalId(), DecisionRequest.of("maybe", 1L, "bad-dec")), ProposalException.class);

        assertThat(error.httpStatus()).isEqualTo(400);
        assertThat(error.reasonCode()).isEqualTo("INVALID_DECISION");
        assertThat(proposalMapper.selectById(created.proposalId()).getStatus()).isEqualTo("pending");
    }

    @Test
    void overLongDecisionKeyIsRejectedWith400WithoutStateChange() throws Exception {
        prepareProject("long-dec-key");
        ProposalResult created = createProposal("long-dec-proposal", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT),
                null);

        ProposalException error = catchThrowableOfType(() -> proposalService.decide(7, 12,
                created.proposalId(), DecisionRequest.approve(1L, "k".repeat(129))), ProposalException.class);

        assertThat(error.httpStatus()).isEqualTo(400);
        assertThat(error.reasonCode()).isEqualTo("INVALID_KEY");
        assertThat(proposalMapper.selectById(created.proposalId()).getStatus()).isEqualTo("pending");
        assertThat(proposalMapper.selectById(created.proposalId()).getDecisionIdempotencyKey()).isNull();
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(1L);
        assertThat(auditMapper.selectCount(new QueryWrapper<AgentProjectConfigAuditEvent>()
                .eq("project_id", 12).eq("event_type", "approved"))).isZero();
    }

    @Test
    void nonDuplicateFailureInsideDecisionCasPropagatesAndLeavesProposalPending() throws Exception {
        prepareProject("cas-prop");
        ProposalResult created = createProposal("cas-prop-key", candidate(AGENT_MAIN_CANDIDATE, ENVIRONMENT), null);
        AgentProjectConfigProposalMapper spied = spy(proposalMapper);
        doThrow(new IllegalStateException("database exploded"))
                .when(spied).update(isNull(), org.mockito.ArgumentMatchers.<UpdateWrapper<AgentProjectConfigProposal>>any());
        AgentProjectConfigProposalService failing = new AgentProjectConfigProposalService(studentProjectService,
                spied, revisionMapper, fileWriter, recoveryService, externalChangeService);

        IllegalStateException boom = catchThrowableOfType(() -> failing.decide(7, 12, created.proposalId(),
                DecisionRequest.approve(1L, "cas-prop-dec")), IllegalStateException.class);

        assertThat(boom).isNotNull();
        assertThat(boom.getMessage()).isEqualTo("database exploded");
        assertThat(proposalMapper.selectById(created.proposalId()).getStatus()).isEqualTo("pending");
        assertThat(proposalMapper.selectById(created.proposalId()).getDecisionIdempotencyKey()).isNull();
    }

    private ProposalResult createProposal(String key, Map<String, String> candidate, Long expectedRevision) {
        return proposalService.createProposal(7, 12, CreateProposalRequest.of(expectedRevision, candidate,
                "test proposal", key));
    }

    private ProposalResult createProposalOn(int studentId, int projectId, String key,
                                            Map<String, String> candidate) {
        return proposalService.createProposal(studentId, projectId,
                CreateProposalRequest.of(1L, candidate, "test proposal", key));
    }

    private Map<String, String> candidate(String agentContent, String environmentContent) {
        return candidate(MANIFEST, agentContent, environmentContent);
    }

    private Map<String, String> candidate(String manifest, String agentContent, String environmentContent) {
        Map<String, String> candidate = new LinkedHashMap<>();
        candidate.put("agent.json", manifest);
        candidate.put("agents/main.json", agentContent);
        candidate.put("tools/build.json", TOOL_BUILD);
        candidate.put("mcp/typescript.json", MCP_TYPESCRIPT);
        candidate.put("skills/backend.md", SKILL_BACKEND);
        candidate.put("environment.json", environmentContent);
        return candidate;
    }

    private List<String> auditEvents() {
        return auditMapper.selectList(new QueryWrapper<AgentProjectConfigAuditEvent>()
                        .eq("project_id", 12).orderByAsc("event_id"))
                .stream().map(AgentProjectConfigAuditEvent::getEventType).toList();
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

    private void registerOwnedProject(int studentId, int projectId, Path workspaceRoot) {
        StudentProject project = new StudentProject();
        project.setProjectId(projectId);
        project.setStudentId(studentId);
        project.setWorkspacePath(workspaceRoot.toString());
        when(studentProjectService.getOwnedProject(studentId, projectId)).thenReturn(project);
    }

    private SqlSessionFactory factory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("proposal-service", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentProjectConfigRevisionMapper.class);
        configuration.addMapper(AgentProjectConfigProposalMapper.class);
        configuration.addMapper(AgentProjectConfigAuditEventMapper.class);
        configuration.addMapper(AgentProjectConfigExternalChangeMapper.class);
        return new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:proposal_service_" + UUID.randomUUID().toString().replace("-", "")
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
