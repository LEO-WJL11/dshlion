package com.lioncode.jetbrains;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * LionBox Tool Window 的内容：用 JCEF 内嵌浏览器加载本地 Web UI。
 *
 * <p>关键点（均出自官方 Embedded Browser (JCEF) 文档）：
 * <ul>
 *   <li>用之前必须 {@link JBCefApp#isSupported()} 判断，JCEF 在部分 JetBrains Runtime 下不可用；</li>
 *   <li>{@code new JBCefBrowser()} 创建浏览器，{@link JBCefBrowser#getComponent()} 拿到 Swing 组件；</li>
 *   <li>{@code browser.loadURL(...)} 可在 EDT 或后台线程调用；</li>
 *   <li>{@link JBCefBrowser} 实现了 {@code Disposable}，必须用 {@link Disposer#dispose} 释放。</li>
 * </ul>
 *
 * <p>后端未就绪时先显示"等待中"卡片并轮询 {@code /api/runtime/mode}，
 * 收到任何 HTTP 响应就切换到浏览器并加载页面。
 *
 * <p>官方文档：<a href="https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html">Embedded Browser (JCEF)</a>
 */
public final class LionBoxPanel implements Disposable {

    /** 可用 JVM 参数 -Dlionbox.baseUrl=http://127.0.0.1:9090 覆盖。 */
    private static final String BASE_URL =
            System.getProperty("lionbox.baseUrl", "http://127.0.0.1:8080").replaceAll("/+$", "");
    private static final String HEALTH_PATH =
            System.getProperty("lionbox.healthPath", "/api/runtime/mode");
    private static final long WAIT_TIMEOUT_MS = 120_000L;
    private static final long POLL_INTERVAL_MS = 800L;
    private static final int PROBE_TIMEOUT_MS = 1_500;

    private static final String CARD_STATUS = "status";
    private static final String CARD_BROWSER = "browser";

    private final JPanel root = new JPanel(new CardLayout());
    private final JLabel statusLabel = new JLabel("", SwingConstants.CENTER);
    private final JButton retryButton = new JButton("重新检测");

    /** JCEF 不可用时为 null。 */
    private final @Nullable JBCefBrowser browser;

    private volatile boolean disposed;

    public LionBoxPanel() {
        root.add(buildStatusCard(), CARD_STATUS);

        JBCefBrowser created = null;
        if (JBCefApp.isSupported()) {
            created = new JBCefBrowser();
            root.add(created.getComponent(), CARD_BROWSER);
        }
        this.browser = created;

        if (created == null) {
            setStatus("当前 IDE 的运行时（JetBrains Runtime）不支持 JCEF 内嵌浏览器，"
                    + "无法显示 LionBox 界面。请在 Help | Find Action 里检查 JCEF 相关设置，"
                    + "或改用浏览器直接访问 " + BASE_URL, false);
            return;
        }
        startWaitingForBackend();
    }

    @NotNull
    public JComponent getComponent() {
        return root;
    }

    @Override
    public void dispose() {
        disposed = true;
        JBCefBrowser current = browser;
        if (current != null) {
            Disposer.dispose(current);
        }
    }

    // -----------------------------------------------------------------------
    // UI
    // -----------------------------------------------------------------------

    private @NotNull JComponent buildStatusCard() {
        JLabel title = new JLabel("LionBox", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel subtitle = new JLabel("Lion-Code Agent Harness", SwingConstants.CENTER);
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        statusLabel.setMaximumSize(new Dimension(460, 200));

        retryButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        retryButton.addActionListener(event -> startWaitingForBackend());

        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.add(title);
        column.add(Box.createVerticalStrut(4));
        column.add(subtitle);
        column.add(Box.createVerticalStrut(18));
        column.add(statusLabel);
        column.add(Box.createVerticalStrut(14));
        column.add(retryButton);

        JPanel wrapper = new JPanel(new GridBagLayout());
        wrapper.add(column, new GridBagConstraints());
        return wrapper;
    }

    private void showCard(@NotNull String card) {
        ((CardLayout) root.getLayout()).show(root, card);
    }

    private void setStatus(@NotNull String text, boolean showRetry) {
        statusLabel.setText("<html><div style='text-align:center;width:420px;'>"
                + escapeHtml(text).replace("\n", "<br/>") + "</div></html>");
        retryButton.setVisible(showRetry);
        root.revalidate();
        root.repaint();
    }

    private static @NotNull String escapeHtml(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // -----------------------------------------------------------------------
    // 后端就绪检测
    // -----------------------------------------------------------------------

    private void startWaitingForBackend() {
        if (disposed || browser == null) {
            return;
        }
        setStatus("正在等待本地服务 " + BASE_URL + " ...", false);
        showCard(CARD_STATUS);
        ApplicationManager.getApplication().executeOnPooledThread(this::waitForBackend);
    }

    private void waitForBackend() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1_000))
                .build();
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(BASE_URL + HEALTH_PATH))
                    .timeout(Duration.ofMillis(PROBE_TIMEOUT_MS))
                    .GET()
                    .build();
        } catch (RuntimeException e) {
            ApplicationManager.getApplication().invokeLater(
                    () -> setStatus("配置的地址不合法：" + BASE_URL, true));
            return;
        }

        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (!disposed && System.currentTimeMillis() < deadline) {
            try {
                // 收到任何 HTTP 响应（200/401/404…）都说明端口已监听 => 就绪
                client.send(request, HttpResponse.BodyHandlers.discarding());
                if (disposed) {
                    return;
                }
                ApplicationManager.getApplication().invokeLater(this::showBrowser);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // 连接被拒 / 超时：继续轮询
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (!disposed) {
            ApplicationManager.getApplication().invokeLater(() -> setStatus(
                    "等待超时：连不上 " + BASE_URL + "。\n请先启动 LionBox 后端"
                            + "（java -jar target/lion-code-agent-harness-1.0.0-SNAPSHOT.jar），"
                            + "然后点「重新检测」。", true));
        }
    }

    private void showBrowser() {
        if (disposed || browser == null) {
            return;
        }
        showCard(CARD_BROWSER);
        browser.loadURL(BASE_URL);
    }
}
