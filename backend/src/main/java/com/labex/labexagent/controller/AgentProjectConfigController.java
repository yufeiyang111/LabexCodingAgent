package com.labex.labexagent.controller;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.common.Result;
import com.labex.labexagent.projectconfig.AgentProjectConfigOwnership.ProjectConfigNotFoundException;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.CreateProposalRequest;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.DecisionRequest;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.DecisionResult;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.ProposalDetail;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.ProposalException;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.ProposalResult;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.ProposalSummary;
import com.labex.labexagent.projectconfig.AgentProjectConfigRevisionService;
import com.labex.labexagent.projectconfig.AgentProjectConfigRevisionService.EnvironmentStatus;
import com.labex.labexagent.projectconfig.AgentProjectConfigRevisionService.ExternalChangeInfo;
import com.labex.labexagent.projectconfig.AgentProjectConfigRevisionService.ProjectConfigView;
import com.labex.labexagent.secret.ProjectSecretBindingService;
import com.labex.labexagent.secret.ProjectSecretBindingService.SecretInputRequest;
import com.labex.labexagent.secret.ProjectSecretBindingService.SecretInputResult;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project configuration control plane surface. The authenticated owner and the route project
 * ID are the only authority inputs; the revision service runs
 * {@code StudentProjectService.getOwnedProject} before any read and the proposal service runs
 * the same gate before any create/decision. Stale conditional reads, stale proposal bases and
 * decision CAS conflicts return a real HTTP 409, expired proposals a real HTTP 410, foreign
 * projects/proposals a generic HTTP 404, and invalid candidates a structured HTTP 400. No
 * secret value and no candidate file content is ever echoed.
 */
@RestController
@RequestMapping("/student/projects/{projectId}/agent")
public class AgentProjectConfigController {

    private final AgentProjectConfigRevisionService revisionService;
    private final AgentProjectConfigProposalService proposalService;
    private final ProjectSecretBindingService secretBindingService;

    @Autowired
    public AgentProjectConfigController(AgentProjectConfigRevisionService revisionService,
                                        AgentProjectConfigProposalService proposalService,
                                        ProjectSecretBindingService secretBindingService) {
        this.revisionService = revisionService;
        this.proposalService = proposalService;
        this.secretBindingService = secretBindingService;
    }

    /** 兼容构造：不携带 secret binding 服务时，secret-input 端点返回 HTTP 500。 */
    public AgentProjectConfigController(AgentProjectConfigRevisionService revisionService,
                                        AgentProjectConfigProposalService proposalService) {
        this(revisionService, proposalService, null);
    }

    /**
     * Returns the redacted document, accepted revision and digest, validation status,
     * trust/runtime status, enabled resource references, environment status and any blocking
     * external-change observation.
     *
     * @param expectedRevision optional conditional-read guard; a mismatch returns HTTP 409
     */
    @GetMapping("/config")
    public Result<Map<String, Object>> config(@PathVariable Integer projectId,
                                              @RequestParam(value = "expectedRevision", required = false)
                                              Long expectedRevision,
                                              Authentication auth,
                                              HttpServletResponse response) {
        Integer studentId = Integer.parseInt(auth.getName());
        try {
            ProjectConfigView view = revisionService.load(studentId, projectId);
            if (expectedRevision != null && !expectedRevision.equals(view.revision())) {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                return Result.error(409, "Stale revision; reload the project configuration");
            }
            return Result.success(toResponse(view));
        } catch (ProjectConfigNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return Result.error(e.getMessage());
        }
    }

    /**
     * Creates a pending proposal from a complete candidate document, the expected revision,
     * a reason and an idempotency key. The candidate is validated, digested and durably
     * staged before the proposal row exists.
     */
    @PostMapping("/config/proposals")
    public Result<Map<String, Object>> createProposal(@PathVariable Integer projectId,
                                                      @RequestBody String body,
                                                      Authentication auth,
                                                      HttpServletResponse response) {
        Integer studentId = Integer.parseInt(auth.getName());
        try {
            ProposalResult result = proposalService.createProposal(studentId, projectId,
                    parseCreateRequest(body));
            return Result.success(toCreateResponse(result));
        } catch (ProposalException e) {
            response.setStatus(e.httpStatus());
            return Result.error(e.httpStatus(), e.getMessage());
        } catch (ProjectConfigNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return Result.error(e.getMessage());
        }
    }

    /** Lists redacted proposal summaries for the owner's project. */
    @GetMapping("/config/proposals")
    public Result<List<Map<String, Object>>> listProposals(@PathVariable Integer projectId,
                                                           Authentication auth,
                                                           HttpServletResponse response) {
        Integer studentId = Integer.parseInt(auth.getName());
        try {
            return Result.success(proposalService.listProposals(studentId, projectId).stream()
                    .map(this::toSummaryResponse).toList());
        } catch (ProposalException e) {
            response.setStatus(e.httpStatus());
            return Result.error(e.httpStatus(), e.getMessage());
        } catch (ProjectConfigNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return Result.error(e.getMessage());
        }
    }

    /** Returns the redacted proposal detail, digest, expiry and audit references. */
    @GetMapping("/config/proposals/{proposalId}")
    public Result<Map<String, Object>> proposalDetail(@PathVariable Integer projectId,
                                                      @PathVariable Long proposalId,
                                                      Authentication auth,
                                                      HttpServletResponse response) {
        Integer studentId = Integer.parseInt(auth.getName());
        try {
            return Result.success(toDetailResponse(proposalService.getProposal(studentId, projectId, proposalId)));
        } catch (ProposalException e) {
            response.setStatus(e.httpStatus());
            return Result.error(e.httpStatus(), e.getMessage());
        } catch (ProjectConfigNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return Result.error(e.getMessage());
        }
    }

    /**
     * Performs the owner-only approve/reject CAS on the exact proposal. Repeated identical
     * decision keys return the first durable result; stale revisions return HTTP 409;
     * expired proposals return HTTP 410.
     */
    @PostMapping("/config/proposals/{proposalId}/decision")
    public Result<Map<String, Object>> decide(@PathVariable Integer projectId,
                                              @PathVariable Long proposalId,
                                              @RequestBody String body,
                                              Authentication auth,
                                              HttpServletResponse response) {
        Integer studentId = Integer.parseInt(auth.getName());
        try {
            DecisionResult result = proposalService.decide(studentId, projectId, proposalId,
                    parseDecisionRequest(body));
            return Result.success(toDecisionResponse(result));
        } catch (ProposalException e) {
            response.setStatus(e.httpStatus());
            return Result.error(e.httpStatus(), e.getMessage());
        } catch (ProjectConfigNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return Result.error(e.getMessage());
        }
    }

    /**
     * Writes one one-time secret for the exact proposal field. The value is encrypted
     * immediately; the response contains only the alias and the configured flag, and the
     * plaintext is never serialized into an interaction response, transcript, event, log
     * or error path.
     */
    @PostMapping("/config/proposals/{proposalId}/secret-input")
    public Result<Map<String, Object>> secretInput(@PathVariable Integer projectId,
                                                   @PathVariable Long proposalId,
                                                   @RequestBody String body,
                                                   Authentication auth,
                                                   HttpServletResponse response) {
        Integer studentId = Integer.parseInt(auth.getName());
        try {
            if (secretBindingService == null) {
                throw new ProposalException(500, "SECRET_INPUT_UNAVAILABLE",
                        "Secret input service is unavailable");
            }
            SecretInputResult result = secretBindingService.inputSecret(studentId, projectId,
                    proposalId, parseSecretInputRequest(body));
            return Result.success(toSecretInputResponse(result));
        } catch (ProposalException e) {
            response.setStatus(e.httpStatus());
            return Result.error(e.httpStatus(), e.getMessage());
        } catch (ProjectConfigNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return Result.error(e.getMessage());
        }
    }

    private SecretInputRequest parseSecretInputRequest(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            Integer ttlSeconds = null;
            if (json.has("ttlSeconds") && json.get("ttlSeconds").isJsonPrimitive()
                    && json.get("ttlSeconds").getAsJsonPrimitive().isNumber()) {
                ttlSeconds = json.get("ttlSeconds").getAsInt();
            }
            return new SecretInputRequest(stringOrNull(json, "fieldId"),
                    stringOrNull(json, "value"), stringOrNull(json, "idempotencyKey"),
                    ttlSeconds);
        } catch (RuntimeException malformed) {
            throw new ProposalException(400, "INVALID_REQUEST", "Malformed secret input request");
        }
    }

    private Map<String, Object> toSecretInputResponse(SecretInputResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("alias", result.alias());
        response.put("configured", result.configured());
        return response;
    }

    private CreateProposalRequest parseCreateRequest(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            Map<String, String> candidate = new LinkedHashMap<>();
            if (json.has("candidate") && json.get("candidate").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("candidate").entrySet()) {
                    candidate.put(entry.getKey(), stringify(entry.getValue()));
                }
            }
            return new CreateProposalRequest(
                    longOrNull(json, "expectedRevision"), candidate,
                    stringOrNull(json, "patch"), stringOrNull(json, "reason"),
                    stringOrNull(json, "idempotencyKey"), stringOrNull(json, "source"),
                    longOrNull(json, "externalChangeId"), longOrNull(json, "originTaskId"),
                    longOrNull(json, "originExecutionEpoch"), stringOrNull(json, "originToolCallId"));
        } catch (RuntimeException malformed) {
            throw new ProposalException(400, "INVALID_REQUEST", "Malformed proposal request");
        }
    }

    private DecisionRequest parseDecisionRequest(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            return new DecisionRequest(stringOrNull(json, "decision"),
                    longOrNull(json, "expectedRevision"),
                    stringOrNull(json, "decisionIdempotencyKey"));
        } catch (RuntimeException malformed) {
            throw new ProposalException(400, "INVALID_REQUEST", "Malformed decision request");
        }
    }

    private String stringify(JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }
        return element.toString();
    }

    private String stringOrNull(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()) {
            return null;
        }
        return json.get(key).getAsString();
    }

    private Long longOrNull(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.get(key).getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return json.get(key).getAsLong();
    }

    private Map<String, Object> toCreateResponse(ProposalResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("proposalId", result.proposalId());
        response.put("status", result.status());
        response.put("baseRevision", result.baseRevision());
        response.put("candidateConfigDigest", result.candidateConfigDigest());
        response.put("changedPathSummary", result.changedPathSummary());
        response.put("expiresTime", result.expiresTime());
        return response;
    }

    private Map<String, Object> toSummaryResponse(ProposalSummary summary) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("proposalId", summary.proposalId());
        response.put("status", summary.status());
        response.put("baseRevision", summary.baseRevision());
        response.put("candidateConfigDigest", summary.candidateConfigDigest());
        response.put("changedPathSummary", summary.changedPathSummary());
        response.put("reason", summary.reason());
        response.put("source", summary.source());
        response.put("creator", summary.creator());
        response.put("expiresTime", summary.expiresTime());
        response.put("decisionTime", summary.decisionTime());
        response.put("appliedRevision", summary.appliedRevision());
        return response;
    }

    private Map<String, Object> toDetailResponse(ProposalDetail detail) {
        Map<String, Object> response = toSummaryResponse(new ProposalSummary(
                detail.proposalId(), detail.status(), detail.baseRevision(),
                detail.candidateConfigDigest(), detail.changedPathSummary(), detail.reason(),
                detail.source(), detail.creator(), detail.expiresTime(), detail.decisionTime(),
                detail.appliedRevision()));
        response.put("patchReference", detail.patchReference());
        response.put("originTaskId", detail.originTaskId());
        response.put("originExecutionEpoch", detail.originExecutionEpoch());
        response.put("originToolCallId", detail.originToolCallId());
        response.put("createTime", detail.createTime());
        response.put("auditEventIds", detail.auditEventIds());
        return response;
    }

    private Map<String, Object> toDecisionResponse(DecisionResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("proposalId", result.proposalId());
        response.put("status", result.status());
        response.put("appliedRevision", result.appliedRevision());
        response.put("decisionTime", result.decisionTime());
        if (result.interactionId() != null) {
            response.put("interactionId", result.interactionId());
            response.put("resumeEpoch", result.resumeEpoch());
        }
        return response;
    }

    private Map<String, Object> toResponse(ProjectConfigView view) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", view.valid());
        response.put("validationStatus", view.validationStatus());
        response.put("revision", view.revision());
        response.put("configDigest", view.configDigest());
        response.put("treeDigest", view.treeDigest());
        response.put("runtimeProfile", view.runtimeProfile());
        response.put("trustStatus", view.trustStatus());
        response.put("externalChangePending", view.externalChangePending());
        response.put("externalChange", externalChange(view.externalChange()));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("manifest", view.redactedManifest() == null ? Map.of() : view.redactedManifest());
        document.put("children", view.redactedChildren());
        response.put("document", document);
        response.put("enabledResources", view.enabledResources());
        EnvironmentStatus environment = view.environmentStatus();
        Map<String, Object> environmentStatus = new LinkedHashMap<>();
        environmentStatus.put("configured", environment.configured());
        environmentStatus.put("variables",
                environment.variables() == null ? List.of() : environment.variables());
        environmentStatus.put("installPolicy", environment.installPolicy());
        response.put("environmentStatus", environmentStatus);
        response.put("errors", view.errors());
        return response;
    }

    private Map<String, Object> externalChange(ExternalChangeInfo change) {
        if (change == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", change.id());
        result.put("baseRevision", change.baseRevision());
        result.put("observedTreeDigest", change.observedTreeDigest());
        result.put("changedPaths", change.changedPaths());
        result.put("detectedAt", change.detectedAt());
        return result;
    }
}
