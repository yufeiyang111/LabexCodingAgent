package com.labex.labexagent.tool;

import com.google.gson.JsonObject;

public class ToolCall {
    private String tool;
    private JsonObject arguments;

    public String getTool() {
        return this.tool;
    }

    public JsonObject getArguments() {
        return this.arguments;
    }

    public void setTool(String tool) {
        this.tool = tool;
    }

    public void setArguments(JsonObject arguments) {
        this.arguments = arguments;
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof ToolCall)) {
            return false;
        }
        ToolCall other = (ToolCall)o;
        if (!other.canEqual(this)) {
            return false;
        }
        String this$tool = this.getTool();
        String other$tool = other.getTool();
        if (this$tool == null ? other$tool != null : !this$tool.equals(other$tool)) {
            return false;
        }
        JsonObject this$arguments = this.getArguments();
        JsonObject other$arguments = other.getArguments();
        return !(this$arguments == null ? other$arguments != null : !this$arguments.equals(other$arguments));
    }

    protected boolean canEqual(Object other) {
        return other instanceof ToolCall;
    }

    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        String $tool = this.getTool();
        result = result * 59 + ($tool == null ? 43 : $tool.hashCode());
        JsonObject $arguments = this.getArguments();
        result = result * 59 + ($arguments == null ? 43 : $arguments.hashCode());
        return result;
    }

    public String toString() {
        return "ToolCall(tool=" + this.getTool() + ", arguments=" + String.valueOf(this.getArguments()) + ")";
    }
}

