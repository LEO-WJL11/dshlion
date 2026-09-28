package com.lioncode.web.controller;

import com.lioncode.core.sound.SoundNotifier;
import com.lioncode.model.config.AppConfigStore;
import com.lioncode.web.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 音效提醒配置 / 试听接口
 *
 * 设置界面里那个「音效提醒」页签就是调这三个接口：
 *   GET  /api/notification                 读当前配置
 *   POST /api/notification                 存配置（改一下存一下，立即生效）
 *   POST /api/notification/test?kind=xxx   试听
 *
 * 试听走的是**后端真实播放**（和实际提醒完全同一条路径），
 * 而不是浏览器 &lt;audio&gt; 放一遍——否则听到的效果和真实提醒不是一回事，
 * 用户调完音量会发现"试听挺响、真提醒没声"。
 */
@RestController
@RequestMapping("/api/notification")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    /** 允许通过接口修改的开关字段 */
    private static final String[] BOOL_FIELDS =
        {"enabled", "soundDone", "soundApproval", "soundQuestion", "soundError"};

    private final AppConfigStore configStore;
    private final SoundNotifier soundNotifier;

    public NotificationController(AppConfigStore configStore, SoundNotifier soundNotifier) {
        this.configStore = configStore;
        this.soundNotifier = soundNotifier;
    }

    /**
     * 读当前音效提醒配置（缺失的字段补默认值）
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> get() {
        return ApiResponse.ok(SoundNotifier.withDefaults(
            configStore.getMap(SoundNotifier.CONFIG_KEY)));
    }

    /**
     * 保存配置。
     *
     * 只认已知字段，未知字段直接忽略——前端多传东西也不会把配置写脏。
     * 传哪个字段就改哪个，没传的保持原值（前端可以只提交一个开关）。
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> save(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> current = new LinkedHashMap<>(SoundNotifier.withDefaults(
            configStore.getMap(SoundNotifier.CONFIG_KEY)));

        if (body != null) {
            for (String key : BOOL_FIELDS) {
                if (body.containsKey(key)) {
                    current.put(key, SoundNotifier.bool(body, key,
                        Boolean.TRUE.equals(current.get(key))));
                }
            }
            if (body.containsKey("volume")) {
                current.put("volume", SoundNotifier.clamp(
                    SoundNotifier.intOf(body, "volume",
                        SoundNotifier.intOf(current, "volume", SoundNotifier.DEFAULT_VOLUME)),
                    0, 100));
            }
            if (body.containsKey("minIntervalMs")) {
                current.put("minIntervalMs", Math.max(0, SoundNotifier.intOf(body, "minIntervalMs",
                    SoundNotifier.intOf(current, "minIntervalMs", SoundNotifier.DEFAULT_MIN_INTERVAL_MS))));
            }
        }

        configStore.set(SoundNotifier.CONFIG_KEY, current);
        log.info("音效提醒配置已保存: enabled={}, done={}, approval={}, question={}, error={}, volume={}",
            current.get("enabled"), current.get("soundDone"), current.get("soundApproval"),
            current.get("soundQuestion"), current.get("soundError"), current.get("volume"));
        return ApiResponse.ok("已保存", current);
    }

    /**
     * 试听：绕过节流和开关直接播一次（允许连点），音量仍按配置走。
     *
     * @param kind done / approval / error
     */
    @PostMapping("/test")
    public ApiResponse<String> test(@RequestParam("kind") String kind) {
        SoundNotifier.Kind k = parse(kind);
        if (k == null) {
            return ApiResponse.error("未知的音效类型: " + kind + "（可选 done / approval / error）");
        }
        int volume = SoundNotifier.intOf(
            SoundNotifier.withDefaults(configStore.getMap(SoundNotifier.CONFIG_KEY)),
            "volume", SoundNotifier.DEFAULT_VOLUME);
        soundNotifier.preview(k);
        if (volume <= 0) {
            return ApiResponse.ok("音量是 0，听不到声音——把音量调大再试", k.name());
        }
        return ApiResponse.ok("已播放 " + k.name().toLowerCase() + " 音效", k.name());
    }

    private SoundNotifier.Kind parse(String kind) {
        if (kind == null) {
            return null;
        }
        try {
            return SoundNotifier.Kind.valueOf(kind.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
