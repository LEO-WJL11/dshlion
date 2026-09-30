package com.lioncode.core.plugin;

import com.lioncode.core.agent.spi.AgentSpi;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 把"用户在设置里关掉的插件"翻译成"下发给模型的工具清单里没有它"。
 *
 * <p>【为什么这是插件系统能不能用的关键一环】只把开关存进文件、界面上显示个灰点，
 * 那是假开关：模型照样会去调那个工具。真正生效必须**在提示词层面**消失 ——
 * 工具清单是模型唯一知道"自己会什么"的途径，清单里没有，它就压根不会去试，
 * 也就不会出现"调一次 ❌、再换个写法调一次 ❌"的烧轮次现象（这在本地模型上尤其致命）。</p>
 *
 * <p>它实现 {@link AgentSpi} 而不是改 AgentLoop：AgentLoop 是所有功能共用的核心循环，
 * 每个功能都去改它必然天天冲突。这个类只做一件事，注册进去就生效，卸载掉就恢复原样。</p>
 */
@Component
public class PluginGateSpi implements AgentSpi {

    private static final Logger log = LoggerFactory.getLogger(PluginGateSpi.class);

    private final PluginRegistry pluginRegistry;
    private final PluginSettings settings;

    public PluginGateSpi(PluginRegistry pluginRegistry, PluginSettings settings) {
        this.pluginRegistry = pluginRegistry;
        this.settings = settings;
    }

    @PostConstruct
    public void install() {
        AgentSpi.register(this);
        log.info("插件开关门禁已挂到 Agent 主循环（关掉的插件不会出现在工具清单里）");
    }

    @PreDestroy
    public void uninstall() {
        AgentSpi.unregister(this);
    }

    @Override
    public String spiName() {
        return "插件开关门禁";
    }

    /**
     * 排在最前面（数字最小）。
     *
     * <p>别的 SPI 拿到的是"已经按开关过滤过"的清单：比如技能插件要按工具清单决定
     * 自己适不适用，它应该看到过滤后的真实清单，而不是过滤前的。
     */
    @Override
    public int order() {
        return 10;
    }

    @Override
    public Set<String> filterToolNames(String sessionId, Set<String> all) {
        if (all == null || all.isEmpty()) {
            return all;
        }
        Map<String, String> nameToPlugin = pluginRegistry.toolNameToPluginId();
        Set<String> kept = new LinkedHashSet<>(all);
        Set<String> dropped = new LinkedHashSet<>();

        // 1) 终端插件：它不是工具本身，而是"一组 shell 工具 + 它们的限制"。
        //    它被关掉时，它管的这一组工具要一起摘掉（否则"关掉终端插件"就成了空话）。
        if (!isEnabled(TerminalPlugin.PLUGIN_ID)) {
            for (String owned : TerminalPlugin.OWNED_TOOL_NAMES) {
                if (kept.remove(owned)) {
                    dropped.add(owned);
                }
            }
        }

        // 2) 单个工具插件：关掉谁就摘掉谁贡献的工具
        for (String name : all) {
            String pluginId = nameToPlugin.get(name);
            if (pluginId == null || !kept.contains(name)) {
                continue;
            }
            if (!isEnabled(pluginId)) {
                kept.remove(name);
                dropped.add(name);
            }
        }

        if (!dropped.isEmpty()) {
            log.info("按插件开关摘掉 {} 个工具: {}", dropped.size(), dropped);
        }
        return kept;
    }

    /**
     * 这个插件现在是不是开着的。
     *
     * <p>查不到插件实例时按"开"处理：查不到只可能是注册表还没填好（启动瞬间），
     * 这时候把工具全砍掉，用户会看到"我的工具全没了"这种惊悚现象。
     */
    private boolean isEnabled(String pluginId) {
        return pluginRegistry.getById(pluginId)
            .map(settings::isEnabled)
            .orElse(true);
    }
}
