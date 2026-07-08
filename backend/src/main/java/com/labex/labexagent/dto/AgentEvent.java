package com.labex.labexagent.dto;

public class AgentEvent {
    private String type;
    private Object data;

    public String getType() {
        return this.type;
    }

    public Object getData() {
        return this.data;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof AgentEvent)) {
            return false;
        }
        AgentEvent other = (AgentEvent)o;
        if (!other.canEqual(this)) {
            return false;
        }
        String this$type = this.getType();
        String other$type = other.getType();
        if (this$type == null ? other$type != null : !this$type.equals(other$type)) {
            return false;
        }
        Object this$data = this.getData();
        Object other$data = other.getData();
        return !(this$data == null ? other$data != null : !this$data.equals(other$data));
    }

    protected boolean canEqual(Object other) {
        return other instanceof AgentEvent;
    }

    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        String $type = this.getType();
        result = result * 59 + ($type == null ? 43 : $type.hashCode());
        Object $data = this.getData();
        result = result * 59 + ($data == null ? 43 : $data.hashCode());
        return result;
    }

    public String toString() {
        return "AgentEvent(type=" + this.getType() + ", data=" + String.valueOf(this.getData()) + ")";
    }

    public AgentEvent() {
    }

    public AgentEvent(String type, Object data) {
        this.type = type;
        this.data = data;
    }
}

