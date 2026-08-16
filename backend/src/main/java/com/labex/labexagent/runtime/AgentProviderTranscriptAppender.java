package com.labex.labexagent.runtime;

import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.run.ExecutionFence;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Provider transcript 的唯一追加入口。
 *
 * <p>运行时写入必须先复制并校验 Provider 协议消息，再由 durable transcript 生成稳定序号。
 * AgentLoopEngine、Native batch 等编排器只能依赖此入口，不能各自维护内存消息列表或序号。</p>
 */
@Service
public class AgentProviderTranscriptAppender {
    private final AgentProviderMessageProjector providerMessageProjector;
    private final AgentRunTranscriptService transcriptService;

    public AgentProviderTranscriptAppender(AgentProviderMessageProjector providerMessageProjector,
                                           AgentRunTranscriptService transcriptService) {
        this.providerMessageProjector = Objects.requireNonNull(providerMessageProjector,
                "providerMessageProjector is required");
        this.transcriptService = Objects.requireNonNull(transcriptService, "transcriptService is required");
    }

    /**
     * 在当前 execution fence 内追加一条 Provider 协议消息。
     *
     * <p>序号由 durable transcript 的当前尾部决定；调用方不得传入本地递增计数。</p>
     */
    public void append(ExecutionFence executionFence, Long taskId, long executionEpoch,
                       Map<String, Object> providerMessage) {
        Map<String, Object> durableCopy = this.providerMessageProjector.copyMessage(providerMessage);
        this.transcriptService.appendMessage(executionFence, taskId, executionEpoch,
                this.transcriptService.nextSequence(taskId), durableCopy);
    }
}
