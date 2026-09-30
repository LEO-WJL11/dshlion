# -*- coding: utf-8 -*-
"""
make_question_wav.py —— 生成「向你提问」的提示音 question.wav

和另外三个音效区分开：
  done.wav      上扬两声   "干完了"
  approval.wav  急促三连   "要你批"
  error.wav     短促单声   "出错了"
  question.wav  先升后悬   "问你个事"   ← 本脚本生成

做法：两声，第一声上扬、第二声停在偏高的位置不收尾（悬着），
听起来就是"疑问"的语气。基频压在 450~950Hz（笔记本小喇叭推得动），
加谐波和快速颤音做出"叽"的音色，峰值归一化到 88%。

只用标准库：wave + struct + math。
"""

import math
import os
import struct
import wave

RATE = 22050
PEAK = 0.88          # 目标峰值（相对满量程）
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   '..', 'src', 'main', 'resources', 'static', 'sounds', 'question.wav')

# 两个音：起始频率、结束频率、时长、起始时刻
NOTES = [
    (480.0, 760.0, 0.16, 0.00),   # 第一声：上扬
    (820.0, 900.0, 0.22, 0.17),   # 第二声：更高、尾部悬住
]


def envelope(t, dur):
    """快速起音 + 渐弱收尾；第二声故意不衰减到 0，留"悬着"的感觉"""
    attack = 0.012
    if t < attack:
        return t / attack
    rel = (t - attack) / max(1e-6, dur - attack)
    return max(0.0, 1.0 - rel ** 1.6) * 0.92 + 0.08


def sample_at(t):
    """叠加两个音，返回 [-1,1] 区间内的样本"""
    total = 0.0
    for f0, f1, dur, start in NOTES:
        lt = t - start
        if lt < 0 or lt > dur:
            continue
        k = lt / dur
        # 频率滑动
        f = f0 + (f1 - f0) * k
        # 快速颤音（约 30Hz），让音色更"叽"
        vib = 1.0 + 0.035 * math.sin(2 * math.pi * 30.0 * lt)
        phase = 2 * math.pi * f * vib * lt
        # 基频 + 几个谐波，模拟鸡叫的谐波结构
        wave_v = (math.sin(phase)
                  + 0.45 * math.sin(2 * phase)
                  + 0.22 * math.sin(3 * phase)
                  + 0.10 * math.sin(4 * phase))
        total += wave_v * envelope(lt, dur)
    return total


def main():
    dur_total = max(s + d for _, _, d, s in NOTES) + 0.02
    n = int(RATE * dur_total)
    raw = [sample_at(i / RATE) for i in range(n)]

    # 归一化到目标峰值，避免削顶
    peak = max(abs(x) for x in raw) or 1.0
    gain = PEAK / peak
    frames = []
    for x in raw:
        v = int(max(-1.0, min(1.0, x * gain)) * 32767)
        frames.append(v)

    out = os.path.abspath(OUT)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    w = wave.open(out, 'wb')
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(RATE)
    w.writeframes(struct.pack('<%dh' % len(frames), *frames))
    w.close()

    peak_after = max(abs(x) for x in frames)
    print('已生成: %s' % out)
    print('  %d 字节 | %d Hz | 16bit | 单声道 | %.2f 秒 | 峰值 %.1f%%'
          % (os.path.getsize(out), RATE, len(frames) / RATE, peak_after / 32767 * 100))


if __name__ == '__main__':
    main()
