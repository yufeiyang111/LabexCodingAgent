package com.labex.labexagent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.labexagent.websearch.WebSearchProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AcceptanceWebSearchCacheControllerTest {

    @Test
    void isAcceptanceOnlyAndReturnsAggregateScopedEvidence() {
        Profile profile = AcceptanceWebSearchCacheController.class.getAnnotation(Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("acceptance & !prod");

        WebSearchProperties properties = new WebSearchProperties();
        properties.setCacheRedisKeyPrefix("labex:web-search");
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        String prefix = "labex:web-search:v1:7:3:";
        when(redis.keys(prefix + "*")).thenReturn(Set.of(
                prefix + "exa:abc",
                prefix + "tavily:def"));
        AcceptanceWebSearchCacheController controller =
                new AcceptanceWebSearchCacheController(redis, properties);

        var result = controller.observe(3,
                new UsernamePasswordAuthenticationToken("7", "ignored"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertThat(data.get("redisEnabled")).isEqualTo(true);
        assertThat(data.get("matchingKeys")).isEqualTo(2);
        assertThat(data.get("queryLeaked")).isEqualTo(false);
        assertThat((List<String>) data.get("providers")).containsExactly("exa", "tavily");
    }
}