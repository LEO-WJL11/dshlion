package com.lioncode.model.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 应用配置存储（持久化）
 * 
 * 持久化用户级配置：API提供商（baseUrl/apiKey）、适配器配置、
 * 当前激活的适配器、选中的模型与思考等级。
 * 
 * 存储位置: {user.home}/.lioncode/app-config.json
 * 每次修改立即落盘，应用重启后自动加载。
 */
@Component
public class AppConfigStore {

    private static final Logger log = LoggerFactory.getLogger(AppConfigStore.class);
    private static final ObjectMapper mapper = createMapper();

    private final Map<String, Object> config = new ConcurrentHashMap<>();
    private Path configFile;

    private static ObjectMapper createMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        return m;
    }

    @PostConstruct
    public void init() {
        configFile = Path.of(System.getProperty("user.home"), ".lioncode", "app-config.json");
        try {
            if (Files.exists(configFile)) {
                String json = Files.readString(configFile);
                Map<String, Object> loaded = mapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
                if (loaded != null) {
                    config.putAll(loaded);
                }
                log.info("应用配置已从磁盘加载: {} ({}项)", configFile, config.size());
            } else {
                log.info("未找到配置文件，使用默认配置: {}", configFile);
            }
        } catch (IOException e) {
            log.error("加载应用配置失败: {}", configFile, e);
        }
    }

    /**
     * 获取配置项
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object value = config.get(key);
        return value != null ? (T) value : defaultValue;
    }

    /**
     * 获取配置项（Map类型）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String key) {
        Object value = config.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    /**
     * 设置配置项并立即持久化
     */
    public synchronized void set(String key, Object value) {
        if (value == null) {
            config.remove(key);
        } else {
            config.put(key, value);
        }
        saveToDisk();
    }

    /**
     * 批量更新配置并立即持久化
     */
    public synchronized void update(Map<String, Object> updates) {
        config.putAll(updates);
        saveToDisk();
    }

    /**
     * 完整配置快照
     */
    public Map<String, Object> snapshot() {
        return new HashMap<>(config);
    }

    /**
     * 持久化到磁盘
     */
    private void saveToDisk() {
        try {
            if (configFile.getParent() != null) {
                Files.createDirectories(configFile.getParent());
            }
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            Files.writeString(configFile, json);
            log.debug("应用配置已保存: {}", configFile);
        } catch (IOException e) {
            log.error("保存应用配置失败: {}", configFile, e);
        }
    }
}
