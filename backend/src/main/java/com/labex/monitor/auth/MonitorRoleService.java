package com.labex.monitor.auth;

import java.util.Locale;
import org.springframework.stereotype.Component;

/** 运维角色与最小权限判定；会话 token 只承担认证，角色来自登录时签发的会话值。 */
@Component
public class MonitorRoleService {

    public static final String VIEWER = "OPS_VIEWER";
    public static final String OPERATOR = "OPS_OPERATOR";
    public static final String ADMIN = "OPS_ADMIN";
    public static final String AUDITOR = "AUDITOR";

    public static final String REQUEST_ATTRIBUTE_ROLE = "monitor.role";

    /** 角色排行：只读 < 操作 < 管理；AUDITOR 只读但可查看审计。 */
    private static int rank(String role) {
        if (role == null) {
            return 0;
        }
        switch (role.toUpperCase(Locale.ROOT)) {
            case ADMIN:
                return 3;
            case OPERATOR:
                return 2;
            case AUDITOR:
                return 2;
            case VIEWER:
                return 1;
            default:
                return 0;
        }
    }

    public boolean hasRole(String actualRole, String minimumRole) {
        return rank(actualRole) >= rank(minimumRole);
    }

    /** 规范化未知角色为只读，保证会话值安全。 */
    public String normalize(String role) {
        String upper = role == null ? "" : role.toUpperCase(Locale.ROOT).trim();
        if (ADMIN.equals(upper) || OPERATOR.equals(upper) || AUDITOR.equals(upper)) {
            return upper;
        }
        return VIEWER;
    }
}