
package com.labex.rag.llm;

import com.labex.rag.config.RagConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.*;

@Slf4j
@Service
public class OllamaChat implements LLMChat {
    private final RagConfig ragConfig;
    private final RestTemplate restTemplate;
    public OllamaChat(RagConfig ragConfig) {
        this.ragConfig = ragConfig;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10000);
        f.setReadTimeout(300000);
        this.restTemplate = new RestTemplate(f);
    }

    @Override
    public String chat(String prompt, String context, String question) {
        try {
            String url = ragConfig.getOllamaBaseUrl().replaceAll("/$", "") + "/v1/chat/completions";
            List<Map<String, Object>> msgs = new ArrayList<>();
            msgs.add(Map.of("role", "system", "content", prompt + "\n\nContext:\n" + context));
            msgs.add(Map.of("role", "user", "content", question));
            Map<String, Object> body = new HashMap<>();
            body.put("model", ragConfig.getOllamaModel());
            body.put("messages", msgs);
            body.put("max_tokens", 4096);
            body.put("stream", false);
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> r = restTemplate.postForEntity(url, new HttpEntity<>(body, h), Map.class);
            if (r.getStatusCode().value() == 200 && r.getBody() != null) {
                Map<String, Object> rb = r.getBody();
                if (rb.containsKey("choices")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) rb.get("choices");
                    if (!choices.isEmpty()) {
                        Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
                        if (msg != null && msg.get("content") != null) return msg.get("content").toString();
                    }
                }
            }
            return "Failed";
        } catch (Exception e) { log.error("Ollama: {}", e.getMessage()); return "LLM error: " + e.getMessage(); }
    }

    @Override
    public String chatWithHistory(String prompt, String context, String question, List<Map<String, String>> history) {
        try {
            String url = ragConfig.getOllamaBaseUrl().replaceAll("/$", "") + "/v1/chat/completions";
            List<Map<String, Object>> msgs = new ArrayList<>();
            msgs.add(Map.of("role", "system", "content", prompt + "\n\nContext:\n" + context));
            if (history != null) {
                for (Map<String, String> m : history) {
                    if (m.get("role") != null && m.get("content") != null) msgs.add(Map.of("role", m.get("role"), "content", m.get("content")));
                }
            }
            msgs.add(Map.of("role", "user", "content", question));
            Map<String, Object> body = new HashMap<>();
            body.put("model", ragConfig.getOllamaModel());
            body.put("messages", msgs);
            body.put("max_tokens", 4096);
            body.put("stream", false);
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> r = restTemplate.postForEntity(url, new HttpEntity<>(body, h), Map.class);
            if (r.getStatusCode().value() == 200 && r.getBody() != null) {
                Map<String, Object> rb = r.getBody();
                if (rb.containsKey("choices")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) rb.get("choices");
                    if (!choices.isEmpty()) {
                        Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
                        if (msg != null && msg.get("content") != null) return msg.get("content").toString();
                    }
                }
            }
            return "Failed";
        } catch (Exception e) { log.error("Ollama: {}", e.getMessage()); return "LLM error: " + e.getMessage(); }
    }
}
