package com.labex.labexagent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessCommandResolverTest {

    @Test
    void resolvesWindowsScriptCommandsForDirectProcessBuilderLaunches() {
        assertThat(ProcessCommandResolver.resolve(List.of("npm", "test")).get(0)).isEqualTo("npm.cmd");
        assertThat(ProcessCommandResolver.resolve(List.of("mvn", "test")).get(0)).isEqualTo("mvn.cmd");
    }

    @Test
    void leavesExplicitPathsAndOrdinaryExecutablesUntouched() {
        assertThat(ProcessCommandResolver.resolve(List.of("C:/tools/npm.cmd", "test")).get(0))
                .isEqualTo("C:/tools/npm.cmd");
        assertThat(ProcessCommandResolver.resolve(List.of("git", "status")).get(0)).isEqualTo("git");
    }
}
