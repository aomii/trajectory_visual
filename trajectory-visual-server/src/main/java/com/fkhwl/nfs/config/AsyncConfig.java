package com.fkhwl.nfs.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 后台任务线程池：用于"全量轨迹压缩"异步任务（任务状态放内存，单线程跑，避免并发写 Mongo 冲突）。
 */
@Configuration
public class AsyncConfig {

    @Bean("trajectoryTaskExecutor")
    public ThreadPoolTaskExecutor trajectoryTaskExecutor(VisualProperties props) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(Math.max(1, props.getJob().getPoolSize()));
        ex.setMaxPoolSize(Math.max(1, props.getJob().getPoolSize()));
        ex.setQueueCapacity(2);
        ex.setThreadNamePrefix("trajectory-job-");
        ex.initialize();
        return ex;
    }
}
