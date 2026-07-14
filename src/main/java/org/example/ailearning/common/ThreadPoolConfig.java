package org.example.ailearning.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class ThreadPoolConfig {
    @Bean
    public ThreadPoolExecutor getThreadPoolExecutor (){
        return new ThreadPoolExecutor(5,10,60,
                TimeUnit.SECONDS,new LinkedBlockingQueue<>(100), new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
