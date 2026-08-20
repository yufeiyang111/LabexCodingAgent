package com.labex.monitor.geo;

import lombok.Data;

/** 一次 IP 归属地解析结果（国家/省/市/运营商，仅到城市级）。 */
@Data
public class IpLocation {
    private String country;
    private String province;
    private String city;
    private String isp;

    public String display() {
        StringBuilder sb = new StringBuilder();
        appendPart(sb, country);
        appendPart(sb, province);
        appendPart(sb, city);
        appendPart(sb, isp);
        return sb.isEmpty() ? "未知" : sb.toString();
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (part == null || part.isBlank() || "0".equals(part)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(part);
    }
}