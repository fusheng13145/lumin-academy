package com.leyon.lumin.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {

    /**
     * AI 运行专用线程池。生成可能持续数分钟，不能占用 Web 请求线程；
     * 固定小池 + 有界队列由调用方控制并发，后续可替换为消息队列 worker。
     */
    @Bean(name = "aiWorkflowExecutor", destroyMethod = "shutdown")
    public ExecutorService aiWorkflowExecutor() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "lumin-ai-workflow-" + sequence.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
        return Executors.newFixedThreadPool(2, factory);
    }
}
