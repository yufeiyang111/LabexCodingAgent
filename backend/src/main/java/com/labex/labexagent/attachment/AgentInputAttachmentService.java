package com.labex.labexagent.attachment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.labex.entity.AgentInputAttachment;
import com.labex.entity.AgentModelConfig;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentInputAttachmentMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.service.AgentModelConfigService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** \u8d1f\u8d23 Agent \u8f93\u5165\u56fe\u7247\u7684\u4e34\u65f6\u4fdd\u5b58\u3001\u7ed1\u5b9a\u3001\u6295\u5f71\u4e0e\u8fc7\u671f\u6e05\u7406\u3002 */
@Service
public class AgentInputAttachmentService {
    private static final Set<String> ACTIVE_TASK_STATUSES = Set.of(
            "pending", "queued", "preparing", "running", "waiting_approval", "waiting_user",
            "waiting_workspace", "retry_backoff", "retrying", "recovering");

    private final AgentInputAttachmentMapper attachmentMapper;
    private final AgentTaskMapper taskMapper;
    private final AgentModelConfigService modelConfigService;
    private final AgentAttachmentProperties properties;

    public AgentInputAttachmentService(AgentInputAttachmentMapper attachmentMapper,
                                       AgentTaskMapper taskMapper,
                                       AgentModelConfigService modelConfigService,
                                       AgentAttachmentProperties properties) {
        this.attachmentMapper = attachmentMapper;
        this.taskMapper = taskMapper;
        this.modelConfigService = modelConfigService;
        this.properties = properties;
    }

    /** \u5411\u524d\u7aef\u63d0\u4f9b\u4e0e\u540e\u7aef\u4e00\u81f4\u7684\u56fe\u7247\u9650\u5236\u3002 */
    public Map<String, Object> policy() {
        return Map.of(
                "maxFilesPerMessage", Math.max(1, properties.getMaxFilesPerMessage()),
                "maxFileSizeBytes", Math.max(1L, properties.getMaxFileSizeBytes()),
                "maxTotalSizeBytes", Math.max(1L, properties.getMaxTotalSizeBytes()),
                "allowedMimeTypes", List.copyOf(properties.getAllowedMimeTypes()));
    }

    /** \u5728\u4efb\u52a1\u521b\u5efa\u524d\u6821\u9a8c\u5e76\u6682\u5b58\u56fe\u7247\u3002 */
    public List<String> storeForRequest(Integer studentId, Integer projectId, Integer modelConfigId,
                                        List<MultipartFile> images) {
        List<MultipartFile> files = images == null ? List.of() : images.stream()
                .filter(file -> file != null && !file.isEmpty())
                .toList();
        if (files.isEmpty()) {
            return List.of();
        }

        AgentModelConfig config = modelConfigService.resolveForStudent(studentId, modelConfigId);
        if (config == null || !Integer.valueOf(1).equals(config.getImageInputEnabled())) {
            throw new IllegalArgumentException("Current model is text-only and cannot accept images");
        }
        if (files.size() > Math.max(1, properties.getMaxFilesPerMessage())) {
            throw new IllegalArgumentException("Too many images in one message");
        }

        long totalSize = 0L;
        List<String> attachmentIds = new ArrayList<>();
        List<Path> createdFiles = new ArrayList<>();
        try {
            Files.createDirectories(storageRoot());
            for (MultipartFile file : files) {
                long declaredSize = file.getSize();
                if (declaredSize <= 0 || declaredSize > properties.getMaxFileSizeBytes()) {
                    throw new IllegalArgumentException("Image exceeds the per-file size limit");
                }
                totalSize += declaredSize;
                if (totalSize > properties.getMaxTotalSizeBytes()) {
                    throw new IllegalArgumentException("Images exceed the total size limit");
                }

                byte[] bytes = file.getBytes();
                if (bytes.length == 0 || bytes.length > properties.getMaxFileSizeBytes()) {
                    throw new IllegalArgumentException("Image exceeds the per-file size limit");
                }
                totalSize += bytes.length - declaredSize;
                if (totalSize > properties.getMaxTotalSizeBytes()) {
                    throw new IllegalArgumentException("Images exceed the total size limit");
                }
                String mimeType = detectMimeType(bytes);
                if (!properties.getAllowedMimeTypes().contains(mimeType)) {
                    throw new IllegalArgumentException("Only JPEG, PNG, GIF and WebP images are supported");
                }

                String attachmentId = UUID.randomUUID().toString();
                String storageKey = attachmentId + extensionFor(mimeType);
                Path target = storageRoot().resolve(storageKey).normalize();
                if (!target.startsWith(storageRoot())) {
                    throw new IllegalArgumentException("Unsafe attachment path");
                }
                Files.write(target, bytes);
                createdFiles.add(target);

                LocalDateTime now = LocalDateTime.now();
                AgentInputAttachment attachment = new AgentInputAttachment();
                attachment.setAttachmentId(attachmentId);
                attachment.setStudentId(studentId);
                attachment.setProjectId(projectId);
                attachment.setOriginalFilename(safeFilename(file.getOriginalFilename()));
                attachment.setMimeType(mimeType);
                attachment.setSizeBytes((long) bytes.length);
                attachment.setSha256(sha256(bytes));
                attachment.setStorageKey(storageKey);
                attachment.setStatus("pending");
                attachment.setExpiresAt(now.plusMinutes(Math.max(1, properties.getPendingTtlMinutes())));
                attachment.setCreateTime(now);
                attachment.setUpdateTime(now);
                attachmentMapper.insert(attachment);
                attachmentIds.add(attachmentId);
            }
            return List.copyOf(attachmentIds);
        } catch (IOException failure) {
            cleanupFailedStore(studentId, projectId, attachmentIds, createdFiles);
            throw new IllegalArgumentException("Unable to store image attachments", failure);
        } catch (RuntimeException failure) {
            cleanupFailedStore(studentId, projectId, attachmentIds, createdFiles);
            throw failure;
        }
    }

    /** \u5c06\u8fd9\u6b21\u8bf7\u6c42\u7684\u4e34\u65f6\u9644\u4ef6\u7ed1\u5b9a\u5230\u65b0\u5efa task\u3002 */
    public void bindToTask(Integer studentId, Integer projectId, Long taskId,
                           String conversationId, List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (String attachmentId : attachmentIds) {
            AgentInputAttachment attachment = findOwned(studentId, projectId, attachmentId);
            if (attachment == null || !"pending".equals(attachment.getStatus())) {
                throw new IllegalArgumentException("Image attachment is unavailable or already bound");
            }
            attachment.setTaskId(taskId);
            attachment.setConversationId(conversationId);
            attachment.setStatus("bound");
            attachment.setExpiresAt(now.plusHours(Math.max(1, properties.getTerminalTtlHours())));
            attachment.setUpdateTime(now);
            attachmentMapper.updateById(attachment);
        }
    }

    /** \u751f\u6210\u4e0d\u542b Base64 \u7684\u53ef\u6301\u4e45\u5316 user message\u3002 */
    public Map<String, Object> durableUserMessage(String text, List<String> attachmentIds) {
        LinkedHashMap<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", text == null ? "" : text);
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            message.put("attachmentIds", List.copyOf(attachmentIds));
        }
        return message;
    }

    /** \u5728 Provider \u8bf7\u6c42\u8fb9\u754c\u4ece\u4e34\u65f6\u6587\u4ef6\u91cd\u5efa\u56fe\u7247 data URL\u3002 */
    public Map<String, Object> hydrateProviderMessage(Long taskId, Map<String, Object> durableMessage) {
        if (durableMessage == null || !"user".equalsIgnoreCase(stringValue(durableMessage.get("role")))) {
            return durableMessage;
        }
        List<String> attachmentIds = attachmentIds(durableMessage.get("attachmentIds"));
        if (attachmentIds.isEmpty()) {
            return withoutAttachmentIds(durableMessage);
        }
        if (taskId == null || taskId <= 0) {
            throw new IllegalStateException("Attachment projection requires a durable task ID");
        }

        LinkedHashMap<String, AgentInputAttachment> attachments = new LinkedHashMap<>();
        List<AgentInputAttachment> rows = attachmentMapper.selectList(new LambdaQueryWrapper<AgentInputAttachment>()
                .eq(AgentInputAttachment::getTaskId, taskId)
                .in(AgentInputAttachment::getAttachmentId, attachmentIds));
        if (rows != null) {
            for (AgentInputAttachment row : rows) {
                attachments.put(row.getAttachmentId(), row);
            }
        }

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", stringValue(durableMessage.get("content"))));
        boolean omittedExpiredImage = false;
        for (String attachmentId : attachmentIds) {
            AgentInputAttachment attachment = attachments.get(attachmentId);
            if (attachment == null || !"bound".equals(attachment.getStatus())) {
                omittedExpiredImage = true;
                continue;
            }
            Path path = storageRoot().resolve(attachment.getStorageKey()).normalize();
            if (!path.startsWith(storageRoot()) || !Files.isRegularFile(path)) {
                omittedExpiredImage = true;
                continue;
            }
            try {
                String dataUrl = "data:" + attachment.getMimeType() + ";base64,"
                        + Base64.getEncoder().encodeToString(Files.readAllBytes(path));
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
            } catch (IOException failure) {
                omittedExpiredImage = true;
            }
        }
        if (omittedExpiredImage) {
            content.add(Map.of("type", "text",
                    "text", "[A previously attached image has expired and is unavailable.]"));
        }
        LinkedHashMap<String, Object> hydrated = withoutAttachmentIds(durableMessage);
        hydrated.put("content", content);
        return hydrated;
    }

    /** 向会话历史投影提供不含存储路径和文件内容的附件展示元数据。 */
    public List<HistoryAttachment> historyAttachments(Integer studentId, Integer projectId, List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        LinkedHashSet<String> uniqueIds = new LinkedHashSet<>(attachmentIds);
        List<HistoryAttachment> result = new ArrayList<>();
        for (String attachmentId : uniqueIds) {
            AgentInputAttachment attachment = findOwned(studentId, projectId, attachmentId);
            if (attachment == null) {
                result.add(new HistoryAttachment(attachmentId, "图片", "", true));
                continue;
            }
            boolean expired = "deleted".equalsIgnoreCase(attachment.getStatus())
                    || attachment.getExpiresAt() == null || !attachment.getExpiresAt().isAfter(now);
            result.add(new HistoryAttachment(attachment.getAttachmentId(), attachment.getOriginalFilename(),
                    attachment.getMimeType(), expired));
        }
        return List.copyOf(result);
    }

    public Preview preview(Integer studentId, Integer projectId, String attachmentId) {
        AgentInputAttachment attachment = findOwned(studentId, projectId, attachmentId);
        if (attachment == null || "deleted".equals(attachment.getStatus())) {
            throw new IllegalArgumentException("Image attachment is unavailable");
        }
        Path path = storageRoot().resolve(attachment.getStorageKey()).normalize();
        if (!path.startsWith(storageRoot()) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Image attachment file is unavailable");
        }
        return new Preview(path, attachment.getMimeType(), attachment.getOriginalFilename());
    }

    /** \u5b9a\u65f6\u5220\u9664\u8fc7\u671f\u6587\u4ef6\uff1b\u6d3b\u8dc3 task \u7684\u9644\u4ef6\u4e0d\u4f1a\u88ab\u63d0\u524d\u56de\u6536\u3002 */
    @Scheduled(fixedDelayString = "${labex-agent.attachment.cleanup-interval-ms:900000}")
    public void cleanupExpired() {
        LocalDateTime now = LocalDateTime.now();
        int batchSize = Math.max(1, properties.getCleanupBatchSize());
        List<AgentInputAttachment> attachments = attachmentMapper.selectList(
                new LambdaQueryWrapper<AgentInputAttachment>()
                        .le(AgentInputAttachment::getExpiresAt, now)
                        .ne(AgentInputAttachment::getStatus, "deleted")
                        .orderByAsc(AgentInputAttachment::getExpiresAt)
                        .last("LIMIT " + batchSize));
        for (AgentInputAttachment attachment : attachments) {
            if (isActiveTask(attachment)) {
                continue;
            }
            deletePhysicalFile(attachment);
            attachment.setStatus("deleted");
            attachment.setUpdateTime(now);
            attachmentMapper.updateById(attachment);
        }
    }

    private boolean isActiveTask(AgentInputAttachment attachment) {
        if (attachment.getTaskId() == null) {
            return false;
        }
        AgentTask task = taskMapper.selectById(attachment.getTaskId());
        return task != null && ACTIVE_TASK_STATUSES.contains(
                stringValue(task.getStatus()).toLowerCase(Locale.ROOT));
    }

    private AgentInputAttachment findOwned(Integer studentId, Integer projectId, String attachmentId) {
        if (attachmentId == null || attachmentId.isBlank()) {
            return null;
        }
        return attachmentMapper.selectOne(new LambdaQueryWrapper<AgentInputAttachment>()
                .eq(AgentInputAttachment::getAttachmentId, attachmentId)
                .eq(AgentInputAttachment::getStudentId, studentId)
                .eq(AgentInputAttachment::getProjectId, projectId));
    }

    private void cleanupFailedStore(Integer studentId, Integer projectId,
                                    List<String> attachmentIds, List<Path> createdFiles) {
        for (String attachmentId : attachmentIds) {
            AgentInputAttachment attachment = findOwned(studentId, projectId, attachmentId);
            if (attachment != null) {
                attachmentMapper.deleteById(attachment.getAttachmentId());
            }
        }
        for (Path path : createdFiles) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // \u540e\u53f0\u6e05\u7406\u4efb\u52a1\u4f1a\u518d\u6b21\u5904\u7406\u6570\u636e\u5e93\u4e2d\u4ecd\u53ef\u89c1\u7684\u6b8b\u7559\u9644\u4ef6\u3002
            }
        }
    }

    private void deletePhysicalFile(AgentInputAttachment attachment) {
        try {
            Path path = storageRoot().resolve(attachment.getStorageKey()).normalize();
            if (path.startsWith(storageRoot())) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // \u8bb0\u5f55\u4f1a\u4fdd\u7559\u4e3a deleted\uff0c\u907f\u514d\u6e05\u7406\u5931\u8d25\u65f6\u6c38\u4e45\u91cd\u8bd5\u5e76\u963b\u585e\u5176\u4ed6\u9644\u4ef6\u3002
        }
    }

    private LinkedHashMap<String, Object> withoutAttachmentIds(Map<String, Object> source) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (!"attachmentIds".equals(entry.getKey())) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    private List<String> attachmentIds(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (Object item : raw) {
            String id = stringValue(item).trim();
            if (!id.isBlank()) {
                unique.add(id);
            }
        }
        return List.copyOf(unique);
    }

    private Path storageRoot() {
        return Path.of(properties.getStoragePath()).toAbsolutePath().normalize();
    }

    private String safeFilename(String value) {
        String filename = value == null || value.isBlank() ? "image" : Path.of(value).getFileName().toString();
        return filename.replaceAll("[\\\\/:*?\\\"<>|\\\\p{Cntrl}]", "_");
    }

    private String extensionFor(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }

    private String detectMimeType(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50
                && bytes[2] == 0x4E && bytes[3] == 0x47) {
            return "image/png";
        }
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        throw new IllegalArgumentException("Image content is not a supported image format");
    }

    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to calculate image checksum", failure);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record Preview(Path path, String mimeType, String filename) {
    }

    public record HistoryAttachment(String attachmentId, String name, String mimeType, boolean expired) {
    }
}
