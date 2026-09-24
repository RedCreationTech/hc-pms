#!/usr/bin/env python3
"""Compose the HC-PMS demo video from the recorded walkthrough.

Inputs (reports/demo-video/): timeline.json (from tests/e2e/pms-demo-video.spec.js; every clip names the
per-chapter recording it was cut from, raw/ch-XX-admin.webm or raw/ch-XX-reviewer.webm), cards/*.png and
storyboard.json (from render_cards.js).
Outputs: edl.json, music.wav, subtitles.ass, hc-pms-demo.srt, hc-pms-demo.mp4.

Layout: 1920x1080 at 25 fps. Each recorded clip (1600x900 at 1:1, minus the 8 px time-code strip at
the bottom) is overlaid at (280, 40) on the chapter background (chapter rail on the left); subtitles
sit in the 140 px band at the bottom, centred under the screen; a thin red progress bar runs along the
bottom edge. Chapter cards are inserted before each chapter; screen clips fade in/out over the
background at every cut.

Sync: the recorder draws a time code into every frame; compose maps each clip start, end and caption
time to the first frame showing that moment. Under load the recording's own clock drifts from the
wall clock unevenly (measured up to 18 s over one chapter), so a fixed offset cannot align captions.

Pacing: long form-filling stretches under a single caption are fast-forwarded. Every caption interval
keeps real time up to max(HOLD, reading time + 2 s); the remainder plays at FF_RATE, so no caption
interval plays faster than about 2.3x. Sped-up intervals show a small "快进" badge at the bottom
of the chapter rail, so the viewer knows the recording was accelerated there.

Usage: python3 scripts/demo-video/compose.py [--keep] [--limit=N]
"""
import bisect
import json
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.environ.get('DEMO_VIDEO_DIR') or os.path.normpath(os.path.join(HERE, '../../reports/demo-video'))
WORK = os.path.join(OUT, 'work')
FPS = 25
OPEN_DUR, CARD_DUR, END_DUR = 4.0, 3.2, 7.0
CLIP_FADE = 0.22
HOLD, FF_RATE, FF_BADGE = 8.0, 3.0, 1.25
FONT = 'Noto Sans CJK SC'

sys.path.insert(0, HERE)
import music  # noqa: E402


def run(cmd):
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if res.returncode != 0:
        sys.stderr.write(res.stderr[-3000:])
        raise SystemExit(f'command failed: {" ".join(cmd[:6])} ...')
    return res


def probe_duration(path):
    res = run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', path])
    return float(res.stdout.strip())


def ts_ass(t):
    cs = int(round(t * 100))
    h, cs = divmod(cs, 360000)
    m, cs = divmod(cs, 6000)
    s, cs = divmod(cs, 100)
    return f'{h}:{m:02d}:{s:02d}.{cs:02d}'


def ts_srt(t):
    ms = int(round(t * 1000))
    h, ms = divmod(ms, 3600000)
    m, ms = divmod(ms, 60000)
    s, ms = divmod(ms, 1000)
    return f'{h:02d}:{m:02d}:{s:02d},{ms:03d}'


def frames(sec):
    return max(1, int(round(sec * FPS)))


def clip_video(timeline, clip):
    # Per-chapter recordings carry their own path; older single-take timelines map account -> video.
    return clip.get('video') or timeline['videos'][clip['who']]


def reading_time(text):
    # Same rule as storyboard.js minDuration: at least 2.8 s, 6.5 characters per second.
    return max(2.8, len(text) / 6.5)


def pieces_for(clip):
    """Split a clip at its caption cues; each piece plays at one speed (1.0 for short intervals)."""
    cues = clip['cues']
    out = []
    for i, cue in enumerate(cues):
        src_start = clip['start'] if i == 0 else cue['t']
        src_end = cues[i + 1]['t'] if i + 1 < len(cues) else clip['end']
        src_dur = max(0.04, src_end - src_start)
        keep = max(HOLD, reading_time(cue['text']) + 2.0)
        shown = src_dur if src_dur <= keep else keep + (src_dur - keep) / FF_RATE
        out.append({'src': round(src_start - clip['start'], 3), 'src_dur': round(src_dur, 3),
                    'speed': round(src_dur / shown, 4), 'text': cue['text'], 'lead': max(0.0, cue['t'] - src_start)})
    return out


CLOCK_Y, CLOCK_H, CLOCK_BITS, CLOCK_BLOCK, CLOCK_MOD = 892, 8, 27, 56, 1 << 24


def frame_clock(rel):
    """Read the time-code strip (see installClock in the spec) from every frame of a recording.

    Returns a list of (video_seconds, epoch_ms_mod_2^24) for the frames whose code decodes cleanly
    (start bits 1,0 and even parity); frames caught mid-update or before the page loaded are skipped.
    """
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', os.path.join(OUT, rel), '-vf',
                          f'fps={FPS},crop=1600:{CLOCK_H}:0:{CLOCK_Y},format=gray', '-f', 'rawvideo', '-'],
                         capture_output=True).stdout
    size = 1600 * CLOCK_H
    out = []
    for i in range(len(raw) // size):
        rows = raw[i * size:(i + 1) * size]
        mid = rows[2 * 1600:3 * 1600]  # third pixel row: away from the strip edges
        bits = []
        for b in range(CLOCK_BITS):
            seg = mid[b * CLOCK_BLOCK + 12:b * CLOCK_BLOCK + CLOCK_BLOCK - 12]
            level = sum(seg) / len(seg)
            bits.append(1 if level < 80 else 0 if level > 175 else None)
        if None in bits or bits[:2] != [1, 0] or sum(bits[2:26]) % 2 != bits[26]:
            continue
        value = 0
        for bit in bits[2:26]:
            value = value * 2 + bit
        out.append((i / FPS, value))
    return out


def clock_mapper(rel, t0):
    """Map timeline seconds (relative to epoch-ms t0) to the first frame that shows that moment."""
    frames = frame_clock(rel)
    if not frames:
        raise SystemExit(f'{rel}: no readable time code')
    base = t0 - t0 % CLOCK_MOD
    stamps, times, top = [], [], None
    for vt, value in frames:
        ms = base + value
        if ms < t0 - CLOCK_MOD // 2:
            ms += CLOCK_MOD
        elif ms > t0 + CLOCK_MOD // 2:
            ms -= CLOCK_MOD
        top = ms if top is None else max(top, ms)  # keep the mapping monotonic
        stamps.append(top)
        times.append(vt)

    def to_video(seconds):
        idx = bisect.bisect_left(stamps, t0 + seconds * 1000)
        return times[min(idx, len(times) - 1)]
    return to_video, len(frames)


def align(timeline):
    """Move clip and cue times from the timeline clock onto each recording's frames via the time code."""
    mappers, report = {}, {}
    for clip in timeline['clips']:
        if 't0' not in clip:
            continue  # older recordings without a time code: assume the clocks match
        rel = clip_video(timeline, clip)
        if rel not in mappers:
            mappers[rel], report[rel] = clock_mapper(rel, clip['t0'])
        to_video = mappers[rel]
        start, end = to_video(clip['start']), to_video(clip['end'])
        clip['lag'] = round(start - clip['start'], 2)
        for cue in clip['cues']:
            cue['t'] = max(start, to_video(cue['t']))
        clip['start'], clip['end'] = start, max(end, start + 1.0)
    return report


def build_edl(timeline, board):
    chapters = {c['no']: c for c in board['chapters']}
    items = [{'type': 'card', 'card': 'open', 'image': 'cards/open.png', 'duration': OPEN_DUR}]
    current = None
    for clip in timeline['clips']:
        if clip['chapter'] != current:
            current = clip['chapter']
            items.append({'type': 'card', 'card': 'chapter', 'chapter': current, 'title': chapters[current]['title'],
                          'image': f'cards/ch-{current}.png', 'duration': CARD_DUR})
        pieces = pieces_for(clip)
        at, cues = 0.0, []
        for pc in pieces:
            pc['at'] = round(at, 3)
            cues.append({'offset': round(at + pc['lead'] / pc['speed'], 3), 'text': pc['text']})
            at += pc['src_dur'] / pc['speed']
        items.append({'type': 'clip', 'shot': clip['shot'], 'chapter': clip['chapter'], 'who': clip['who'],
                      'video': clip_video(timeline, clip), 'start': clip['start'], 'duration': round(at, 3),
                      'source_duration': round(clip['end'] - clip['start'], 3), 'pieces': pieces,
                      'background': f'cards/bg-{clip["chapter"]}.png', 'cues': cues})
    items.append({'type': 'card', 'card': 'end', 'image': 'cards/end.png', 'duration': END_DUR})
    # Durations are quantised to whole frames so that concatenated segments keep exact timing.
    at = 0.0
    for it in items:
        it['frames'] = frames(it['duration'])
        it['duration'] = it['frames'] / FPS
        it['at'] = round(at, 3)
        at += it['duration']
    return {'fps': FPS, 'size': [1920, 1080], 'duration': round(at, 3), 'project_no': timeline.get('project_no'), 'items': items}


def subtitle_events(edl):
    events = []
    for it in edl['items']:
        if it['type'] != 'clip':
            continue
        cues = it['cues']
        for i, cue in enumerate(cues):
            start = it['at'] + max(0.0, cue['offset'])
            end = it['at'] + (cues[i + 1]['offset'] if i + 1 < len(cues) else it['duration'] - 0.12)
            if end - start > 0.3:
                events.append((start, end, cue['text']))
    return events


def write_ass(edl, events, path):
    head = f"""[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
WrapStyle: 2
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Sub,{FONT},46,&H00FFFFFF,&H00FFFFFF,&H00101010,&H78000000,0,0,0,0,100,100,1,0,1,1.6,1.2,2,280,40,44,1
Style: Tag,{FONT},22,&H00B0B8C4,&H00FFFFFF,&H00101010,&H00000000,0,0,0,0,100,100,1,0,1,0,0,1,40,40,48,1
Style: Fast,{FONT},24,&H00FFFFFF,&H00FFFFFF,&H30353AE5,&H30353AE5,1,0,0,0,100,100,1,0,3,7,0,7,42,40,872,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
    lines = [head]
    for start, end, text in events:
        lines.append(f'Dialogue: 0,{ts_ass(start)},{ts_ass(end)},Sub,,0,0,0,,{{\\fad(160,160)}}{text}\n')
    # Small account tag at the lower left: which account is on screen.
    for it in edl['items']:
        if it['type'] == 'clip':
            who = '独立审核人账号' if it['who'] == 'reviewer' else '项目经理账号'
            lines.append(f"Dialogue: 0,{ts_ass(it['at'])},{ts_ass(it['at'] + it['duration'])},Tag,,0,0,0,,{who}\n")
            # Fast-forward badge in the chapter rail (below the chapter list) while a piece plays sped up.
            for pc in it.get('pieces', []):
                shown = pc['src_dur'] / pc['speed']
                if pc['speed'] >= FF_BADGE and shown > 1.2:
                    start, end = it['at'] + pc['at'] + 0.25, min(it['at'] + it['duration'], it['at'] + pc['at'] + shown) - 0.2
                    lines.append(f"Dialogue: 1,{ts_ass(start)},{ts_ass(end)},Fast,,0,0,0,,{{\\fad(120,120)}}快进 {pc['speed']:.1f}x\n")
    with open(path, 'w', encoding='utf-8') as f:
        f.writelines(lines)


def write_srt(events, path):
    with open(path, 'w', encoding='utf-8') as f:
        for i, (start, end, text) in enumerate(events, 1):
            f.write(f'{i}\n{ts_srt(start)} --> {ts_srt(end)}\n{text}\n\n')


def render_card(it, dest):
    fr = it['frames']
    dur = fr / FPS
    fade = 0.45 if it['card'] != 'chapter' else 0.35
    vf = f'fps={FPS},format=yuv420p,fade=t=in:st=0:d={fade},fade=t=out:st={dur - fade:.3f}:d={fade}'
    run(['ffmpeg', '-y', '-v', 'error', '-loop', '1', '-framerate', str(FPS), '-i', os.path.join(OUT, it['image']),
         '-vf', vf, '-frames:v', str(fr), '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '14', '-pix_fmt', 'yuv420p', dest])


def render_clip(it, screen, dest):
    fr = it['frames']
    dur = fr / FPS
    pieces = it.get('pieces') or [{'src': 0.0, 'src_dur': it['duration'], 'speed': 1.0}]
    # The bottom CLOCK_H rows carry the time code; crop them (the background frame shows through).
    graph = [f'[1:v]crop=1600:{CLOCK_Y}:0:0,setpts=PTS-STARTPTS' + (f',split={len(pieces)}' + ''.join(f'[i{i}]' for i in range(len(pieces))) if len(pieces) > 1 else '[i0]')]
    for i, pc in enumerate(pieces):
        graph.append(f"[i{i}]trim=start={pc['src']:.3f}:duration={pc['src_dur']:.3f},setpts=(PTS-STARTPTS)/{pc['speed']}[p{i}]")
    joined = ''.join(f'[p{i}]' for i in range(len(pieces)))
    graph.append((f'{joined}concat=n={len(pieces)}:v=1:a=0,' if len(pieces) > 1 else f'{joined}null,')
                 + f"fps={FPS},format=yuva420p,fade=t=in:st=0:d={CLIP_FADE}:alpha=1,"
                 + f"fade=t=out:st={dur - CLIP_FADE:.3f}:d={CLIP_FADE}:alpha=1[fg]")
    graph.append(f"[0:v][fg]overlay={screen['x']}:{screen['y']}:eof_action=repeat,format=yuv420p[v]")
    fg = ';'.join(graph)
    source = sum(pc['src_dur'] for pc in pieces)
    run(['ffmpeg', '-y', '-v', 'error', '-loop', '1', '-framerate', str(FPS), '-i', os.path.join(OUT, it['background']),
         '-ss', f"{it['start']:.3f}", '-t', f'{source + 0.5:.3f}', '-i', os.path.join(OUT, it['video']),
         '-filter_complex', fg, '-map', '[v]', '-frames:v', str(fr),
         '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '14', '-pix_fmt', 'yuv420p', dest])


def main():
    keep = '--keep' in sys.argv
    limit = next((int(a.split('=')[1]) for a in sys.argv if a.startswith('--limit=')), None)
    timeline = json.load(open(os.path.join(OUT, 'timeline.json'), encoding='utf-8'))
    if limit:
        timeline['clips'] = timeline['clips'][:limit]
    board = json.load(open(os.path.join(OUT, 'storyboard.json'), encoding='utf-8'))
    decoded = align(timeline)
    if decoded:
        lags = [c['lag'] for c in timeline['clips'] if 'lag' in c]
        print(f'time code: {len(decoded)} recordings, {sum(decoded.values())} frames decoded; '
              f'clip start lag {min(lags):+.2f}..{max(lags):+.2f}s')
    for rel in sorted({clip_video(timeline, c) for c in timeline['clips']}):
        length = probe_duration(os.path.join(OUT, rel))
        last = max(c['end'] for c in timeline['clips'] if clip_video(timeline, c) == rel)
        if last > length + 0.5:
            raise SystemExit(f'{rel} is {length:.1f}s but the timeline needs {last:.1f}s')
    edl = build_edl(timeline, board)
    json.dump(edl, open(os.path.join(OUT, 'edl.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    print(f"edl: {len(edl['items'])} items, {edl['duration']:.1f}s")

    events = subtitle_events(edl)
    write_ass(edl, events, os.path.join(OUT, 'subtitles.ass'))
    write_srt(events, os.path.join(OUT, 'hc-pms-demo.srt'))
    print(f'subtitles: {len(events)} lines')

    cards = [{'start': it['at'], 'duration': it['duration'], 'kind': it['card']} for it in edl['items'] if it['type'] == 'card']
    music.render(edl['duration'], cards, os.path.join(OUT, 'music.wav'))
    print('music: rendered')

    shutil.rmtree(WORK, ignore_errors=True)
    os.makedirs(WORK)
    parts = []
    for i, it in enumerate(edl['items']):
        dest = os.path.join(WORK, f'seg_{i:03d}.mp4')
        if it['type'] == 'card':
            render_card(it, dest)
        else:
            render_clip(it, board['screen'], dest)
        parts.append(dest)
        sys.stdout.write(f'\rsegments: {i + 1}/{len(edl["items"])}')
        sys.stdout.flush()
    print()
    with open(os.path.join(WORK, 'list.txt'), 'w') as f:
        f.writelines(f"file '{p}'\n" for p in parts)
    body = os.path.join(WORK, 'body.mp4')
    run(['ffmpeg', '-y', '-v', 'error', '-f', 'concat', '-safe', '0', '-i', os.path.join(WORK, 'list.txt'), '-c', 'copy', body])

    total = edl['duration']
    ass = os.path.join(OUT, 'subtitles.ass').replace('\\', '/').replace(':', '\\:')
    vf = (f"[0:v]ass='{ass}'[s];color=c=0xE53935:s=1920x6:r={FPS}[bar];"
          f"[s][bar]overlay=x='-W+W*t/{total:.3f}':y=1074:shortest=1[v];"
          f"[1:a]loudnorm=I=-20:TP=-1.5:LRA=11,aresample=48000[a]")
    final = os.path.join(OUT, 'hc-pms-demo.mp4')
    run(['ffmpeg', '-y', '-v', 'error', '-i', body, '-i', os.path.join(OUT, 'music.wav'), '-filter_complex', vf,
         '-map', '[v]', '-map', '[a]', '-t', f'{total:.3f}', '-c:v', 'libx264', '-preset', 'slow', '-crf', '25',
         '-tune', 'stillimage', '-x264-params', 'keyint=250:min-keyint=25', '-pix_fmt', 'yuv420p',
         '-c:a', 'aac', '-b:a', '160k', '-movflags', '+faststart', final])
    size = os.path.getsize(final) / 1024 / 1024
    print(f'final: {final} {probe_duration(final):.1f}s {size:.1f} MiB')
    if not keep:
        shutil.rmtree(WORK, ignore_errors=True)


if __name__ == '__main__':
    main()
