package com.labex.labexagent.runtime;

import com.google.gson.Gson;
import com.labex.labexagent.dto.AgentEvent;
import java.io.IOException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class AgentSsePublisher {
    private static final Gson GSON = new Gson();
    private final SseEmitter emitter;

    public AgentSsePublisher(SseEmitter emitter) {
        this.emitter = emitter;
    }

    public void send(String type, Object data) throws IOException {
        this.emitter.send(SseEmitter.event().name(type).data(GSON.toJson(new AgentEvent(type, data))));
    }
}

