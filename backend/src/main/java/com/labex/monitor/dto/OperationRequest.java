package com.labex.monitor.dto;

import lombok.Data;

@Data
public class OperationRequest {
    private String idempotencyKey;
    private String reason;
    private String operatorCode;
}