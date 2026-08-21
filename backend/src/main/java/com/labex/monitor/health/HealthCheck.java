package com.labex.monitor.health;

/** 单个依赖健康检查接口。实现必须自行捕获异常并返回确定的 HealthCheckResult，不得向上抛。 */
public interface HealthCheck {

    /** 依赖名称（稳定、可读，用于展示与排序）。 */
    String name();

    /** 该依赖不可用是否直接影响核心服务能力。 */
    boolean affectsCoreService();

    /** 执行一次健康检查。异常必须在实现内部隔离并转换为 DOWN/UNKNOWN。 */
    HealthCheckResult check();
}
