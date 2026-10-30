package com.labex.labexagent.websearch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

class WebSearchPropertiesTest {

    @Test
    void bindsAllWebSearchCacheControlsFromApplicationYaml() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("application",
                new FileSystemResource(Path.of("src/main/resources/application.yml")))) {
            environment.getPropertySources().addFirst(source);
        }

        WebSearchProperties properties = Binder.get(environment)
                .bind("labex-agent.web-search", WebSearchProperties.class)
                .get();

        assertThat(properties.isCacheEnabled()).isTrue();
        assertThat(properties.getCacheTtlSeconds()).isEqualTo(600);
        assertThat(properties.getCacheMaxEntries()).isEqualTo(100);
        assertThat(properties.isCacheRedisEnabled()).isTrue();
        assertThat(properties.getCacheMaxValueBytes()).isEqualTo(131072);
        assertThat(properties.getCacheRedisKeyPrefix()).isEqualTo("labex:web-search");
    }
}
