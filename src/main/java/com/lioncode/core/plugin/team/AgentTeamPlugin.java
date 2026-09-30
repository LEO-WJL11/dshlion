package com.lioncode.core.plugin.team;

import com.lioncode.core.agent.AgentMode;
import com.lioncode.core.agent.spi.AgentSpi;
import com.lioncode.core.plugin.Plugin;
import com.lioncode.core.plugin.PluginKind;
import com.lioncode.core.plugin.PluginSettings;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 智能体团队插件：用户自己定义"有哪些智能体、各自用什么模式、负责干什么"。
 *
 * <p>一个团队就是一组 {@link TeamMember}。它做三件事：
 * <ol>
 *   <li>配置持久化（存在 settings.json 的 team.members 里，重启还在）；</li>
 *   <li>增删改查（REST 端点直接调这里，见 PluginController）；</li>
 *   <li>把团队说明注入系统提示词（{@link AgentSpi#extraSystemSections}）——
 *       否则"配置了团队"对模型来说是隐形的，等于没配。</li>
 * </ol>
 *
 * <p>【为什么提示词里只描述、不下发派发指令】本轮还没有"把任务交给某个成员"的工具
 * （那需要 Lead 在 AgentLoop 里接）。这时候如果在提示词里写"你可以把任务派给成员"，
 * 模型会去找一个不存在的工具、白烧轮次。所以这一节只说清"团队里有谁、各自擅长什么"，
 * 等派发工具上线后，Lead 在这里补一句工具名即可。</p>
 */
@Component
public class AgentTeamPlugin implements Plugin, AgentSpi {

    public static final String PLUGIN_ID = "plugin.agent-team";

    private static final Logger log = LoggerFactory.getLogger(AgentTeamPlugin.class);

    private final PluginSettings settings;

    public AgentTeamPlugin(PluginSettings settings) {
        this.settings = settings;
    }

    @PostConstruct
    public void install() {
        AgentSpi.register(this);
    }

    @PreDestroy
    public void uninstall() {
        AgentSpi.unregister(this);
    }

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "agent_team";
    }

    @Override
    public String getDisplayName() {
        return "智能体团队";
    }

    @Override
    public String getDescription() {
        return "用户自定义的智能体集群：每个智能体用什么模式（极简/标准）、负责干什么";
    }

    @Override
    public PluginType getType() {
        return PluginType.SYSTEM;
    }

    @Override
    public PluginKind getKind() {
        return PluginKind.AGENT_TEAM;
    }

    @Override
    public String spiName() {
        return "智能体团队插件";
    }

    /** 团队说明放在提示词靠后位置，别挤掉硬性格式约定 */
    @Override
    public int order() {
        return 200;
    }

    // ------------------------------------------------------------------
    // 增删改查
    // ------------------------------------------------------------------

    /** 全部成员（含被停用的：界面上要显示成灰的，删掉就看不见了） */
    public List<TeamMember> members() {
        List<TeamMember> out = new ArrayList<>();
        for (Map<String, Object> raw : settings.listOf("team", "members")) {
            TeamMember m = TeamMember.fromMap(raw);
            if (m != null) {
                out.add(m);
            }
        }
        return out;
    }

    /** 参与干活的成员（停用的不算） */
    public List<TeamMember> activeMembers() {
        return members().stream().filter(TeamMember::enabled).toList();
    }

    /**
     * 新增成员。
     *
     * <p>模式用 {@link AgentMode#fromName} 校验：写错模式名（比如 "fast"）应当当场报错，
     * 而不是先存下来、等到真的用它跑任务时才炸。PTC/CREATIVE 会被归一成 STANDARD
     * （跟全局的模式归一保持同一套规则，老配置不会因此失效）。</p>
     *
     * @throws IllegalArgumentException 模式名不认识
     */
    public TeamMember add(String id, String name, String mode, String role, String model,
                          Boolean enabled) {
        String normalizedMode;
        try {
            normalizedMode = AgentMode.fromName(mode).name();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的模式: " + mode + "（可选 MINIMAL / STANDARD）");
        }
        TeamMember created = TeamMember.create(id, name, normalizedMode, role, model, enabled);
        if (find(created.id()) != null) {
            throw new IllegalArgumentException("智能体ID已存在: " + created.id());
        }
        List<Map<String, Object>> list = new ArrayList<>(settings.listOf("team", "members"));
        list.add(created.toMap());
        settings.put("team", "members", list);
        log.info("智能体团队成员已新增: {}（{}）", created.name(), created.mode());
        return created;
    }

    /**
     * 部分更新：只改传进来的字段，其余保持原样。
     *
     * <p>为什么不做整体替换：前端"改个名字"只发 name，整体替换会把 role/model 抹掉 ——
     * 这种"改一处丢三处"的 bug 在设置页里特别容易发生，也特别难被发现。
     *
     * @return 更新后的成员；id 不存在返回 null
     */
    public TeamMember update(String id, Map<String, Object> patch) {
        TeamMember old = find(id);
        if (old == null) {
            return null;
        }
        String mode = old.mode();
        if (patch != null && patch.get("mode") != null) {
            try {
                mode = AgentMode.fromName(String.valueOf(patch.get("mode"))).name();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("不支持的模式: " + patch.get("mode")
                    + "（可选 MINIMAL / STANDARD）");
            }
        }
        TeamMember updated = new TeamMember(
            old.id(),
            pick(patch, "name", old.name()),
            mode,
            pick(patch, "role", old.role()),
            pick(patch, "model", old.model()),
            pickBool(patch, "enabled", old.enabled()));

        List<Map<String, Object>> list = new ArrayList<>(settings.listOf("team", "members"));
        for (int i = 0; i < list.size(); i++) {
            TeamMember m = TeamMember.fromMap(list.get(i));
            if (m != null && m.id().equals(id)) {
                list.set(i, updated.toMap());
            }
        }
        settings.put("team", "members", list);
        return updated;
    }

    /** 删除成员 */
    public boolean remove(String id) {
        List<Map<String, Object>> list = new ArrayList<>(settings.listOf("team", "members"));
        boolean removed = list.removeIf(raw -> {
            TeamMember m = TeamMember.fromMap(raw);
            return m != null && m.id().equals(id);
        });
        if (removed) {
            settings.put("team", "members", list);
        }
        return removed;
    }

    /** 按 id 找人 */
    public TeamMember find(String id) {
        if (id == null) {
            return null;
        }
        return members().stream().filter(m -> id.equals(m.id())).findFirst().orElse(null);
    }

    private static String pick(Map<String, Object> patch, String key, String fallback) {
        if (patch == null || !patch.containsKey(key) || patch.get(key) == null) {
            return fallback;
        }
        return String.valueOf(patch.get(key));
    }

    private static boolean pickBool(Map<String, Object> patch, String key, boolean fallback) {
        if (patch == null || !patch.containsKey(key) || patch.get(key) == null) {
            return fallback;
        }
        Object v = patch.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    // ------------------------------------------------------------------
    // 注入系统提示词
    // ------------------------------------------------------------------

    @Override
    public List<String> extraSystemSections(String sessionId, String workspacePath, String userMessage) {
        if (!settings.isEnabled(this)) {
            return List.of();
        }
        List<TeamMember> active = activeMembers();
        if (active.isEmpty()) {
            return List.of();   // 没配成员就一个字都不加，别给提示词添噪音
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 智能体团队\n");
        sb.append("用户为这个工作区配置了以下智能体分工，处理对应类型的任务时按它们各自的定位来做：\n");
        for (TeamMember m : active) {
            sb.append("- ").append(m.name())
              .append("（模式：").append("MINIMAL".equals(m.mode()) ? "极简" : "标准");
            if (!m.model().isBlank()) {
                sb.append("，模型：").append(m.model());
            }
            sb.append("）");
            if (!m.role().isBlank()) {
                sb.append("：").append(m.role());
            }
            sb.append("\n");
        }
        return List.of(sb.toString());
    }
}
