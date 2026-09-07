package com.lioncode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Lion-Code Agent Harness 主启动类
 * 
 * 本地Agent运行时框架，超越DeepSeek-Harness。
 * 核心理念：一切皆插件、事件溯源、流式工具执行。
 */
@SpringBootApplication
@EnableAsync
public class LionCodeApplication {

    private static final Logger log = LoggerFactory.getLogger(LionCodeApplication.class);

    public static void main(String[] args) {
        log.info("🦁 Lion-Code Agent Harness 正在启动...");
        SpringApplication.run(LionCodeApplication.class, args);
        log.info("🦁 Lion-Code Agent Harness 启动完成，监听端口: 8080");
    }
}
