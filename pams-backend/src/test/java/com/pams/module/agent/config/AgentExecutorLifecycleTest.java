package com.pams.module.agent.config;
import com.pams.common.OrgContext;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class AgentExecutorLifecycleTest {
    @Test void disposingTheSecurityWrapperShutsDownItsWorkerThreads() throws Exception {
        var props=new AgentProperties();props.setExecutorCoreSize(1);props.setExecutorMaxSize(1);
        var executor=new AgentExecutorConfig().agentExecutor(props);
        try {
            OrgContext.set(7L);var completed=new CountDownLatch(1);executor.execute(completed::countDown);
            assertThat(completed.await(2,TimeUnit.SECONDS)).isTrue();
        } finally {OrgContext.clear();((org.springframework.beans.factory.DisposableBean)executor).destroy();}
        var pool=(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor)org.springframework.test.util.ReflectionTestUtils.getField(executor,"pool");
        assertThat(pool.getThreadPoolExecutor().isShutdown()).isTrue();assertThat(pool.getThreadPoolExecutor().awaitTermination(2,TimeUnit.SECONDS)).isTrue();
    }
}
