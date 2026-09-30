package com.lioncode.core.plugin.skill;

import com.lioncode.core.agent.spi.AgentSpi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 技能注入扩展点：把"技能目录"和"被指定/命中的技能正文"塞进系统提示词。
 *
 * <p>【为什么要走 SPI 而不是改 AgentLoop】AgentLoop 是所有功能共用的主循环，
 * 谁都能改它就会天天冲突。AgentSpi 已经留好了 {@code extraSystemSections} 这个口子，
 * 技能只关心"往提示词里加几段话"，实现这一个方法就够了。</p>
 *
 * <p>注入三块，顺序按重要性排（模型只看前几屏，重要的必须在前）：
 * <ol>
 *   <li><b>本轮指定的技能</b>：用户用 {@code /api/skills/active} 钉住、或消息里写了
 *       {@code @skill:<id>} 的（后者在 ChatController 已经就地展开，这里管的是钉住的）——
 *       正文直接给全，不让模型再去调工具。</li>
 *   <li><b>关键词命中的技能</b>：只补 AgentLoop 老路径覆盖不到的那些（用户自己加的技能）。
 *       四个内置技能由 AgentLoop 里那份老 {@code buildSkillPrompt()} 注入，这里再来一遍
 *       就是同一段话出现两遍，白烧 token。</li>
 *   <li><b>技能目录</b>：一行一个，告诉模型"还有哪些技能、什么时候用、要用就先 skill_load"。</li>
 * </ol>
 */
@Component
public class SkillCatalogSpi implements AgentSpi {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalogSpi.class);

    /** 单份技能正文注入上限：用户技能写失控（塞进去一本书）时不能让每条消息都爆掉。 */
    private static final int MAX_BODY_CHARS = 8000;

    private final SkillRepository repository;

    /** 取插件开关。用 ObjectProvider 懒取，避免和 PluginSettings 形成构造期循环依赖。 */
    private final org.springframework.beans.factory.ObjectProvider<com.lioncode.core.plugin.PluginSettings> settingsProvider;

    /** 取插件注册表（要看 kind=SKILL 的插件都关没关）。 */
    private final org.springframework.beans.factory.ObjectProvider<com.lioncode.core.plugin.PluginRegistry> registryProvider;

    public SkillCatalogSpi(SkillRepository repository,
                           org.springframework.beans.factory.ObjectProvider<com.lioncode.core.plugin.PluginSettings> settingsProvider,
                           org.springframework.beans.factory.ObjectProvider<com.lioncode.core.plugin.PluginRegistry> registryProvider) {
        this.repository = repository;
        this.settingsProvider = settingsProvider;
        this.registryProvider = registryProvider;
    }

    /**
     * 技能系统本身是不是被用户关掉了。
     *
     * <p>判定：只要有**任何一个** kind=SKILL 的插件还开着，就算开着。
     * 四个内置技能随便关掉一个不影响别的；全关掉（或在设置里关掉技能系统插件）就不再注入提示词。
     */
    private boolean skillSystemEnabled() {
        try {
            var settings = settingsProvider.getIfAvailable();
            var registry = registryProvider.getIfAvailable();
            if (settings == null || registry == null) {
                return true;   // 还没起来：按开着处理，别把功能误关
            }
            boolean any = false;
            for (var p : registry.getByKind(com.lioncode.core.plugin.PluginKind.SKILL)) {
                if (p.getKind() == com.lioncode.core.plugin.PluginKind.SKILL) {
                    if (settings.isEnabled(p)) {
                        any = true;
                        break;
                    }
                }
            }
            return any;
        } catch (Exception e) {
            log.debug("查技能插件开关失败，按开着处理: {}", e.toString());
            return true;
        }
    }

    @PostConstruct
    public void register() {
        AgentSpi.register(this);
        log.info("技能目录注入已挂到 Agent 主循环（AgentSpi，order={}）", order());
    }

    @PreDestroy
    public void unregister() {
        AgentSpi.unregister(this);
    }

    @Override
    public String spiName() {
        return "技能目录注入";
    }

    @Override
    public int order() {
        // 排在后面（默认 100）：技能是"可选能力"，不能挤掉前面的硬性格式约定
        return 200;
    }

    @Override
    public List<String> extraSystemSections(String sessionId, String workspacePath, String userMessage) {
        List<String> sections = new ArrayList<>();

        // 用户在设置里把"技能"这类插件关掉之后，提示词里就不该再出现技能目录/正文 ——
        // 否则开关只影响了界面上那个列表，模型照旧按技能办事，"关掉"是假的。
        // （plugin-core 的 PluginGateSpi 只把关掉的插件提供的**工具**摘掉，摘不掉提示词段落，
        //  所以这里必须自己查一次开关。）
        if (!skillSystemEnabled()) {
            return sections;
        }

        Set<String> alreadyGiven = new LinkedHashSet<>();

        // ---- 1) 钉住的技能：正文全给，模型不用再去 load ----
        List<SkillDefinition> pinned = repository.activeDefinitions(sessionId);
        if (!pinned.isEmpty()) {
            StringBuilder sb = new StringBuilder("## 本轮指定技能（用户指定，必须遵守）\n");
            for (SkillDefinition d : pinned) {
                sb.append("### ").append(d.id()).append(" — ").append(d.displayName()).append("\n");
                sb.append(cap(d.body())).append("\n\n");
                alreadyGiven.add(d.id());
            }
            sections.add(sb.toString());
        }

        // ---- 2) 关键词命中的技能（只补老路径漏掉的）----
        List<SkillDefinition> matched = new ArrayList<>();
        for (SkillDefinition d : repository.enabled()) {
            if (alreadyGiven.contains(d.id())) {
                continue;
            }
            // 内置四件套由 AgentLoop.buildSkillPrompt() 按同样的关键词规则注入，这里跳过。
            // 【注意】哪天 AgentLoop 里那份老实现删掉了，把下面这行去掉即可由本类接管。
            if (SkillDefinition.isLegacy(d.id())) {
                continue;
            }
            if (d.matchesKeywords(userMessage)) {
                matched.add(d);
            }
        }
        if (!matched.isEmpty()) {
            StringBuilder sb = new StringBuilder("## 关键词命中的技能\n");
            for (SkillDefinition d : matched) {
                sb.append("### ").append(d.id()).append(" — ").append(d.displayName()).append("\n");
                sb.append(cap(d.body())).append("\n\n");
            }
            sections.add(sb.toString());
        }

        // ---- 3) 技能目录 ----
        String catalog = repository.catalogText();
        if (!catalog.isBlank()) {
            sections.add(catalog);
        }
        return sections;
    }

    /** 正文超长时截断并说明 —— 比整段丢掉好（模型至少知道有这个技能、可以用 read_file 读全文）。 */
    private static String cap(String body) {
        if (body == null) {
            return "";
        }
        if (body.length() <= MAX_BODY_CHARS) {
            return body.trim();
        }
        return body.substring(0, MAX_BODY_CHARS)
            + "\n\n（技能正文过长已截断，共 " + body.length() + " 字；完整内容请用 read_file 打开技能文件）";
    }
}
