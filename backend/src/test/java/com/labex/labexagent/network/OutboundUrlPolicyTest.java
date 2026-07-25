package com.labex.labexagent.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OutboundUrlPolicyTest {

    @Test
    void acceptsHttpDestinationsResolvedOnlyToPublicAddresses() throws Exception {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(host -> addresses("8.8.8.8", "2001:4860:4860::8888"));

        OutboundUrlPolicy.ValidatedDestination destination = policy.validate("https://docs.example.dev/path?q=1");

        assertEquals(URI.create("https://docs.example.dev/path?q=1"), destination.uri());
        assertEquals(2, destination.resolvedAddresses().size());
    }

    @Test
    void rejectsPrivateAndMetadataAddresses() throws Exception {
        OutboundUrlPolicy privatePolicy = new OutboundUrlPolicy(host -> addresses("10.0.0.7"));
        OutboundUrlPolicy metadataPolicy = new OutboundUrlPolicy(host -> addresses("169.254.169.254"));

        assertRejected(privatePolicy, "http://internal.example.test/");
        assertRejected(metadataPolicy, "http://metadata.google.internal/computeMetadata/v1/");
    }

    @Test
    void rejectsAHostWhenAnyDnsAnswerIsBlocked() throws Exception {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(host -> addresses("8.8.8.8", "127.0.0.1"));

        assertRejected(policy, "https://mixed.example.test/");
    }

    @Test
    void acceptsHttpsHostnamesResolvedThroughAProxySyntheticAddress() throws Exception {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(host -> addresses("198.18.0.51"));

        OutboundUrlPolicy.ValidatedDestination destination = policy.validate("https://api.example.test/v1");

        assertEquals(URI.create("https://api.example.test/v1"), destination.uri());
    }

    @Test
    void rejectsUnsafeUsesOfProxySyntheticAddresses() throws Exception {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(host -> addresses("198.18.0.51"));

        assertRejected(policy, "http://api.example.test/v1");
        assertRejected(policy, "https://198.18.0.51/v1");
    }
    @Test
    void revalidatesDnsForEachConnectionToDefendAgainstRebinding() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        OutboundUrlPolicy policy = new OutboundUrlPolicy(host -> resolutions.incrementAndGet() == 1
                ? addresses("8.8.8.8")
                : addresses("192.168.1.7"));

        policy.validate("https://changes.example.test/");

        assertRejected(policy, "https://changes.example.test/");
        assertEquals(2, resolutions.get());
    }

    @Test
    void validatesEveryRedirectDestination() throws Exception {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(host -> host.startsWith("public")
                ? addresses("8.8.8.8")
                : addresses("127.0.0.1"));

        assertRejected(policy, URI.create("https://public.example.test/start"), "http://redirected.example.test/private");
    }

    private void assertRejected(OutboundUrlPolicy policy, String url) {
        assertThrows(OutboundUrlPolicy.RejectedOutboundUrlException.class, () -> policy.validate(url));
    }

    private void assertRejected(OutboundUrlPolicy policy, URI current, String location) {
        assertThrows(OutboundUrlPolicy.RejectedOutboundUrlException.class,
                () -> policy.validateRedirect(current, location));
    }

    private InetAddress[] addresses(String... values) {
        try {
            InetAddress[] addresses = new InetAddress[values.length];
            for (int index = 0; index < values.length; index++) {
                addresses[index] = InetAddress.getByName(values[index]);
            }
            return addresses;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
