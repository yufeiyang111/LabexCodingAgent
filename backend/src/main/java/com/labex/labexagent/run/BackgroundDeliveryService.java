package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.labex.entity.AgentVerification;
import com.labex.mapper.AgentVerificationMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class BackgroundDeliveryService {
    private final BackgroundDeliveryPolicy policy;
    private final AgentRunArtifactService artifacts;
    private final AgentVerificationMapper verificationMapper;
    public BackgroundDeliveryService(BackgroundDeliveryPolicy policy, AgentRunArtifactService artifacts, AgentVerificationMapper verificationMapper) {
        this.policy = policy; this.artifacts = artifacts; this.verificationMapper = verificationMapper;
    }

    public String commit(Long taskId, Path worktree, String branch, String message, boolean explicitApproval) throws IOException, InterruptedException {
        boolean agentBranch = branch != null && branch.matches("agent/run-[1-9][0-9]*");
        var decision = policy.evaluate(new BackgroundDeliveryPolicy.Request(BackgroundDeliveryPolicy.Action.COMMIT, agentBranch, explicitApproval, hasSuccessfulVerification(taskId)));
        if (!decision.allowed()) throw new IllegalStateException(decision.reason());
        String output = run(worktree, List.of("add", "-A"));
        output += run(worktree, List.of("commit", "--allow-empty", "-m", safeMessage(message)));
        String commit = run(worktree, List.of("rev-parse", "HEAD")).trim();
        artifacts.record(taskId, "git_commit", null, output + "\ncommit=" + commit);
        return commit;
    }


    private boolean hasSuccessfulVerification(Long taskId) {
        if (taskId == null || taskId <= 0) return false;
        return verificationMapper.selectCount(new LambdaQueryWrapper<AgentVerification>()
                .eq(AgentVerification::getTaskId, taskId)
                .eq(AgentVerification::getStatus, "passed")) > 0;
    }

    private String safeMessage(String message) { String value = message == null ? "Labex Agent background run" : message.trim(); return value.isBlank() ? "Labex Agent background run" : value.substring(0, Math.min(value.length(), 240)); }
    private String run(Path worktree, List<String> args) throws IOException, InterruptedException {
        List<String> cmd = new java.util.ArrayList<>(); cmd.add("git"); cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).directory(worktree.toFile()).redirectErrorStream(true).start(); String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("git command timed out"); }
        if (p.exitValue() != 0) throw new IOException("git command failed: " + output.trim()); return output;
    }
}
