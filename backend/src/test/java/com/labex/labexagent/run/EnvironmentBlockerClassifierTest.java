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
    void doesNotBlockSuccessfulOrUnrelatedToolResults() {
        assertThat(EnvironmentBlockerClassifier.classify("run_tests", ToolResult.ok("exit=0"))).isEmpty();
        assertThat(EnvironmentBlockerClassifier.classify("web_search", ToolResult.failed("Unknown host api.example"))).isEmpty();
    }
}
