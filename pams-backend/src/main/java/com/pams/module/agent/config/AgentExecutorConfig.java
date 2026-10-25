package com.pams.module.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Agent 专属线程池（业务设计方案 D1）。
 *
 * <p><b>为什么不用 {@code @Async} 默认线程池</b>：会丢 SecurityContext 且难观测。
 * 为 Agent 建独立、命名的线程池，便于限流与指标采集。
 *
 * <p><b>为什么必须包装 SecurityContext</b>：{@code ActivityService.currentUserId()} 与
 * {@code SigninRosterService} 都从 {@code SecurityContextHolder} 取当前用户（用于记录操作人）。
 * 异步任务里若未传播，{@code currentUserId()} 返回 {@code null}，
 * <b>审计字段会静默变空，且不报错</b>（D11 第三类变更的真实例子之一）。
 */
@Slf4j
@Configuration
public class AgentExecutorConfig {

    public static final String EXECUTOR_NAME = "agentExecutor";

    @Bean(name = EXECUTOR_NAME)
    public Executor agentExecutor(AgentProperties props) {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(props.getExecutorCoreSize());
        pool.setMaxPoolSize(props.getExecutorMaxSize());
        pool.setQueueCapacity(props.getExecutorQueueCapacity());
        pool.setThreadNamePrefix("agent-");
        pool.setTaskDecorator(task -> {
            Long captured = com.pams.common.OrgContext.get();
            var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            Long tenant = captured != null ? captured : authentication != null && authentication.getPrincipal() instanceof com.pams.security.LoginUser u ? u.getOrgId() : null;
            if (tenant == null) throw new IllegalStateException("异步业务任务缺少可信组织上下文");
            return () -> {
                Long previous = com.pams.common.OrgContext.get();
                try {
                    com.pams.common.OrgContext.set(tenant);
                    task.run();
                } finally {
                    if (previous == null) com.pams.common.OrgContext.clear(); else com.pams.common.OrgContext.set(previous);
                }
            };
        });
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(20);
        pool.initialize();
        // 关键：包装 SecurityContext，否则异步工具里 currentUserId() 静默为 null
        return new ManagedAgentExecutor(pool);
    }

    /** Spring must own the wrapped pool lifecycle as well as its security context wrapper. */
    public static final class ManagedAgentExecutor extends DelegatingSecurityContextAsyncTaskExecutor implements org.springframework.beans.factory.DisposableBean {
        private final ThreadPoolTaskExecutor pool;
        ManagedAgentExecutor(ThreadPoolTaskExecutor pool) {super(pool);this.pool=pool;}
        @Override public void destroy() {pool.shutdown();}
    }

    /** 供不希望依赖 Spring 类型场景使用的包装工具 */
    public static Executor wrap(Executor delegate) {
        return new DelegatingSecurityContextExecutor(delegate);
    }
}
