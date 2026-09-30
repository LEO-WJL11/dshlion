package com.lioncode.core.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 插件目录的唯一定位处（插件 jar、设置文件、开发工程都在它下面）。
 *
 * <p>【为什么不能各写各的 {@code Path.of(user.home, ".lioncode")}】
 * 插件加载器、设置持久化、开发模式脚手架三处都要知道"插件目录在哪"。
 * 三份代码各算一次，迟早会出现"jar 放在 A、设置存到 B、界面显示的又是 C"这种事，
 * 而且定位规则一改就要改三遍。所以收口到这一个类。</p>
 *
 * <p>主目录的定位顺序（越靠前优先级越高）：</p>
 * <ol>
 *   <li>系统属性 {@code lionbox.plugins.dir} —— 便携版/测试用，指哪打哪；</li>
 *   <li><b>{@code {user.home}/.lioncode/plugins}</b> —— 跟 {@code AppConfigStore} 的
 *       {@code {user.home}/.lioncode/app-config.json} 同一套"用户配置目录"约定，
 *       而且这里是**进程启动时现算**的 {@code user.home}。</li>
 * </ol>
 *
 * <p>【为什么不直接用 application.yml 里的 {@code lion.plugin.scan-path} 当主目录】
 * 踩过的坑：pom 对 {@code src/main/resources} 开了 Maven 资源过滤（{@code filtering=true}），
 * 于是 {@code application.yml} 里的 {@code ${user.home}} 在**打包时**就被替换成了构建机的家目录，
 * 打进 jar 之后是死的（实测：打出来的 jar 里写着 {@code default-path: C:\Users\Leo/lion-code-workspace}）。
 * 用户机器上的家目录不是构建机那个，插件目录就会指到一个根本不存在的地方，
 * 表现是"我明明放了插件，列表里什么都没有"。
 * 所以主目录一律按运行时 {@code user.home} 现算。</p>
 *
 * <p>那个老配置项没有被丢掉：{@link #extraScanDirs()} 会把它当成**额外的扫描目录**，
 * 老用户放在那儿（application.yml 默认位置）的 jar 照样能被扫到。</p>
 *
 * <p>目录本身**不在这里创建**：只有真要写文件时才 mkdir。
 * 否则每次启动都会凭空多出一个空目录，用户会以为里面该有东西。</p>
 */
@Component
public class PluginPaths {

    private static final Logger log = LoggerFactory.getLogger(PluginPaths.class);

    @Value("${lion.plugin.scan-path:}")
    private String scanPath;

    /** 解析出来的插件根目录（懒解析一次并缓存：它在一个进程生命周期里不会变） */
    private volatile Path cached;

    /** 插件主目录：jar 放这儿，settings.json 和 dev/ 也在它下面 */
    public Path pluginsDir() {
        Path p = cached;
        if (p != null) {
            return p;
        }
        p = resolve();
        cached = p;
        log.info("插件目录: {}", p);
        for (Path extra : extraScanDirs()) {
            log.info("额外的插件扫描目录（lion.plugin.scan-path）: {}", extra);
        }
        return p;
    }

    private Path resolve() {
        String override = System.getProperty("lionbox.plugins.dir");
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim()).toAbsolutePath().normalize();
        }
        // 注意用 System.getProperty 现读，不经 Spring 占位符：
        // 占位符在打包时就被替换掉了（见类注释），那样算出来的路径是构建机的
        return Path.of(System.getProperty("user.home", "."), ".lioncode", "plugins")
            .toAbsolutePath().normalize();
    }

    /**
     * 额外要扫描的插件目录（老配置 {@code lion.plugin.scan-path}）。
     *
     * <p>只收"真的存在、且和主目录不是同一个"的目录：
     * 不存在就跳过（那只是构建时被写死的无效路径），
     * 不跳过的话每次启动都会报一条"目录不存在"，纯噪音。</p>
     */
    public java.util.List<Path> extraScanDirs() {
        java.util.List<Path> out = new java.util.ArrayList<>();
        if (scanPath == null || scanPath.isBlank()) {
            return out;
        }
        try {
            Path p = Path.of(scanPath.trim()).toAbsolutePath().normalize();
            if (!p.equals(pluginsDir()) && java.nio.file.Files.isDirectory(p)) {
                out.add(p);
            }
        } catch (Exception e) {
            log.debug("lion.plugin.scan-path 不是合法路径，忽略: {}", scanPath);
        }
        return out;
    }

    /** 全部要扫描的目录（主目录在前） */
    public java.util.List<Path> scanDirs() {
        java.util.List<Path> dirs = new java.util.ArrayList<>();
        dirs.add(pluginsDir());
        dirs.addAll(extraScanDirs());
        return dirs;
    }

    /**
     * 用户开关 + 各类插件参数都存这里。
     *
     * <p>放在插件目录里（而不是塞进 {@code app-config.json}）：用户要备份/迁移插件时，
     * 整个 {@code plugins} 目录拷走就带走了"装了哪些、开了哪些"，
     * 不需要再去另一个文件里挑键。</p>
     */
    public Path settingsFile() {
        return pluginsDir().resolve("settings.json");
    }

    /** 插件开发模式生成工程的地方（{@code plugins/dev/<插件id>/}） */
    public Path devDir() {
        return pluginsDir().resolve("dev");
    }

    /** 清掉缓存（测试/换目录时用） */
    void invalidate() {
        cached = null;
    }
}
