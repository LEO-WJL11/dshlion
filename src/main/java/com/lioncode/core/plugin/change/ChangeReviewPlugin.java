package com.lioncode.core.plugin.change;

import com.lioncode.core.plugin.Plugin;
import com.lioncode.core.plugin.PluginKind;
import org.springframework.stereotype.Component;

/**
 * 改动人工审核插件。
 *
 * <p>【它管什么】改文件的工具（write_file / modify_file / append_file / create_file / delete_file）
 * 不再一调就落盘，而是先把改动登记成一条待审记录；人在界面（WebUI 或 VS Code 侧栏）看到 diff，
 * 点「通过」才真写，点「打回」就把理由回给模型让它重写 —— 用户原话："人工去审核，审核通过了
 * 这个文件才会真正被使用，否则打回去重写，相当于作业。"</p>
 *
 * <p>真正干活的是 {@link com.lioncode.core.agent.change.ChangeReview}；这个类只是"让它在插件列表里
 * 有一个能被开关的条目"，符合"一切皆插件"的规矩：用户能在设置里关掉它（关掉即老行为：直接落盘）。</p>
 *
 * <p><b>默认开启</b>：这是用户明确要的默认行为（先审后用）。它和"自动授权审查插件"不是一回事 ——
 * 那个是动手**之前**让另一个模型判断该不该放行；这个是动完手**之后**、落盘之前等人点头。
 * 两个都开就是双保险：模型先审，人再终审。</p>
 */
@Component
public class ChangeReviewPlugin implements Plugin {

    public static final String PLUGIN_ID = "plugin.change-review";

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "change_review";
    }

    @Override
    public String getDisplayName() {
        return "改动人工审核";
    }

    @Override
    public String getDescription() {
        return "AI 改的每个文件先攒成待审改动，人在界面上点通过才真正落盘；打回则按理由重写";
    }

    @Override
    public PluginType getType() {
        return PluginType.SYSTEM;
    }

    @Override
    public PluginKind getKind() {
        return PluginKind.APPROVAL_REVIEW;
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }
}
