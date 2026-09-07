package com.lioncode.core.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务容器（依赖注入）
 * 
 * 提供插件间的服务注册和发现机制。
 * 插件可以注册自己的服务，其他插件可以获取并使用。
 * 实现核心runtime与业务插件代码的解耦。
 */
@Component
public class ServiceContainer {

    private static final Logger log = LoggerFactory.getLogger(ServiceContainer.class);

    /** 已注册服务：服务名 -> 服务实例 */
    private final Map<String, Object> services = new ConcurrentHashMap<>();

    /**
     * 注册服务
     */
    public <T> void registerService(Class<T> serviceType, T serviceInstance) {
        String key = serviceType.getName();
        services.put(key, serviceInstance);
        log.debug("服务已注册: {}", key);
    }

    /**
     * 注册命名服务
     */
    public <T> void registerService(String serviceName, T serviceInstance) {
        services.put(serviceName, serviceInstance);
        log.debug("命名服务已注册: {}", serviceName);
    }

    /**
     * 获取服务
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getService(Class<T> serviceType) {
        return Optional.ofNullable((T) services.get(serviceType.getName()));
    }

    /**
     * 获取命名服务
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getService(String serviceName) {
        return Optional.ofNullable((T) services.get(serviceName));
    }

    /**
     * 检查服务是否存在
     */
    public boolean hasService(Class<?> serviceType) {
        return services.containsKey(serviceType.getName());
    }

    /**
     * 移除服务
     */
    public void removeService(Class<?> serviceType) {
        services.remove(serviceType.getName());
        log.debug("服务已移除: {}", serviceType.getName());
    }

    /**
     * 获取所有已注册服务名
     */
    public java.util.Set<String> getRegisteredServices() {
        return java.util.Set.copyOf(services.keySet());
    }
}
