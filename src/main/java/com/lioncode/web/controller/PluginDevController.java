package com.lioncode.web.controller;

import com.lioncode.core.plugin.PluginPaths;
import com.lioncode.core.plugin.dev.PluginDevService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件开发模式的端点（开关、脚手架、SDK）。
 *
 * <p>【为什么和 PluginController 分开】那个类是"装了什么、开没开"的日常管理接口，
 * 前端随时在调；这里是开发者功能，只有开发模式开着才有意义。
 * 混在一起会让"改个终端超时"和"生成插件工程"这种完全不搭界的东西挤在一个文件里。</p>
 *
 * <p>端点全部挂在 {@code /api/plugins} 下（用户记一个前缀就够），
 * 具体路径用 {@code dev-mode}/{@code scaffold}/{@code dev}/{@code sdk} 这种**字面量**，
 * Spring 会优先匹配它们，不会和 {@code /{pluginId}} 撞上。</p>
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginDevController {

    private static final Logger log = LoggerFactory.getLogger(PluginDevController.class);

    private final PluginDevService devService;
    private final PluginPaths paths;

    public PluginDevController(PluginDevService devService, PluginPaths paths) {
        this.devService = devService;
        this.paths = paths;
    }

    /** 查开发模式状态 */
    @GetMapping("/dev-mode")
    public Map<String, Object> devMode() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("devMode", devService.isDevMode());
        out.put("pluginsDir", paths.pluginsDir().toString());
        out.put("devDir", paths.devDir().toString());
        out.put("hint", "开启方式：启动参数 --lionbox.plugin.dev-mode=true，"
            + "或 POST /api/plugins/dev-mode {\"enabled\": true}（会持久化）");
        return out;
    }

    /** 运行时开关（持久化，重启后仍按这次的选择） */
    @PostMapping("/dev-mode")
    public Map<String, Object> setDevMode(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("enabled");
        boolean enabled = raw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw));
        boolean actual = devService.setDevMode(enabled);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("devMode", actual);
        out.put("message", actual ? "插件开发模式已开启" : "插件开发模式已关闭");
        return out;
    }

    /**
     * 生成一个最小插件工程。
     *
     * <p>请求：{@code {"id": "demo.echo", "name": "回声示例", "kind": "BASE_TOOL"}}。
     * {@code kind} 支持 9 类里的任意一类（也认中文显示名）。
     * 生成到 {@code <插件目录>/dev/<id>/}，含源码、清单、build.ps1、README.md。</p>
     */
    @PostMapping("/scaffold")
    public Map<String, Object> scaffold(@RequestBody Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String id = body == null || body.get("id") == null ? null : String.valueOf(body.get("id"));
            String name = body == null || body.get("name") == null ? null : String.valueOf(body.get("name"));
            String kind = body == null || body.get("kind") == null ? null : String.valueOf(body.get("kind"));
            PluginDevService.ScaffoldResult r = devService.scaffold(id, name, kind);
            out.put("ok", true);
            out.put("path", r.path().toString());
            out.put("files", r.files());
            out.put("sdkJar", r.sdkJar() == null ? null : r.sdkJar().toString());
            out.put("message", "插件工程已生成。编译：powershell -ExecutionPolicy Bypass -File build.ps1；"
                + "然后 POST /api/plugins/reload 让插件生效。");
            return out;
        } catch (IllegalArgumentException | IllegalStateException e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            return out;
        } catch (Exception e) {
            log.error("生成插件工程失败", e);
            out.put("ok", false);
            out.put("error", "生成失败: " + e);
            return out;
        }
    }

    /** 已经生成了哪些开发工程（界面上列出来，点一下能看路径） */
    @GetMapping("/dev")
    public Map<String, Object> devProjects() {
        List<Map<String, Object>> projects = new ArrayList<>();
        Path devDir = paths.devDir();
        if (Files.isDirectory(devDir)) {
            try (var list = Files.list(devDir)) {
                for (Path dir : list.filter(Files::isDirectory).sorted().toList()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", dir.getFileName().toString());
                    m.put("path", dir.toString());
                    m.put("built", Files.isRegularFile(
                        paths.pluginsDir().resolve(dir.getFileName().toString() + ".jar")));
                    projects.add(m);
                }
            } catch (IOException e) {
                log.warn("列开发工程失败: {}", e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("devMode", devService.isDevMode());
        out.put("devDir", devDir.toString());
        out.put("projects", projects);
        return out;
    }

    /**
     * 生成/复用插件 SDK jar（编译插件用的接口包）。
     *
     * <p>单独开这个端点是因为：用户可能删了工程但留着代码，
     * 换个机器编译时需要重新拿一份 SDK；build.ps1 的 {@code -Sdk} 也可以指到这里返回的路径。</p>
     */
    @PostMapping("/sdk")
    public Map<String, Object> buildSdk() {
        Path sdk = devService.ensureSdkJar();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", sdk != null);
        out.put("path", sdk == null ? null : sdk.toString());
        out.put("error", sdk == null ? "SDK 生成失败（详情见日志）" : null);
        return out;
    }
}
