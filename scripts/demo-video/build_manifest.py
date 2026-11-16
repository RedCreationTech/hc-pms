#!/usr/bin/env python3
"""由分镜 + 台词时长 + 录屏 marks 生成 agent-evidence-video 的 manifest.json.

输入:
  scripts/demo-video/showcase-storyboard.json  台词文本 (唯一数据源)
  reports/demo-video/showcase/durations.json   每句配音时长 (showcase_tts.py 产出)
  reports/demo-video/showcase/marks.json       每句台词在各章录屏内的起点 (录制 spec 产出)
输出:
  reports/demo-video/showcase/manifest.json    供 skill compose.py 消费
校验: 句间不重叠, 末句配音完整落在录屏时长内 (ffprobe 可用时).
"""
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STORYBOARD = ROOT / 'scripts/demo-video/showcase-storyboard.json'
OUT_DIR = ROOT / 'reports/demo-video/showcase'
DURATIONS = OUT_DIR / 'durations.json'
MARKS = OUT_DIR / 'marks.json'
MANIFEST = OUT_DIR / 'manifest.json'


def probe_duration(path: Path) -> float | None:
    try:
        raw = subprocess.check_output(
            ['ffprobe', '-v', 'error', '-show_entries', 'format=duration',
             '-of', 'default=noprint_wrappers=1:nokey=1', str(path)],
            text=True, stderr=subprocess.DEVNULL)
        return float(raw.strip())
    except (OSError, subprocess.CalledProcessError, ValueError):
        return None


def main() -> int:
    storyboard = json.loads(STORYBOARD.read_text(encoding='utf-8'))
    durations = json.loads(DURATIONS.read_text(encoding='utf-8'))
    marks = json.loads(MARKS.read_text(encoding='utf-8'))

    scenes = []
    for scene in storyboard['scenes']:
        sid = scene['id']
        if sid not in marks:
            print(f'error: scene {sid} missing in marks.json (recording incomplete?)', file=sys.stderr)
            return 1
        capture = OUT_DIR / 'raw' / f'{sid}.webm'
        length = probe_duration(capture)
        if length is None:
            print(f'error: cannot probe {capture}', file=sys.stderr)
            return 1
        lines = []
        prior_end = 0.0
        for line in scene['lines']:
            lid = line['id']
            if lid not in durations or lid not in marks[sid]['lines']:
                print(f'error: line {lid} missing durations/marks', file=sys.stderr)
                return 1
            start = float(marks[sid]['lines'][lid])
            end = start + float(durations[lid])
            if start < prior_end - 0.001:
                print(f'error: line {lid} overlaps previous (start {start} < {prior_end})', file=sys.stderr)
                return 1
            if end > length - 0.02:
                print(f'error: line {lid} audio ends {end:.2f}s past capture {length:.2f}s', file=sys.stderr)
                return 1
            lines.append({'text': line['text'], 'audio': f'audio/{lid}.wav', 'start': start})
            prior_end = end
        scenes.append({'title': f'{sid} {scene["title"]}', 'capture': f'raw/{sid}.webm', 'lines': lines})
        print(f'scene {sid}: {len(lines)} lines, capture {length:.2f}s, last end {prior_end:.2f}s')

    manifest = {'title': storyboard['title'], 'scenes': scenes}
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'wrote {MANIFEST}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
