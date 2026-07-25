package com.labex.labexagent.websearch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

final class McpWebSearchResponseParser {
    String extractContent(String body) throws WebSearchException {
        JsonObject response = parseResponse(body);
        if (response.has("error")) {
            throw new WebSearchException("Web-search provider returned an error", false);
        }
        JsonElement resultElement = response.get("result");
        if (resultElement == null || !resultElement.isJsonObject()) {
            throw new WebSearchException("Web-search provider returned an invalid response", true);
        }
        JsonElement contentElement = resultElement.getAsJsonObject().get("content");
        if (contentElement == null || !contentElement.isJsonArray()) {
            throw new WebSearchException("Web-search provider returned no search content", true);
        }
        List<String> textParts = new ArrayList<>();
        for (JsonElement item : contentElement.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject content = item.getAsJsonObject();
            if (content.has("text") && content.get("text").isJsonPrimitive()) {
                String text = content.get("text").getAsString().trim();
                if (!text.isBlank()) {
                    textParts.add(text);
                }
            }
        }
        if (textParts.isEmpty()) {
            throw new WebSearchException("Web-search provider returned no search content", true);
        }
        return String.join("\n\n", textParts);
    }

    private JsonObject parseResponse(String body) throws WebSearchException {
        try {
            JsonElement element = JsonParser.parseString(body);
            if (element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        } catch (Exception ignored) {
            // MCP endpoints may return server-sent events instead of a bare JSON document.
        }
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String data = trimmed.substring("data:".length()).trim();
            if (data.isBlank() || "[DONE]".equals(data)) {
                continue;
            }
            try {
                JsonElement element = JsonParser.parseString(data);
                if (element.isJsonObject()) {
                    return element.getAsJsonObject();
                }
            } catch (Exception ignored) {
                // Continue looking for a complete JSON event.
            }
        }
        throw new WebSearchException("Web-search provider returned an invalid response", true);
    }
}
