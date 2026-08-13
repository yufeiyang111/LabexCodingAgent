package com.labex.labexagent.run;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 为可长时间运行的审批恢复提供独立且有界的执行资源，避免阻塞 HTTP 与通用调度线程。 */
@Configuration
public class AgentRunExecutorConfiguration {
    public static final String NETWORK_RETRY_EXECUTOR = "agentNetworkRetryExecutor";
    public static final String TRANSIENT_EVENT_EXECUTOR = "agentTransientEventExecutor";

    @Bean(name = NETWORK_RETRY_EXECUTOR, destroyMethod = "shutdownNow")
    public ExecutorService agentNetworkRetryExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 4, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
                new NamedDaemonThreadFactory("labex-network-retry-"), new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @Bean(name = TRANSIENT_EVENT_EXECUTOR, destroyMethod = "shutdownNow")
    public ExecutorService agentTransientEventExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                0, 16, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
                new NamedDaemonThreadFactory("labex-transient-event-"), new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger();

        private NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
