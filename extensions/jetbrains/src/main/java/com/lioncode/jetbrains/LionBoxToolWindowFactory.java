package com.lioncode.jetbrains;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

/**
 * LionBox Tool Window 工厂。
 *
 * <p>声明式注册（见 plugin.xml 的 {@code com.intellij.toolWindow} 扩展点）：只有当用户
 * 真正点开 Tool Window 时，平台才会调用 {@link #createToolWindowContent}，
 * 所以未使用插件不会带来启动开销。
 *
 * <p>官方文档：
 * <ul>
 *   <li><a href="https://plugins.jetbrains.com/docs/intellij/tool-windows.html">Tool Windows</a></li>
 * </ul>
 */
public final class LionBoxToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        LionBoxPanel panel = new LionBoxPanel();
        // 官方推荐写法：ContentManager.getFactory().createContent(...)
        Content content = toolWindow.getContentManager()
                .getFactory()
                .createContent(panel.getComponent(), "LionBox", false);
        // 面板持有 JBCefBrowser，必须随 Content 一起释放
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
