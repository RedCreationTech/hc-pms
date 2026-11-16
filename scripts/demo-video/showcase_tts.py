#!/usr/bin/env python3
"""按分镜逐句调用技能 mimo_tts.py 合成配音并探测时长, 输出 durations.json.

幂等: wav 已存在则跳过 (mimo_tts.py 自身拒绝覆盖). 密钥只从 MIMO_API_KEY 环境变量读取.
用法: MIMO_API_KEY=sk-xxx tools/pyenv/bin/python3 scripts/demo-video/showcase_tts.py
"""
import json
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
SKILL = Path(os.environ.get('EVIDENCE_SKILL_DIR', str(Path.home() / '.codex/skills/agent-evidence-video')))
STORYBOARD = HERE / 'showcase-storyboard.json'
OUT = REPO / 'reports/demo-video/showcase'
AUDIO = OUT / 'audio'
FFPROBE = REPO / 'tools/bin/ffprobe'


def duration(path: Path) -> float:
    raw = subprocess.check_output(
        [str(FFPROBE), '-v', 'error', '-show_entries', 'format=duration',
         '-of', 'default=noprint_wrappers=1:nokey=1', str(path)], text=True)
    return float(raw.strip())


def main() -> int:
    if not (SKILL / 'scripts/mimo_tts.py').is_file():
        print(f'skill not found: {SKILL}', file=sys.stderr)
        return 1
    if not os.environ.get('MIMO_API_KEY'):
        print('Set MIMO_API_KEY in the environment.', file=sys.stderr)
        return 1
    AUDIO.mkdir(parents=True, exist_ok=True)
    data = json.loads(STORYBOARD.read_text(encoding='utf-8'))
    durations = {}
    for scene in data['scenes']:
        for line in scene['lines']:
            lid = line['id']
            wav = AUDIO / f'{lid}.wav'
            txt = AUDIO / f'{lid}.txt'
            txt.write_text(line['text'], encoding='utf-8')
            if not wav.exists():
                cmd = [sys.executable, str(SKILL / 'scripts/mimo_tts.py'),
                       '--text-file', str(txt), '--output', str(wav)]
                print(f'TTS {lid}: {line["text"]}')
                result = subprocess.run(cmd)
                if result.returncode != 0:
                    print(f'failed: {lid}', file=sys.stderr)
                    return 1
            durations[lid] = round(duration(wav), 3)
    out = OUT / 'durations.json'
    out.write_text(json.dumps(durations, ensure_ascii=False, indent=2), encoding='utf-8')
    total = sum(durations.values())
    print(f'{len(durations)} lines, narration total {total:.1f}s -> {out}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
