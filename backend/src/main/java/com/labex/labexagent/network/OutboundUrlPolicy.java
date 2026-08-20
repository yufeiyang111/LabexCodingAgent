package com.labex.labexagent.network;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Fail-closed validation for user-controlled HTTP destinations.
 */
@Component
public class OutboundUrlPolicy {
    private final DnsResolver dnsResolver;

    public OutboundUrlPolicy() {
        this(InetAddress::getAllByName);
    }

    public OutboundUrlPolicy(DnsResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
    }

    public ValidatedDestination validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw reject(RejectionReason.INVALID_URL, "URL is required");
        }
        try {
            return validate(URI.create(rawUrl.trim()));
        } catch (RejectedOutboundUrlException e) {
            throw e;
        } catch (Exception e) {
            throw reject(RejectionReason.INVALID_URL, "URL is invalid");
        }
    }

    public ValidatedDestination validate(URI uri) {
        if (uri == null || !uri.isAbsolute()) {
            throw reject(RejectionReason.INVALID_URL, "URL must be absolute");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw reject(RejectionReason.UNSUPPORTED_SCHEME, "Only HTTP(S) URLs are allowed");
        }
        if (uri.getUserInfo() != null) {
            throw reject(RejectionReason.USER_INFO, "URL user info is not allowed");
        }
        String host = normalizedHost(uri);
        if (host.isBlank()) {
            throw reject(RejectionReason.MISSING_HOST, "URL host is required");
        }
        if (isBlockedHost(host)) {
            throw reject(RejectionReason.BLOCKED_HOST, "URL host is blocked");
        }

        List<InetAddress> addresses = resolve(host);
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address) && !isAllowedProxySyntheticAddress(uri, host, address)) {
                throw reject(RejectionReason.BLOCKED_ADDRESS, "URL resolves to a blocked address");
            }
        }
        return new ValidatedDestination(uri, addresses);
    }

    /**
     * 对齐 OpenCode / Vercel AI SDK：LLM Provider 允许 HTTP、HTTPS 以及本地/私有模型端点（Ollama/vLLM/LocalAI等）。
     */
    public ValidatedDestination validateProviderUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw reject(RejectionReason.INVALID_URL, "URL is required");
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            if (!uri.isAbsolute()) {
                throw reject(RejectionReason.INVALID_URL, "URL must be absolute");
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw reject(RejectionReason.UNSUPPORTED_SCHEME, "Only HTTP(S) URLs are allowed");
            }
            if (uri.getUserInfo() != null) {
                throw reject(RejectionReason.USER_INFO, "URL user info is not allowed");
            }
            String host = normalizedHost(uri);
            if (host.isBlank()) {
                throw reject(RejectionReason.MISSING_HOST, "URL host is required");
            }
            if (isCloudMetadataHost(host)) {
                throw reject(RejectionReason.BLOCKED_HOST, "Cloud metadata endpoint is blocked");
            }
            return new ValidatedDestination(uri, List.of());
        } catch (RejectedOutboundUrlException e) {
            throw e;
        } catch (Exception e) {
            throw reject(RejectionReason.INVALID_URL, "URL is invalid");
        }
    }

    private boolean isCloudMetadataHost(String host) {
        return "metadata".equals(host)
                || "metadata.google.internal".equals(host)
                || "instance-data".equals(host)
                || "instance-data.ec2.internal".equals(host);
    }

    public ValidatedDestination validateRedirect(URI current, String location) {
        if (current == null || location == null || location.isBlank()) {
            throw reject(RejectionReason.INVALID_REDIRECT, "Redirect location is invalid");
        }
        try {
            return validate(current.resolve(location.trim()));
        } catch (RejectedOutboundUrlException e) {
            throw e;
        } catch (Exception e) {
            throw reject(RejectionReason.INVALID_REDIRECT, "Redirect location is invalid");
        }
    }

    private List<InetAddress> resolve(String host) {
        try {
            InetAddress[] resolved = dnsResolver.resolve(host);
            if (resolved == null || resolved.length == 0) {
                throw reject(RejectionReason.DNS_FAILURE, "URL host could not be resolved");
            }
            List<InetAddress> addresses = new ArrayList<>(resolved.length);
            for (InetAddress address : resolved) {
                if (address == null) {
                    throw reject(RejectionReason.DNS_FAILURE, "URL host resolved to an invalid address");
                }
                addresses.add(address);
            }
            return List.copyOf(addresses);
        } catch (RejectedOutboundUrlException e) {
            throw e;
        } catch (Exception e) {
            throw reject(RejectionReason.DNS_FAILURE, "URL host could not be resolved");
        }
    }

    private String normalizedHost(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "";
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            return IDN.toASCII(normalized, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw reject(RejectionReason.INVALID_URL, "URL host is invalid");
        }
    }

    private boolean isBlockedHost(String host) {
        return "localhost".equals(host)
                || host.endsWith(".localhost")
                || "metadata".equals(host)
                || "metadata.google.internal".equals(host)
                || "instance-data".equals(host)
                || "instance-data.ec2.internal".equals(host)
                || "localhost.localdomain".equals(host);
    }

    private boolean isAllowedProxySyntheticAddress(URI uri, String host, InetAddress address) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || isIpLiteral(host)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 4
                && (bytes[0] & 0xff) == 198
                && ((bytes[1] & 0xff) == 18 || (bytes[1] & 0xff) == 19);
    }

    private boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
    }
    private boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address || bytes.length == 4) {
            return isBlockedIpv4(bytes);
        }
        if (address instanceof Inet6Address || bytes.length == 16) {
            return isBlockedIpv6(bytes);
        }
        return true;
    }

    private boolean isBlockedIpv4(byte[] bytes) {
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        int third = bytes[2] & 0xff;
        return first == 0
                || first == 10
                || first == 127
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 192 && second == 0 && (third == 0 || third == 2))
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113)
                || first >= 224;
    }

    private boolean isBlockedIpv6(byte[] bytes) {
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        if ((first & 0xfe) == 0xfc || (first == 0xfe && (second & 0xc0) == 0x80)) {
            return true;
        }
        if (first == 0x20 && second == 0x01 && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8) {
            return true;
        }
        if (isIpv4Mapped(bytes)) {
            byte[] ipv4 = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
            return isBlockedIpv4(ipv4);
        }
        return false;
    }

    private boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private RejectedOutboundUrlException reject(RejectionReason reason, String message) {
        return new RejectedOutboundUrlException(reason, message);
    }

    @FunctionalInterface
    public interface DnsResolver {
        InetAddress[] resolve(String host) throws Exception;
    }

    public record ValidatedDestination(URI uri, List<InetAddress> resolvedAddresses) {
    }

    public enum RejectionReason {
        INVALID_URL,
        UNSUPPORTED_SCHEME,
        USER_INFO,
        MISSING_HOST,
        BLOCKED_HOST,
        DNS_FAILURE,
        BLOCKED_ADDRESS,
        INVALID_REDIRECT
    }

    public static final class RejectedOutboundUrlException extends IllegalArgumentException {
        private final RejectionReason reason;

        public RejectedOutboundUrlException(RejectionReason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public RejectionReason reason() {
            return reason;
        }
    }
}
