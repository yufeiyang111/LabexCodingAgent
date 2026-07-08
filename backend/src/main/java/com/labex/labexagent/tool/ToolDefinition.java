package com.labex.labexagent.tool;

import java.util.Map;

public class ToolDefinition {
    private String name;
    private String description;
    private Map<String, Object> inputSchema;

    public ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getName() {
        return this.name;
    }

    public String getDescription() {
        return this.description;
    }

    public Map<String, Object> getInputSchema() {
        return this.inputSchema;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setInputSchema(Map<String, Object> inputSchema) {
        this.inputSchema = inputSchema;
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof ToolDefinition)) {
            return false;
        }
        ToolDefinition other = (ToolDefinition)o;
        if (!other.canEqual(this)) {
            return false;
        }
        String this$name = this.getName();
        String other$name = other.getName();
        if (this$name == null ? other$name != null : !this$name.equals(other$name)) {
            return false;
        }
        String this$description = this.getDescription();
        String other$description = other.getDescription();
        if (this$description == null ? other$description != null : !this$description.equals(other$description)) {
            return false;
        }
        Map this$inputSchema = this.getInputSchema();
        Map other$inputSchema = other.getInputSchema();
        return !(this$inputSchema == null ? other$inputSchema != null : !(this$inputSchema).equals(other$inputSchema));
    }

    protected boolean canEqual(Object other) {
        return other instanceof ToolDefinition;
    }

    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        String $name = this.getName();
        result = result * 59 + ($name == null ? 43 : $name.hashCode());
        String $description = this.getDescription();
        result = result * 59 + ($description == null ? 43 : $description.hashCode());
        Map $inputSchema = this.getInputSchema();
        result = result * 59 + ($inputSchema == null ? 43 : ($inputSchema).hashCode());
        return result;
    }

    public String toString() {
        return "ToolDefinition(name=" + this.getName() + ", description=" + this.getDescription() + ", inputSchema=" + String.valueOf(this.getInputSchema()) + ")";
    }

    public static class Builder {
        private String name;
        private String description;
        private Map<String, Object> properties = new java.util.LinkedHashMap<>();
        private java.util.List<String> required = new java.util.ArrayList<>();

        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder stringProperty(String name, String desc, boolean required) {
            properties.put(name, Map.of("type", "string", "description", desc != null ? desc : ""));
            if (required) this.required.add(name);
            return this;
        }
        public Builder intProperty(String name, String desc, boolean required) {
            properties.put(name, Map.of("type", "integer", "description", desc != null ? desc : ""));
            if (required) this.required.add(name);
            return this;
        }
        public Builder arrayProperty(String name, String desc, Map<String, Object> items, boolean required) {
            properties.put(name, Map.of("type", "array", "description", desc != null ? desc : "", "items", items));
            if (required) this.required.add(name);
            return this;
        }
        public ToolDefinition build() {
            Map<String, Object> schema = new java.util.LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            if (!required.isEmpty()) schema.put("required", required);
            return new ToolDefinition(name, description, schema);
        }
    }
}
