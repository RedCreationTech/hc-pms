#!/usr/bin/env python3
"""进展证据视频合成: 卡片 PNG + MiMo 配音 + 字幕 -> MP4 + SRT.

数据源 evidence_video.json (卡片与解说词的唯一事实来源), 卡片由
render_evidence_cards.js 预先渲染到 <out>/cards/NN.png. 本脚本逐段:
  1) MiMo TTS 合成解说 wav (按段缓存, 复用 compose.synth_narration)
  2) 按解说时长生成段内 SRT (全局 SRT 同步累计时间轴)
  3) ffmpeg 渲染段: 卡片淡入淡出 + 底部字幕烧录 + 音频延迟到片头留白
  4) concat 全部分段, 输出 faststart MP4 与整片 SRT

用法 (key 只走环境变量, 不入库):
  MIMO_API_KEY=sk-xxx PATH="$PWD/tools/bin:$PATH" \
    tools/pyenv/bin/python3 scripts/demo-video/evidence_video.py
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '../..'))
sys.path.insert(0, HERE)
# TTS/时长探测用的 ffmpeg/ffprobe 走项目内静态构建 (含 libass)
os.environ['PATH'] = os.path.join(REPO, 'tools/bin') + os.pathsep + os.environ.get('PATH', '')
os.environ.setdefault('FONTCONFIG_PATH', os.path.join(REPO, 'tools/ffmpeg/fonts'))

import compose  # noqa: E402  (复用 mimo 合成/时长探测/子进程工具, main 有守卫)

LEAD = 0.6    # 片头留白 (卡片先于人声)
TAIL = 1.0    # 片尾留白
FADE = 0.35   # 卡片淡入淡出
MAX_LINE = 22     # 字幕单行最大字符数
MAX_EVENT = 2     # 每条字幕最多行数
MIN_EVENT = 1.2   # 单条字幕最短秒数


def out_dir():
    return os.environ.get('EVIDENCE_VIDEO_DIR') or os.path.join(REPO, 'reports/demo-video/evidence')


def split_events(text):
    """把解说词切成 <=MAX_EVENT 行、每行 <=MAX_LINE 字的字幕事件, 保留句读."""
    # 按句末标点切句 (保留标点)
    import re
    word = r'[A-Za-z0-9-]'
    sentences = [s for s in re.split(r'(?<=[。!?；;])', text) if s]
    lines = []
    for s in sentences:
        s = s.strip()
        while len(s) > MAX_LINE:
            cut = MAX_LINE
            # 硬切点落在拉丁词中间时回退到最近的词边界, 避免 crap/per 断词
            while cut > 1 and re.match(word, s[cut - 1]) and re.match(word, s[cut]):
                cut -= 1
            lines.append(s[:cut].rstrip())
            s = s[cut:].lstrip()
        if s:
            lines.append(s)
    # 标点禁则: 行首标点上移一行, 孤立标点行并入上一行
    fixed = []
    for ln in lines:
        m = re.match(r'^([,.。;:!?、，:：]+)(.*)$', ln)
        if m and fixed:
            fixed[-1] += m.group(1)
            ln = m.group(2)
        if ln:
            fixed.append(ln)
    lines = fixed or [text]
    events = []
    for i in range(0, len(lines), MAX_EVENT):
        events.append('\n'.join(lines[i:i + MAX_EVENT]))
    return events or [text]


def write_srt(events, spans, path):
    """spans: [(start_sec, end_sec), ...] 与 events 等长."""
    def ts(t):
        ms = int(round(t * 1000))
        h, ms = divmod(ms, 3600000)
        m, ms = divmod(ms, 60000)
        s, ms = divmod(ms, 1000)
        return f'{h:02d}:{m:02d}:{s:02d},{ms:03d}'
    with open(path, 'w', encoding='utf-8') as f:
        for i, (ev, (a, b)) in enumerate(zip(events, spans), 1):
            f.write(f'{i}\n{ts(a)} --> {ts(b)}\n{ev}\n\n')


def main():
    data = json.load(open(os.path.join(HERE, 'evidence_video.json'), encoding='utf-8'))
    segs = data['segments']
    out = out_dir()
    cards = os.path.join(out, 'cards')
    narr = os.path.join(out, 'narr')
    subs = os.path.join(out, 'subs')
    clips = os.path.join(out, 'clips')
    for d in (narr, subs, clips):
        os.makedirs(d, exist_ok=True)
    for i in range(1, len(segs) + 1):
        if not os.path.exists(os.path.join(cards, f'{i:02d}.png')):
            raise SystemExit(f'missing card {i:02d}.png; run: node scripts/demo-video/render_evidence_cards.js')

    total = 0.0
    srt_all = []
    idx_srt = [0]
    clip_list = []
    for i, seg in enumerate(segs, 1):
        tag = f'{i:02d}'
        wav = os.path.join(narr, tag + '.wav')
        dur = compose.synth_narration(seg['narration'], wav)   # 已合成则直接复用
        seg_dur = LEAD + dur + TAIL
        # 段内字幕: 按字符比例分配到 [LEAD, LEAD+dur]
        events = split_events(seg['narration'])
        total_chars = sum(len(e.replace('\n', '')) for e in events) or 1
        spans = []
        cursor = LEAD
        for e in events:
            share = max(MIN_EVENT, dur * (len(e.replace('\n', '')) / total_chars))
            spans.append((cursor, min(cursor + share, LEAD + dur)))
            cursor = spans[-1][1]
        # 收紧: 最后一条贴到 LEAD+dur
        if spans:
            spans[-1] = (spans[-1][0], LEAD + dur)
        local_srt = os.path.join(subs, tag + '.srt')
        write_srt(events, spans, local_srt)
        for ev, (a, b) in zip(events, spans):
            srt_all.append((total + a, total + b, ev))
        idx_srt.append(len(srt_all))
        # 渲染分段
        card = os.path.join(cards, tag + '.png')
        clip = os.path.join(clips, tag + '.mp4')
        clip_list.append(clip)
        fade_out = max(0.0, seg_dur - FADE)
        fc = (
            f"[0:v]fade=t=in:st=0:d={FADE},fade=t=out:st={fade_out:.3f}:d={FADE},"
            f"subtitles='{local_srt}':force_style='FontName=Noto Sans CJK SC,FontSize=16,MarginV=30,Outline=1'[v];"
            f"[1:a]adelay={int(LEAD*1000)}|{int(LEAD*1000)},apad[a]"
        )
        compose.run([
            'ffmpeg', '-y', '-v', 'error',
            '-loop', '1', '-framerate', '25', '-i', card,
            '-i', wav,
            '-filter_complex', fc,
            '-map', '[v]', '-map', '[a]',
            '-t', f'{seg_dur:.3f}',
            '-c:v', 'libx264', '-preset', 'medium', '-crf', '20', '-pix_fmt', 'yuv420p',
            '-c:a', 'aac', '-b:a', '160k', '-ar', '48000', '-ac', '2',
            clip,
        ])
        total += seg_dur
        print(f'seg {tag}: narr={dur:.2f}s seg={seg_dur:.2f}s events={len(events)}')

    # 整片 SRT (绝对时间轴)
    def ts(t):
        ms = int(round(t * 1000))
        h, ms = divmod(ms, 3600000)
        m, ms = divmod(ms, 60000)
        s, ms = divmod(ms, 1000)
        return f'{h:02d}:{m:02d}:{s:02d},{ms:03d}'
    final_srt = os.path.join(out, 'hc-pms-progress-evidence.srt')
    with open(final_srt, 'w', encoding='utf-8') as f:
        for n, (a, b, ev) in enumerate(srt_all, 1):
            f.write(f'{n}\n{ts(a)} --> {ts(b)}\n{ev}\n\n')

    # 合并分段
    listfile = os.path.join(clips, 'list.txt')
    with open(listfile, 'w', encoding='utf-8') as f:
        for c in clip_list:
            f.write("file '" + c + "'\n")
    final = os.path.join(out, 'hc-pms-progress-evidence.mp4')
    compose.run([
        'ffmpeg', '-y', '-v', 'error',
        '-f', 'concat', '-safe', '0', '-i', listfile,
        '-c', 'copy', '-movflags', '+faststart',
        final,
    ])
    dur = compose.probe_duration(final)
    size = os.path.getsize(final)
    print(f'done: {final}')
    print(f'duration={dur:.1f}s size={size/1024/1024:.1f}MiB segments={len(segs)} srt={final_srt}')


if __name__ == '__main__':
    main()
