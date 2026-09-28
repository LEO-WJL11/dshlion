package com.lioncode.core.sound;

import com.lioncode.model.config.AppConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 音效提醒（任务完成 / 需要审批 / 任务出错）
 *
 * 为什么用 Java 自带的音频 API，而不是调外部程序：
 *   这件事我们在另一套 Node 版实现上踩过坑——用
 *   {@code spawn(powershell, ..., { detached: true })} 播声音，在 Windows 上那个子进程
 *   **根本不会执行**：不报错、不崩溃、pid 照常返回、日志还老老实实写着"播放成功"，
 *   结果一声都没有，排查了很久才定位。
 *   Java 在进程内播放完全没有这一类问题，也不用付进程启动的开销。
 *
 * 三条硬约束：
 *   1. **绝不抛异常**。这是锦上添花的功能，开关关着、文件缺了、声卡不支持、
 *      音频线路被占用……一律静静跳过，只记 debug 日志，绝不影响主流程。
 *   2. **绝不阻塞调用方**。AgentLoop 在跑主循环，播放必须丢到守护线程里异步出声
 *      （{@code Clip.start()} 而非等它播完）。
 *   3. **配置每次播放前读一次**，这样用户在设置里一改就立刻生效，不用重启。
 *
 * 配置存在 {@link AppConfigStore} 的 "notification" 键下：
 * <pre>
 * {
 *   "enabled": true,        // 总开关（默认开）
 *   "soundDone": true,      // 任务完成
 *   "soundApproval": true,  // 需要审批
 *   "soundQuestion": true,  // 模型要向你提问
 *   "soundError": true,     // 任务出错
 *   "volume": 80,           // 0~100，0 = 静音
 *   "minIntervalMs": 800    // 每种音效各自的最小间隔，防连响叠音
 * }
 * </pre>
 */
@Component
public class SoundNotifier {

    private static final Logger log = LoggerFactory.getLogger(SoundNotifier.class);

    /** 配置键 */
    public static final String CONFIG_KEY = "notification";

    /** 缺省值 */
    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_VOLUME = 80;
    public static final int DEFAULT_MIN_INTERVAL_MS = 800;

    /** 提示音种类 */
    public enum Kind {
        /** 任务完成 */
        DONE("done.wav", "soundDone"),
        /** 需要用户审批 */
        APPROVAL("approval.wav", "soundApproval"),
        /** 模型要向用户提问（ask_user 工具） */
        QUESTION("question.wav", "soundQuestion"),
        /** 任务出错 / 被中止 */
        ERROR("error.wav", "soundError");

        private final String fileName;
        private final String configKey;

        Kind(String fileName, String configKey) {
            this.fileName = fileName;
            this.configKey = configKey;
        }

        public String fileName() {
            return fileName;
        }

        /** 对应的配置开关字段 */
        public String configKey() {
            return configKey;
        }
    }

    private final AppConfigStore configStore;

    /** 每种音效上次播放的时间戳，用于节流 */
    private final Map<Kind, Long> lastPlayedAt = new ConcurrentHashMap<>();

    /** WAV 字节缓存：从 jar 里读一次就够了，不必每次播放都读 */
    private final Map<Kind, byte[]> soundCache = new EnumMap<>(Kind.class);

    /** 实际发起播放的次数（自测用） */
    private volatile long startedCount = 0L;

    public SoundNotifier(AppConfigStore configStore) {
        this.configStore = configStore;
    }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    /**
     * 播放提示音（AgentLoop 的触发点调用）。
     * 严格按配置：总开关、分开关、音量、节流都过一遍。
     *
     * @param kind 种类；为 null 时直接返回
     */
    public void play(Kind kind) {
        emit(kind, false);
    }

    /**
     * 试听（设置界面的"试听"按钮）。
     * 不过总开关也不过节流——用户点试听就是想马上听到，连点也得响；
     * 但仍然尊重音量（音量 0 就是不播）。
     *
     * @param kind 种类
     */
    public void preview(Kind kind) {
        emit(kind, true);
    }

    /** 总开关当前是否打开 */
    public boolean isEnabled() {
        return bool(config(), "enabled", DEFAULT_ENABLED);
    }

    /** 实际发起播放的次数（自测断言用） */
    public long startedCount() {
        return startedCount;
    }

    /**
     * 读某个音效的原始字节（自测断言用，顺便能确认资源真的打进包了）
     *
     * @param kind 种类
     * @return WAV 字节；资源缺失时返回 null
     */
    public byte[] soundBytes(Kind kind) {
        return kind == null ? null : loadSound(kind);
    }

    /**
     * 把用户存的配置补全成完整结构（GET /api/notification 用）
     *
     * @param raw AppConfigStore 里存的原始 map，可为 null
     * @return 补齐默认值后的配置
     */
    public static Map<String, Object> withDefaults(Map<String, Object> raw) {
        Map<String, Object> in = raw == null ? Map.of() : raw;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", bool(in, "enabled", DEFAULT_ENABLED));
        out.put("soundDone", bool(in, "soundDone", true));
        out.put("soundApproval", bool(in, "soundApproval", true));
        out.put("soundQuestion", bool(in, "soundQuestion", true));
        out.put("soundError", bool(in, "soundError", true));
        out.put("volume", clamp(intOf(in, "volume", DEFAULT_VOLUME), 0, 100));
        out.put("minIntervalMs", Math.max(0, intOf(in, "minIntervalMs", DEFAULT_MIN_INTERVAL_MS)));
        return out;
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 统一入口。
     *
     * @param kind    种类
     * @param preview true = 试听（跳过开关与节流）
     */
    private void emit(Kind kind, boolean preview) {
        if (kind == null) {
            return;
        }
        try {
            Map<String, Object> cfg = config();

            if (!preview) {
                if (!bool(cfg, "enabled", DEFAULT_ENABLED)) {
                    return;                                     // 总开关关着
                }
                if (!bool(cfg, kind.configKey(), true)) {
                    return;                                     // 这一类关着
                }
            }

            int volume = clamp(intOf(cfg, "volume", DEFAULT_VOLUME), 0, 100);
            if (volume <= 0) {
                return;                                         // 静音
            }

            if (!preview && !allowByThrottle(kind,
                    Math.max(0, intOf(cfg, "minIntervalMs", DEFAULT_MIN_INTERVAL_MS)))) {
                return;                                         // 太密了，跳过
            }

            byte[] wav = loadSound(kind);
            if (wav == null || wav.length == 0) {
                log.debug("提示音资源缺失，跳过: {}", kind.fileName());
                return;
            }

            startAsync(kind, wav, volume);
        } catch (Throwable t) {
            // 兜底：声音相关的一切问题都不该影响主流程
            log.debug("播放提示音失败（忽略）: {}", t.toString());
        }
    }

    /**
     * 节流：每种音效各自计时，距上次播放不足间隔就跳过。
     * 用 synchronized + 记时间戳，避免同一时刻并发触发时叠在一起响。
     */
    private synchronized boolean allowByThrottle(Kind kind, int minIntervalMs) {
        if (minIntervalMs <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long prev = lastPlayedAt.get(kind);
        if (prev != null && now - prev < minIntervalMs) {
            return false;
        }
        lastPlayedAt.put(kind, now);
        return true;
    }

    /**
     * 守护线程里异步播放。
     * {@code Clip.open()} 会把整个 WAV 读进内存缓冲，所以之后可以安心关掉输入流。
     */
    private void startAsync(Kind kind, byte[] wav, int volume) {
        Thread t = new Thread(() -> {
            Clip clip = null;
            try (AudioInputStream in =
                     AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
                clip = AudioSystem.getClip();
                clip.open(in);
                applyVolume(clip, volume);
                final Clip closing = clip;
                clip.addLineListener(e -> {
                    if (e.getType() == LineEvent.Type.STOP) {
                        try {
                            closing.close();                    // 播完立刻释放音频线路
                        } catch (Exception ignored) {
                            // 关不掉也无所谓，下一次会重新开一条
                        }
                    }
                });
                clip.start();                                   // 异步出声，不等它播完
                startedCount++;
            } catch (Exception e) {
                log.debug("音频线路不可用，跳过播放 {}: {}", kind.fileName(), e.getMessage());
                if (clip != null) {
                    try {
                        clip.close();
                    } catch (Exception ignored) {
                        // 已经关了或者开都没开成功
                    }
                }
            }
        }, "lion-sound");
        t.setDaemon(true);                                      // 绝不拖住 JVM 退出
        t.start();
    }

    /**
     * 音量：MASTER_GAIN 单位是分贝，gain = 20*log10(v)，v 取 0~1。
     * 注意必须先问 isControlSupported——很多声卡没有这个控制，
     * 直接 getControl 会抛 IllegalArgumentException。
     * 另外算出来的 dB 可能超出声卡支持范围，要夹一下。
     */
    private void applyVolume(Clip clip, int volume0to100) {
        try {
            if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                return;                                         // 不支持就按原始音量放
            }
            FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            float v = Math.min(1.0f, Math.max(0.0f, volume0to100 / 100.0f));
            float dB = (float) (20.0 * Math.log10(v));
            dB = Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB));
            gain.setValue(dB);
        } catch (Exception e) {
            log.debug("设置音量失败（按原音量播放）: {}", e.getMessage());
        }
    }

    /** 从 classpath 读 WAV 字节，读到的缓存起来 */
    private byte[] loadSound(Kind kind) {
        byte[] cached = soundCache.get(kind);
        if (cached != null) {
            return cached;
        }
        String path = "/static/sounds/" + kind.fileName();
        try (InputStream is = SoundNotifier.class.getResourceAsStream(path)) {
            if (is == null) {
                return null;
            }
            byte[] bytes = is.readAllBytes();
            if (bytes.length > 0) {
                soundCache.put(kind, bytes);
            }
            return bytes;
        } catch (Exception e) {
            log.debug("读取提示音资源失败: {} - {}", path, e.getMessage());
            return null;
        }
    }

    /** 读一次配置（AppConfigStore 本身是内存里的 map，开销可忽略） */
    private Map<String, Object> config() {
        Map<String, Object> raw = configStore.getMap(CONFIG_KEY);
        return raw == null ? Map.of() : raw;
    }

    // ------------------------------------------------------------------
    // 小工具（NotificationController 与自测共用）
    // ------------------------------------------------------------------

    /** 宽松读布尔：兼容存成 true / "true" 的情况（控制器与自测也用，故为 public） */
    public static boolean bool(Map<String, Object> cfg, String key, boolean def) {
        Object v = cfg.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return def;
    }

    /** 宽松读整数：兼容存成数字 / 字符串的情况 */
    public static int intOf(Map<String, Object> cfg, String key, int def) {
        Object v = cfg.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    /** 把值夹到 [min, max] */
    public static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
