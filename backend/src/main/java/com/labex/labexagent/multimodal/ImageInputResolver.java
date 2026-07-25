package com.labex.labexagent.multimodal;

import com.labex.labexagent.network.OutboundUrlPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Resolves allowed local, data-URL, and remote image inputs into bounded data URLs. */
@Component
public class ImageInputResolver implements ImageSourceResolver {
    private static final long MAX_IMAGE_BYTES = 20L * 1024L * 1024L;
    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp");

    private final OutboundUrlPolicy outboundUrlPolicy;

    public ImageInputResolver() {
        this(new OutboundUrlPolicy());
    }

    @Autowired
    public ImageInputResolver(OutboundUrlPolicy outboundUrlPolicy) {
        this.outboundUrlPolicy = outboundUrlPolicy;
    }

    public String resolve(String imageSource) {
        String source = imageSource == null ? "" : imageSource.trim();
        if (source.isBlank()) {
            throw new IllegalArgumentException("image source is required");
        }
        String lower = source.toLowerCase(Locale.ROOT);
        if (lower.startsWith("data:")) {
            validateDataUrl(source);
            return source;
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            DownloadedImage downloaded = download(URI.create(source));
            return toDataUrl(downloaded.contentType(), downloaded.data());
        }
        return localFileToDataUrl(Path.of(source));
    }

    private String localFileToDataUrl(Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("image file does not exist or is not a regular file");
            }
            long size = Files.size(path);
            if (size > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("image exceeds 20MB");
            }
            return toDataUrl(Files.probeContentType(path), Files.readAllBytes(path));
        } catch (IOException e) {
            throw new IllegalArgumentException("unable to read image file", e);
        }
    }

    private DownloadedImage download(URI source) {
        HttpURLConnection connection = null;
        try {
            URI destination = outboundUrlPolicy.validate(source).uri();
            connection = (HttpURLConnection) destination.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "image/jpeg,image/png,image/gif,image/webp");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalArgumentException("image URL returned HTTP " + status);
            }
            long declaredLength = connection.getContentLengthLong();
            if (declaredLength > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("image exceeds 20MB");
            }
            return new DownloadedImage(connection.getContentType(), readBounded(connection.getInputStream()));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("unable to download image", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private byte[] readBounded(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = source.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_IMAGE_BYTES) {
                    throw new IllegalArgumentException("image exceeds 20MB");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private String toDataUrl(String detectedContentType, byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("image has no content");
        }
        if (data.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("image exceeds 20MB");
        }
        String mimeType = normalizeMimeType(detectedContentType);
        if (!SUPPORTED_MIME_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("only JPEG, PNG, GIF, and WebP images are supported");
        }
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(data);
    }

    private void validateDataUrl(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (comma <= 0 || !dataUrl.substring(0, comma).toLowerCase(Locale.ROOT).contains(";base64")) {
            throw new IllegalArgumentException("image data URL must be base64 encoded");
        }
        String mimeType = normalizeMimeType(dataUrl.substring(5, comma));
        if (!SUPPORTED_MIME_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("only JPEG, PNG, GIF, and WebP images are supported");
        }
        long approximateBytes = (long) ((dataUrl.length() - comma - 1) * 0.75d);
        if (approximateBytes > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("image exceeds 20MB");
        }
    }

    private String normalizeMimeType(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (lower.contains("png")) return "image/png";
        if (lower.contains("webp")) return "image/webp";
        if (lower.contains("gif")) return "image/gif";
        if (lower.contains("jpeg") || lower.contains("jpg")) return "image/jpeg";
        return lower.replace("data:", "").replace(";base64", "").trim();
    }

    private record DownloadedImage(String contentType, byte[] data) {
    }
}
