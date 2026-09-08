package com.lioncode.core.plugin;

import com.lioncode.core.plugin.skill.SkillPlugin;
import com.lioncode.core.plugin.tool.ToolPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 插件加载器
 * 
 * 负责插件的发现、加载和生命周期管理：
 * - 启动时扫描插件目录（lion.plugin.scan-path）下的JAR包
 * - 通过独立URLClassLoader加载插件类（与主应用类加载器隔离，支持热重载）
 * - 识别实现了 ToolPlugin / SkillPlugin 接口的类并注册到PluginRegistry
 * - 提供手动扫描接口（POST /api/plugins/scan）实现"放JAR进目录→扫描→生效"
 */
@Component
public class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    private final PluginRegistry pluginRegistry;

    @Value("${lion.plugin.scan-path:}")
    private String scanPath;

    @Value("${lion.plugin.hot-reload:true}")
    private boolean hotReloadEnabled;

    /** 已加载的插件类加载器（保持引用防止被GC回收） */
    private final List<ClassLoader> activeClassLoaders = new ArrayList<>();

    public PluginLoader(PluginRegistry pluginRegistry) {
        this.pluginRegistry = pluginRegistry;
    }

    @PostConstruct
    public void init() {
        log.info("插件加载器已初始化，热加载: {}", hotReloadEnabled);
        if (scanPath != null && !scanPath.isBlank()) {
            log.info("插件扫描路径: {}", scanPath);
            // 启动时自动扫描一次磁盘插件
            scanAndLoad();
        }
    }

    /**
     * 扫描插件目录：加载所有JAR中实现插件接口的类
     * 
     * @return 扫描报告
     */
    public synchronized ScanReport scanAndLoad() {
        List<String> errors = new ArrayList<>();
        if (scanPath == null || scanPath.isBlank()) {
            errors.add("未配置插件扫描路径 lion.plugin.scan-path");
            return new ScanReport(0, errors);
        }

        Path dir = Path.of(scanPath);
        if (!Files.isDirectory(dir)) {
            log.warn("插件扫描路径不存在: {}", dir);
            errors.add("插件扫描路径不存在: " + dir);
            return new ScanReport(0, errors);
        }

        int totalLoaded = 0;
        try (var jarStream = Files.list(dir)) {
            List<Path> jars = jarStream
                .filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                .sorted()
                .toList();

            for (Path jar : jars) {
                totalLoaded += loadJar(jar, errors);
            }
        } catch (IOException e) {
            log.error("扫描插件目录失败: {}", dir, e);
            errors.add("扫描失败: " + e.getMessage());
        }

        log.info("插件扫描完成: 加载 {} 个插件（{} 个问题）", totalLoaded, errors.size());
        return new ScanReport(totalLoaded, errors);
    }

    /**
     * 加载单个JAR：枚举类并实例化实现插件接口的类
     * 
     * @return 成功加载的插件数量
     */
    private int loadJar(Path jar, List<String> errors) {
        log.info("加载插件JAR: {}", jar.getFileName());
        int loaded = 0;
        try {
            URLClassLoader loader = new URLClassLoader(
                new URL[]{ jar.toUri().toURL() }, getClass().getClassLoader());

            List<String> classNames = new ArrayList<>();
            try (JarFile jarFile = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.endsWith(".class") && !name.contains("META-INF")) {
                        classNames.add(name.replace('/', '.').replace(".class", ""));
                    }
                }
            }

            for (String className : classNames) {
                try {
                    Class<?> clazz = Class.forName(className, false, loader);
                    if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
                        continue;
                    }
                    if (ToolPlugin.class.isAssignableFrom(clazz)
                            || SkillPlugin.class.isAssignableFrom(clazz)) {
                        Object instance = clazz.getDeclaredConstructor().newInstance();
                        Plugin plugin = (Plugin) instance;
                        if (pluginRegistry.isRegistered(plugin.getId())) {
                            log.warn("插件ID冲突，跳过: {} (来源: {})", plugin.getId(), jar.getFileName());
                            errors.add("ID冲突: " + plugin.getId());
                            continue;
                        }
                        pluginRegistry.register(plugin);
                        loaded++;
                    }
                } catch (Throwable e) {
                    log.warn("加载插件类失败: {} (来源: {})", className, jar.getFileName(), e);
                    errors.add("类加载失败: " + className + " - " + e.getMessage());
                }
            }

            activeClassLoaders.add(loader);
        } catch (IOException e) {
            log.error("读取插件JAR失败: {}", jar.getFileName(), e);
            errors.add("JAR读取失败: " + jar.getFileName());
        }
        return loaded;
    }

    /**
     * 手动加载插件
     */
    public boolean loadPlugin(Plugin plugin) {
        if (pluginRegistry.isRegistered(plugin.getId())) {
            log.warn("插件已存在: {}", plugin.getId());
            return false;
        }
        pluginRegistry.register(plugin);
        return true;
    }

    /**
     * 手动卸载插件
     */
    public boolean unloadPlugin(String pluginId) {
        return pluginRegistry.unregister(pluginId);
    }

    /**
     * 热重载插件（重注册新实例）
     */
    public boolean reloadPlugin(String pluginId, Plugin newVersion) {
        if (!hotReloadEnabled) {
            log.warn("热加载已禁用");
            return false;
        }
        pluginRegistry.unregister(pluginId);
        pluginRegistry.register(newVersion);
        log.info("插件已热重载: {}", pluginId);
        return true;
    }

    /**
     * 批量加载插件
     */
    public int loadPlugins(List<Plugin> plugins) {
        int loaded = 0;
        for (Plugin plugin : plugins) {
            if (loadPlugin(plugin)) {
                loaded++;
            }
        }
        log.info("批量加载完成: {}/{}", loaded, plugins.size());
        return loaded;
    }

    /**
     * 获取已加载插件数量
     */
    public int getLoadedCount() {
        return pluginRegistry.size();
    }

    /**
     * 检查热加载是否启用
     */
    public boolean isHotReloadEnabled() {
        return hotReloadEnabled;
    }

    /**
     * 扫描报告
     */
    public record ScanReport(int loadedCount, List<String> errors) {
        public int errorCount() {
            return errors == null ? 0 : errors.size();
        }
    }
}
