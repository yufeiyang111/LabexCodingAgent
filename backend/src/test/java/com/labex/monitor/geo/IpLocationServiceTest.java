package com.labex.monitor.geo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IpLocationServiceTest {

    @Test
    void parsesRegionTextIntoDisplay() {
        IpLocation loc = IpLocationService.parse("中国|广东|深圳|电信");
        assertThat(loc.getCountry()).isEqualTo("中国");
        assertThat(loc.getProvince()).isEqualTo("广东");
        assertThat(loc.getCity()).isEqualTo("深圳");
        assertThat(loc.getIsp()).isEqualTo("电信");
        assertThat(loc.display()).isEqualTo("中国 广东 深圳 电信");
    }

    @Test
    void treatsZeroSegmentsAsUnknown() {
        IpLocation loc = IpLocationService.parse("0|0|0|0");
        assertThat(loc.getCountry()).isNull();
        assertThat(loc.getProvince()).isNull();
        assertThat(loc.display()).isEqualTo("未知");
    }

    @Test
    void treatsBlankRegionAsUnknown() {
        assertThat(IpLocationService.parse(null)).isNull();
        assertThat(IpLocationService.parse("")).isNull();
    }

    @Test
    void flagsPrivateAndReservedAddresses() {
        assertThat(IpLocationService.isLocalOrInvalid("127.0.0.1")).isTrue();
        assertThat(IpLocationService.isLocalOrInvalid("10.1.2.3")).isTrue();
        assertThat(IpLocationService.isLocalOrInvalid("192.168.1.1")).isTrue();
        assertThat(IpLocationService.isLocalOrInvalid("172.16.0.1")).isTrue();
        assertThat(IpLocationService.isLocalOrInvalid("169.254.1.1")).isTrue();
        assertThat(IpLocationService.isLocalOrInvalid("0.0.0.0")).isTrue();
        assertThat(IpLocationService.isLocalOrInvalid("::1")).isTrue();
        assertThat(IpLocationService.isLocalOrInvalid("not-an-ip")).isTrue();
        assertThat(IpLocationService.isLocalOrInvalid(null)).isTrue();
        assertThat(IpLocationService.isLocalOrInvalid("8.8.8.8")).isFalse();
        assertThat(IpLocationService.isLocalOrInvalid("114.114.114.114")).isFalse();
    }

    @Test
    void resolvesKnownPublicIpFromBundledXdb() {
        IpLocationProperties props = new IpLocationProperties();
        props.setEnabled(true);
        IpLocationService service = new IpLocationService(props);
        service.init();

        IpLocation loc = service.lookup("114.114.114.114");
        assertThat(loc).isNotNull();
        assertThat(loc.getCountry()).isNotBlank();
        assertThat(loc.display()).isNotBlank();
    }

    @Test
    void returnsNullForLocalAddresses() {
        IpLocationProperties props = new IpLocationProperties();
        props.setEnabled(true);
        IpLocationService service = new IpLocationService(props);
        service.init();

        assertThat(service.lookup("127.0.0.1")).isNull();
        assertThat(service.lookup(null)).isNull();
        assertThat(service.lookup("192.168.0.5")).isNull();
    }
}