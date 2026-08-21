package com.labex.monitor.operation;

import com.labex.entity.OpsOperation;
import com.labex.mapper.OpsOperationMapper;
import org.springframework.stereotype.Service;

/** 操作幂等：同一幂等键（action + target + 请求方）只执行一次，重复提交返回首次结果。 */
@Service
public class OperationIdempotencyService {

    private final OpsOperationMapper operationMapper;

    public OperationIdempotencyService(OpsOperationMapper operationMapper) {
        this.operationMapper = operationMapper;
    }

    /** 幂等键缺失或为空白时不允许执行。 */
    public boolean isValidKey(String idempotencyKey) {
        return idempotencyKey != null && !idempotencyKey.isBlank() && idempotencyKey.length() <= 128;
    }

    /** 返回已存在操作；未执行过返回 null。 */
    public OpsOperation findExisting(String idempotencyKey) {
        return operationMapper.selectByIdempotencyKey(idempotencyKey);
    }

    public String buildKey(String actionType, String targetType, String targetId, String requestKey) {
        return requestKey;
    }
}