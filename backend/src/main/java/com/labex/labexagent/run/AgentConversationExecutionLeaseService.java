package com.labex.labexagent.run;

import com.labex.entity.AgentConversation;
import com.labex.mapper.AgentConversationMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conversation 范围的单 runner 租约。
 *
 * <p>它复刻单 session 同时只允许一个运行者的语义，并使用持久化行锁适配多 JVM Web 部署。
 * Task 自己的 execution lease 不被替代；二者分别保护会话写入顺序和单次任务执行 epoch。</p>
 */
@Service
public class AgentConversationExecutionLeaseService {
    private final AgentConversationMapper conversationMapper;

    public AgentConversationExecutionLeaseService(AgentConversationMapper conversationMapper) {
        this.conversationMapper = Objects.requireNonNull(conversationMapper, "Conversation mapper is required");
    }

    @Transactional(rollbackFor = Exception.class)
    public Claim claim(ClaimRequest request) {
        validateIdentity(request.studentId(), request.projectId(), request.conversationId(), request.owner());
        requireFutureExpiry(request.now(), request.leaseExpiresAt());
        AgentConversation conversation = requireConversation(request.studentId(), request.projectId(), request.conversationId());
        String owner = conversation.getExecutionOwner();
        long epoch = epochOrZero(conversation.getExecutionEpoch());
        LocalDateTime currentExpiry = conversation.getExecutionLeaseExpiresAt();
        boolean active = owner != null && !owner.isBlank() && currentExpiry != null && currentExpiry.isAfter(request.now());
        if (active && !owner.equals(request.owner())) {
            return new Claim(false, owner, epoch, currentExpiry);
        }

        long nextEpoch = active ? epoch : nextEpoch(epoch, request.conversationId());
        conversation.setExecutionOwner(request.owner());
        conversation.setExecutionEpoch(nextEpoch);
        conversation.setExecutionLeaseExpiresAt(request.leaseExpiresAt());
        conversation.setExecutionHeartbeatAt(request.now());
        update(conversation);
        return new Claim(true, request.owner(), nextEpoch, request.leaseExpiresAt());
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean heartbeat(LeaseRef lease) {
        validateLease(lease, true);
        AgentConversation conversation = requireConversation(lease.studentId(), lease.projectId(), lease.conversationId());
        if (!ownsActiveLease(conversation, lease.owner(), lease.executionEpoch(), lease.now())) {
            return false;
        }
        conversation.setExecutionLeaseExpiresAt(lease.leaseExpiresAt());
        conversation.setExecutionHeartbeatAt(lease.now());
        update(conversation);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean release(LeaseRef lease) {
        validateLease(lease, false);
        AgentConversation conversation = requireConversation(lease.studentId(), lease.projectId(), lease.conversationId());
        if (!Objects.equals(conversation.getExecutionOwner(), lease.owner())
                || !Objects.equals(conversation.getExecutionEpoch(), lease.executionEpoch())) {
            return false;
        }
        conversation.setExecutionOwner(null);
        conversation.setExecutionLeaseExpiresAt(null);
        conversation.setExecutionHeartbeatAt(lease.now());
        update(conversation);
        return true;
    }

    private AgentConversation requireConversation(Integer studentId, Integer projectId, String conversationId) {
        AgentConversation conversation = conversationMapper.selectOwnedForUpdate(studentId, projectId, conversationId);
        if (conversation == null) {
            throw new IllegalStateException("Conversation is unavailable for execution lease: conversationId="
                    + conversationId);
        }
        return conversation;
    }

    private boolean ownsActiveLease(AgentConversation conversation, String owner, Long epoch, LocalDateTime now) {
        LocalDateTime expiry = conversation.getExecutionLeaseExpiresAt();
        return Objects.equals(conversation.getExecutionOwner(), owner)
                && Objects.equals(conversation.getExecutionEpoch(), epoch)
                && expiry != null
                && expiry.isAfter(now);
    }

    private void update(AgentConversation conversation) {
        if (conversationMapper.updateById(conversation) != 1) {
            throw new IllegalStateException("Failed to persist conversation execution lease: conversationId="
                    + conversation.getConversationId());
        }
    }

    private long nextEpoch(long current, String conversationId) {
        if (current < 0 || current == Long.MAX_VALUE) {
            throw new IllegalStateException("Conversation execution epoch is invalid: conversationId=" + conversationId);
        }
        return current + 1;
    }

    private long epochOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private void validateLease(LeaseRef lease, boolean requiresFutureExpiry) {
        if (lease == null) {
            throw new IllegalArgumentException("Conversation lease reference is required");
        }
        validateIdentity(lease.studentId(), lease.projectId(), lease.conversationId(), lease.owner());
        if (lease.executionEpoch() == null || lease.executionEpoch() <= 0 || lease.now() == null) {
            throw new IllegalArgumentException("Conversation lease reference requires a positive epoch and current time");
        }
        if (requiresFutureExpiry) {
            requireFutureExpiry(lease.now(), lease.leaseExpiresAt());
        }
    }

    private void validateIdentity(Integer studentId, Integer projectId, String conversationId, String owner) {
        if (studentId == null || studentId <= 0 || projectId == null || projectId <= 0
                || conversationId == null || conversationId.isBlank() || owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("Conversation lease requires owner and conversation identity");
        }
    }

    private void requireFutureExpiry(LocalDateTime now, LocalDateTime expiry) {
        if (now == null || expiry == null || !expiry.isAfter(now)) {
            throw new IllegalArgumentException("Conversation execution lease expiry must be after current time");
        }
    }

    public record ClaimRequest(Integer studentId, Integer projectId, String conversationId, String owner,
                               LocalDateTime now, LocalDateTime leaseExpiresAt) {
    }

    public record LeaseRef(Integer studentId, Integer projectId, String conversationId, String owner,
                           Long executionEpoch, LocalDateTime now, LocalDateTime leaseExpiresAt) {
    }

    public record Claim(boolean claimed, String owner, long executionEpoch, LocalDateTime leaseExpiresAt) {
    }
}
