package com.labex.labexagent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class ProcessHostIdentityTest {

    @Test
    void springConstructsTheConfiguredHostIdentityWhenMultipleConstructorsExist() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "process-host-test", Map.of("labex-agent.process-host-id", "acceptance-host-71")));
            context.register(ProcessHostIdentity.class);
            context.refresh();

            ProcessHostIdentity identity = context.getBean(ProcessHostIdentity.class);
            assertThat(identity.hostId()).startsWith("host-").hasSize(29);
        }
    }
}
