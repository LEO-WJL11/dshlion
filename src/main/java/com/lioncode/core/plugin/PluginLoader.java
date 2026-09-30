package com.lioncode.core.plugin;

import com.lioncode.core.agent.spi.AgentSpi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 插件加载器（热插拔的落地部分）
 *
 * <p>职责：
 * <ul>
 *   <li>扫描插件目录（{@link PluginPaths}，默认就是 application.yml 里那个
 *       {@code lion.plugin.scan-path}）下的 {@code *.jar}；</li>
 *   <li>用**独立的 {@link URLClassLoader}** 加载，与主应用隔离 —— 这是"能卸载"的前提：
 *       同一个 ClassLoader 加载过的类是收不回来的，只有把 ClassLoader 连同插件一起丢掉，
 *       这个 jar 才真的从进程里消失（再次加载才拿得到新版本）；</li>
 *   <li>发现插件类的三种方式，从明确到粗暴：{@code META-INF/lionbox-plugin.properties}
 *       → {@code META-INF/services/...Plugin}（ServiceLoader）→ 扫 jar 里所有 class
 *       （兼容老插件包。最慢，但老 jar 里没有前两种清单，不能不认）；</li>
 *   <li>卸载：从 {@link PluginRegistry}、{@link AgentSpi} 里摘干净，再 {@code close()} 掉
 *       ClassLoader。</li>
 * </ul>
 *
 * <p><b>一条铁律：插件目录不存在 / 是空的 / 某个 jar 是坏的，都不能让应用起不来。</b>
 * 用户往目录里扔一个没编译完的 jar，最坏的结果应该只是"列表里多了一条红色错误"，
 * 而不是"软件打不开了"。所以这里所有入口都兜住了 Throwable。</p>
 */
@Component
public class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    /** 插件清单文件名：一行一个实现类名（也可以写成 plugin.class=xx.yy.Zz） */
    private static final String MANIFEST_ENTRY = "META-INF/lionbox-plugin.properties";
    /** ServiceLoader 约定路径（与 Plugin 接口对应） */
    private static final String SERVICE_ENTRY = "META-INF/services/com.lioncode.core.plugin.Plugin";

    private final PluginRegistry pluginRegistry;
    private final PluginPaths paths;

    @Value("${lion.plugin.hot-reload:true}")
    private boolean hotReloadEnabled;

    /** 已加载的 jar：jar 文件名 -> 状态（含 ClassLoader 引用，防被 GC 回收） */
    private final Map<String, LoadedJar> loadedJars = new ConcurrentHashMap<>();

    /** 插件ID -> 所属 jar 文件名（内置插件不在这个表里） */
    private final Map<String, String> pluginToJar = new ConcurrentHashMap<>();

    /** 加载失败记录（坏 jar 也要在界面上看得见） */
    private final List<PluginLoadFailure> failures = new CopyOnWriteArrayList<>();

    public PluginLoader(PluginRegistry pluginRegistry, PluginPaths paths) {
        this.pluginRegistry = pluginRegistry;
        this.paths = paths;
    }

    @PostConstruct
    public void init() {
        log.info("插件加载器已初始化，热加载: {}，插件目录: {}", hotReloadEnabled, paths.pluginsDir());
        try {
            scanAndLoad();
        } catch (Throwable t) {
            // 连扫描都炸了也不能拦启动
            log.error("启动时扫描插件目录失败（忽略，应用继续启动）", t);
            failures.add(new PluginLoadFailure("-", null, "扫描失败: " + t));
        }
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    /**
     * 扫描插件目录：加载所有 JAR 中实现插件接口的类
     *
     * <p>扫描范围 = 主插件目录（{@link PluginPaths#pluginsDir()}）+
     * 老配置 {@code lion.plugin.scan-path} 指向的目录（如果它真的存在）。
     * 两个目录的 jar 都按**绝对路径**登记，重名的 jar 不会互相顶掉。</p>
     *
     * @return 扫描报告
     */
    public synchronized ScanReport scanAndLoad() {
        List<String> errors = new ArrayList<>();
        int totalLoaded = 0;

        for (Path dir : paths.scanDirs()) {
            if (!Files.isDirectory(dir)) {
                // 【不是错误】出厂状态就是这样：还没人放过插件。不建目录、不报错，
                // 等真要写 jar/生成工程时再 mkdir。
                log.info("插件目录不存在，跳过扫描: {}", dir);
                continue;
            }
            try (var jarStream = Files.list(dir)) {
                List<Path> jars = jarStream
                    .filter(Files::isRegularFile)
                    // 只看目录**第一层**的 jar：dev/ 下面是我们生成、用户正在改的工程，
                    // 里面可能有半成品的 target/*.jar，绝不能顺手加载进来。
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted()
                    .toList();

                for (Path jar : jars) {
                    totalLoaded += loadJar(jar, errors);
                }
            } catch (IOException e) {
                log.error("扫描插件目录失败: {}", dir, e);
                errors.add("扫描失败 " + dir + ": " + e.getMessage());
            }
        }

        log.info("插件扫描完成: 加载 {} 个插件（{} 个问题）", totalLoaded, errors.size());
        return new ScanReport(totalLoaded, errors);
    }

    /**
     * 加载单个 JAR：找出里面的插件类并实例化注册
     *
     * @return 成功加载的插件数量
     */
    private int loadJar(Path jar, List<String> errors) {
        String jarKey = jar.toAbsolutePath().normalize().toString();
        String jarName = jar.getFileName().toString();
        if (loadedJars.containsKey(jarKey)) {
            log.debug("插件 JAR 已加载过，跳过: {}", jarName);
            return 0;
        }

        int loaded = 0;
        URLClassLoader loader = null;
        try {
            loader = new URLClassLoader(new URL[]{ jar.toUri().toURL() }, getClass().getClassLoader());
            List<String> classNames = discoverPluginClassNames(jar);

            if (classNames.isEmpty()) {
                // 不是错误，只是这个 jar 里没有插件（比如用户放了个依赖库）
                log.info("插件 JAR 中没有发现插件类: {}", jarName);
                loader.close();
                return 0;
            }

            List<String> registeredIds = new ArrayList<>();
            for (String className : classNames) {
                try {
                    Class<?> clazz = Class.forName(className, false, loader);
                    if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())
                            || !Plugin.class.isAssignableFrom(clazz)) {
                        continue;
                    }
                    Object instance = clazz.getDeclaredConstructor().newInstance();
                    Plugin plugin = (Plugin) instance;

                    if (pluginRegistry.isRegistered(plugin.getId())) {
                        String msg = "插件ID冲突，跳过: " + plugin.getId();
                        log.warn("{}（来源: {}）", msg, jarName);
                        errors.add(msg);
                        failures.add(new PluginLoadFailure(jarName, plugin.getId(), msg));
                        continue;
                    }

                    pluginRegistry.register(plugin);
                    registeredIds.add(plugin.getId());
                    pluginToJar.put(plugin.getId(), jarKey);
                    loaded++;

                    // 外置插件如果实现了 AgentSpi（比如自己写了个"大循环插件"），
                    // 顺手挂进 SPI 注册表；卸载时要记得摘掉，所以这里记名字。
                    if (instance instanceof AgentSpi spi) {
                        AgentSpi.register(spi);
                    }
                } catch (Throwable e) {
                    // 【一个类炸了不能带走整个 jar】其它插件类继续加载
                    String msg = "类加载失败: " + className + " - " + e;
                    log.warn("加载插件类失败: {} (来源: {})", className, jarName, e);
                    errors.add(msg);
                    failures.add(new PluginLoadFailure(jarName, className, msg));
                }
            }

            if (!registeredIds.isEmpty()) {
                loadedJars.put(jarKey, new LoadedJar(jarKey, jar, loader, registeredIds));
                log.info("插件 JAR 已加载: {}（{} 个插件）", jarName, registeredIds.size());
            } else {
                loader.close();
            }
            return loaded;
        } catch (Throwable e) {
            log.error("读取插件JAR失败（忽略这个 jar，应用继续）: {}", jarName, e);
            errors.add("JAR读取失败: " + jarName + " - " + e);
            failures.add(new PluginLoadFailure(jarName, null, "JAR读取失败: " + e));
            closeQuietly(loader);
            return 0;
        }
    }
    /**
     * 找出一个 jar 里"声明自己是插件"的类名。
     *
     * <p>三种来源依次尝试，取并集：声明式的清单最可靠（也是官方推荐的写法），
     * 老 jar 没有清单时退回扫 class（慢一点，但保证"照老规矩写的插件仍然能用"）。
     *
     * <p>【为什么不用 {@code ServiceLoader.load(...).iterator()} 直接拿实例】
     * 迭代 ServiceLoader 就等于把 provider **实例化一次**，后面注册时还要再实例化一次 ——
     * 构造里有副作用的插件（开文件、起线程）会被执行两遍。
     * 所以这里只读它同款的那个清单文件（{@code META-INF/services/...}）拿类名，
     * 实例化统一由 {@link #loadJar} 做一次，而且每个类单独 try/catch，坏一个不影响其余的。
     */
    private List<String> discoverPluginClassNames(Path jar) {
        LinkedHashSet<String> names = new LinkedHashSet<>();

        try (JarFile jarFile = new JarFile(jar.toFile())) {
            // 1) META-INF/lionbox-plugin.properties —— 推荐写法
            JarEntry entry = jarFile.getJarEntry(MANIFEST_ENTRY);
            if (entry != null) {
                Properties props = new Properties();
                try (InputStream in = jarFile.getInputStream(entry)) {
                    props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                }
                addAll(names, props.getProperty("plugin.class"));
                addAll(names, props.getProperty("plugin.classes"));
                addAll(names, props.getProperty("plugins"));
            }
            // 2) ServiceLoader 约定：META-INF/services/com.lioncode.core.plugin.Plugin
            JarEntry svc = jarFile.getJarEntry(SERVICE_ENTRY);
            if (svc != null) {
                try (InputStream in = jarFile.getInputStream(svc)) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    for (String line : text.split("\\R")) {
                        addAll(names, line);
                    }
                }
            }
            // 3) 兜底：老插件包没有上面两种清单，扫 class
            if (names.isEmpty()) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.endsWith(".class") && !name.startsWith("META-INF")) {
                        names.add(name.replace('/', '.').replace(".class", ""));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 jar 清单失败: " + e.getMessage(), e);
        }
        return new ArrayList<>(names);
    }

    /** 把一行（可能逗号/空格/分号分隔、可能带注释）里的类名拆出来 */
    private static void addAll(LinkedHashSet<String> out, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String part : raw.split("[,;\\s]+")) {
            String name = part.trim();
            if (!name.isEmpty() && !name.startsWith("#")) {
                out.add(name);
            }
        }
    }

    // ------------------------------------------------------------------
    // 卸载（热插拔的另一半）
    // ------------------------------------------------------------------

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
     * 卸载插件。
     *
     * <p>外置插件会连着它的 ClassLoader 一起丢掉（这样重新扫描才能拿到新版本）；
     * 内置插件只是从注册表里摘掉（它是 Spring 单例，卸载后要重启才能回来 —— 这一点
     * 通过 {@link Plugin#isHotReloadable()} 在界面上如实告诉用户）。
     */
    public synchronized boolean unloadPlugin(String pluginId) {
        String jarKey = pluginToJar.get(pluginId);
        if (jarKey != null) {
            return unloadJar(jarKey) > 0;
        }
        return pluginRegistry.unregister(pluginId);
    }

    /**
     * 卸载一整个 jar：它的全部插件、SPI 扩展都要摘掉，最后 close 掉 ClassLoader。
     *
     * @param jarKey jar 的绝对路径（{@link #loadedJarNames()} 里给的就是它）
     * @return 摘掉的插件个数
     */
    public synchronized int unloadJar(String jarKey) {
        LoadedJar state = loadedJars.remove(jarKey);
        if (state == null) {
            return 0;
        }
        int removed = 0;
        for (String id : state.pluginIds()) {
            Plugin p = pluginRegistry.getById(id).orElse(null);
            // SPI 摘除：外置插件贡献的 AgentSpi 如果不摘，卸载后还会继续影响 AgentLoop
            if (p instanceof AgentSpi spi) {
                AgentSpi.unregister(spi);
            }
            if (pluginRegistry.unregister(id)) {
                removed++;
            }
            pluginToJar.remove(id);
        }
        closeQuietly(state.loader());
        log.info("插件 JAR 已卸载: {}（摘掉 {} 个插件，ClassLoader 已关闭）",
            state.path().getFileName(), removed);
        return removed;
    }

    /** 卸载全部外置插件（reload 的第一步） */
    public synchronized int unloadAllExternal() {
        int removed = 0;
        for (String jarKey : new ArrayList<>(loadedJars.keySet())) {
            removed += unloadJar(jarKey);
        }
        return removed;
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
     * 重新扫描：先卸掉所有外置插件（含 ClassLoader），再从头扫一遍。
     *
     * <p>这就是"改了插件→重载→立刻生效"的按钮。内置插件不受影响。</p>
     *
     * @return 本次扫描后加载的插件数
     */
    public synchronized ScanReport reload() {
        unloadAllExternal();
        failures.clear();
        return scanAndLoad();
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

    /** 外置插件的来源信息（内置插件返回 null） */
    public ExternalOrigin origin(String pluginId) {
        String jarKey = pluginToJar.get(pluginId);
        if (jarKey == null) {
            return null;
        }
        LoadedJar state = loadedJars.get(jarKey);
        Path path = state == null ? Path.of(jarKey) : state.path();
        return new ExternalOrigin(path.getFileName().toString(), path);
    }

    /** 加载失败记录（REST 里以"坏插件"条目显示） */
    public List<PluginLoadFailure> getFailures() {
        return List.copyOf(failures);
    }

    /** 已经加载的外置 jar 名单（绝对路径，诊断用） */
    public List<String> loadedJarNames() {
        return List.copyOf(loadedJars.keySet());
    }

    private static void closeQuietly(URLClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException e) {
            log.debug("关闭插件 ClassLoader 失败（忽略）: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 结构
    // ------------------------------------------------------------------

    /** 一个已加载的 jar 及其贡献 */
    public record LoadedJar(String jarName, Path path, URLClassLoader loader, List<String> pluginIds) {}

    /** 外置插件的来源 */
    public record ExternalOrigin(String jarName, Path path) {}

    /** 某个 jar / 某个类加载失败 */
    public record PluginLoadFailure(String jar, String pluginId, String message) {}

    /**
     * 扫描报告
     */
    public record ScanReport(int loadedCount, List<String> errors) {
        public int errorCount() {
            return errors == null ? 0 : errors.size();
        }
    }
}
