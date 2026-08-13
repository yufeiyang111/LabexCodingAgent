package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.labexagent.tool.ToolResult;
import org.junit.jupiter.api.Test;

class EnvironmentBlockerClassifierTest {

    @Test
    void classifiesMavenDnsFailureAsRecoverableEnvironmentBlocker() {
        var blocker = EnvironmentBlockerClassifier.classify("run_tests",
                ToolResult.failed("exit=1\nCould not transfer artifact: Unknown host repo.maven.apache.org"));

        assertThat(blocker).isPresent();
        assertThat(blocker.orElseThrow().code()).isEqualTo("DNS_UNAVAILABLE");
    }

    @Test
    void classifiesNonResolvableParentPomAsDependencyEnvironmentFailure() {
        var blocker = EnvironmentBlockerClassifier.classify("run_tests",
                ToolResult.failed("Non-resolvable parent POM: Could not transfer artifact org.springframework.boot:spring-boot-starter-parent"));

        assertThat(blocker).isPresent();
        assertThat(blocker.orElseThrow().code()).isEqualTo("DEPENDENCY_RESOLUTION_FAILED");
    }

    @Test
    void onlyRawNetworkAndDependencyFailuresAreEligibleForOneTimeNetworkRetry() {
        assertThat(EnvironmentBlockerClassifier.isNetworkRetryCandidate("shell",
                ToolResult.failed("Non-resolvable parent POM: Could not transfer artifact from central"))).isTrue();
        assertThat(EnvironmentBlockerClassifier.isNetworkRetryCandidate("run_tests",
                ToolResult.failed("Unknown host repo.maven.apache.org"))).isTrue();
        assertThat(EnvironmentBlockerClassifier.isNetworkRetryCandidate("shell",
                ToolResult.failed("failure_code=ENVIRONMENT_BLOCKED\nretryable=false"))).isFalse();
        assertThat(EnvironmentBlockerClassifier.isNetworkRetryCandidate("read_file",
                ToolResult.failed("Unknown host repo.maven.apache.org"))).isFalse();
    }

    @Test
    void classifiesStructuredGuardResultAsEnvironmentBlocker() {
        var blocker = EnvironmentBlockerClassifier.classify("run_tests",
                ToolResult.failed("failure_code=ENVIRONMENT_BLOCKED\nretryable=false\nblocked by prior Maven DNS failure"));

        assertThat(blocker).isPresent();
        assertThat(blocker.orElseThrow().code()).isEqualTo("ENVIRONMENT_BLOCKED");
    }

    @Test
    void doesNotBlockSuccessfulOrUnrelatedToolResults() {
        assertThat(EnvironmentBlockerClassifier.classify("run_tests", ToolResult.ok("exit=0"))).isEmpty();
        assertThat(EnvironmentBlockerClassifier.classify("web_search", ToolResult.failed("Unknown host api.example"))).isEmpty();
    }
}
