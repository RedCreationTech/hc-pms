#!/usr/bin/env python3
"""Original background music for the HC-PMS demo video, synthesized note by note.

No samples, loops or third-party material are used: every sound is generated here from
oscillators and noise (numpy / scipy). Style: light, upbeat, "tech" feel.

- 112 BPM, D major. Chord cycles of 8 bars: I-V-vi-IV-I-V-IV-V, and a vi-IV-I-V variant.
- Voices: detuned-saw pad, plucked 16th-note arpeggio with ping-pong delay, plucky bass,
  bell-like FM hook every other cycle, four-on-the-floor kick, off-beat hats, soft claps.
- Arranged against the video edit: drums breathe out under every chapter card, a noise
  riser leads into each card and a soft impact lands on it; intro and outro are gentle.
- Mastered to about -20 LUFS later by ffmpeg loudnorm in compose.py (background level).

Usage: python3 scripts/demo-video/music.py [edl.json] [out.wav]
"""
import json
import os
import sys
import wave

import numpy as np
from scipy import signal

SR = 44100
DT = np.float32  # long timelines (12+ min) are ~33M samples per track; single precision halves memory
BPM = 112.0
BEAT = 60.0 / BPM
BAR = 4 * BEAT
STEP = BEAT / 4  # 16th note

# D major, MIDI roots in octave 3 (D3 = 50).
CHORDS = {
    'D': (50, (0, 4, 7)), 'A': (45, (0, 4, 7)), 'Bm': (47, (0, 3, 7)), 'G': (43, (0, 4, 7)),
    'Em': (52, (0, 3, 7)), 'F#m': (54, (0, 3, 7)),
}
PROG_A = ['D', 'A', 'Bm', 'G', 'D', 'A', 'G', 'A']
PROG_B = ['Bm', 'G', 'D', 'A', 'Bm', 'G', 'Em', 'A']
# Hook: (step offset within 2 bars in 16ths, MIDI note, length in 16ths); D major pentatonic.
HOOK = [(0, 78, 3), (3, 81, 3), (6, 83, 2), (8, 81, 4), (12, 78, 2), (14, 76, 2),
        (16, 74, 3), (19, 76, 3), (22, 78, 2), (24, 81, 6), (30, 83, 2)]


def hz(midi):
    return 440.0 * 2 ** ((midi - 69) / 12.0)


def saw(freq, n, phase=0.0):
    t = np.arange(n) / SR
    return 2.0 * ((freq * t + phase) % 1.0) - 1.0


def env_adsr(n, a, d, s, r, sustain_len):
    """Piecewise-linear ADSR envelope, all times in seconds; total length n samples."""
    e = np.zeros(n)
    ai, di, si, ri = int(a * SR), int(d * SR), int(sustain_len * SR), int(r * SR)
    idx = 0
    for seg, start, end in ((ai, 0.0, 1.0), (di, 1.0, s), (si, s, s), (ri, s, 0.0)):
        seg = max(1, min(seg, n - idx))
        if idx >= n:
            break
        e[idx:idx + seg] = np.linspace(start, end, seg, endpoint=False)
        idx += seg
    return e


def add(buf, start_s, sig, gain=1.0):
    i = int(round(start_s * SR))
    if i >= buf.shape[-1] or i + sig.shape[-1] <= 0:
        return
    j = min(buf.shape[-1], i + sig.shape[-1])
    k = max(0, -i)
    buf[..., max(i, 0):j] += gain * sig[..., k:k + (j - max(i, 0))]


def lowpass(x, cutoff, order=2):
    b, a = signal.butter(order, cutoff / (SR / 2), btype='low')
    return signal.lfilter(b, a, x, axis=-1).astype(DT, copy=False)


def highpass(x, cutoff, order=2):
    b, a = signal.butter(order, cutoff / (SR / 2), btype='high')
    return signal.lfilter(b, a, x, axis=-1).astype(DT, copy=False)


def voicing(name, center=66):
    root, ivs = CHORDS[name]
    notes = [root + i for i in ivs] + [root + 12]
    # Move every tone to the octave closest to the centre for smooth voice leading.
    return sorted(n + 12 * round((center - n) / 12) for n in notes)


def chord_at(bar_index):
    cycle, pos = divmod(bar_index, 8)
    return (PROG_B if cycle % 3 == 2 else PROG_A)[pos]


def smooth_mask(n, windows, ramp=0.35, floor=0.0):
    """1.0 everywhere except inside windows [(start, end)] where it ramps down to floor."""
    m = np.ones(n, dtype=DT)
    r = int(ramp * SR)
    for start, end in windows:
        a, b = int(start * SR), int(end * SR)
        a0, b1 = max(0, a - r), min(n, b + r)
        seg = np.ones(b1 - a0)
        down = min(r, a - a0)
        if down > 0:
            seg[:down] = np.linspace(1.0, floor, down)
        seg[down:down + (b - a)] = floor
        up = b1 - b
        if up > 0:
            seg[-up:] = np.linspace(floor, 1.0, up)
        m[a0:b1] = np.minimum(m[a0:b1], seg)
    return m


def reverb_ir(seconds=1.9, seed=11):
    rng = np.random.default_rng(seed)
    n = int(seconds * SR)
    t = np.arange(n) / SR
    decay = np.exp(-t / (seconds / 5.5))
    ir = rng.standard_normal((2, n)) * decay
    ir = lowpass(ir, 5200)
    ir[:, :int(0.012 * SR)] = 0.0  # pre-delay
    return ir / np.sqrt(np.sum(ir ** 2, axis=-1, keepdims=True))


def render(duration, cards, out_path, seed=7):
    rng = np.random.default_rng(seed)
    n = int((duration + 0.5) * SR)
    pad, arp, bass, lead, kick, hats, claps, fx = (np.zeros(n, dtype=DT) for _ in range(8))
    bars = int(np.ceil(duration / BAR)) + 1

    # Pad: 3 detuned saws per chord tone, slow attack, one chord per bar.
    for b in range(bars):
        t0 = b * BAR
        notes = voicing(chord_at(b))
        length = int((BAR + 0.6) * SR)
        env = env_adsr(length, 0.35, 0.3, 0.8, 0.6, BAR - 0.65)
        chord = np.zeros(length)
        for m in notes:
            for det in (-0.09, 0.0, 0.08):
                chord += saw(hz(m + det), length, rng.random())
        add(pad, t0, chord * env, 0.06)
    pad = lowpass(pad, 1900, 2)

    # Arpeggio: 16ths over chord tones two octaves up, plucked envelope.
    pattern = [0, 2, 1, 3, 2, 1, 0, 2]
    note_len = int(0.28 * SR)
    pluck_env = np.exp(-np.arange(note_len) / SR / 0.085)
    pluck_env[:int(0.002 * SR)] *= np.linspace(0, 1, int(0.002 * SR))
    steps = int(duration / STEP) + 1
    for s in range(steps):
        t0 = s * STEP
        tones = voicing(chord_at(int(t0 // BAR)), center=78)
        m = tones[pattern[s % len(pattern)] % len(tones)]
        vel = 0.9 if s % 4 == 0 else 0.62 if s % 2 == 0 else 0.5
        tone = 0.65 * saw(hz(m), note_len) + 0.35 * np.sign(np.sin(2 * np.pi * hz(m) * np.arange(note_len) / SR))
        add(arp, t0, tone * pluck_env, 0.05 * vel)
    arp = lowpass(arp, 3400, 2)
    # Ping-pong delay (dotted eighth), stereo.
    d = int(3 * STEP * SR)
    arp_st = np.vstack([arp, arp * DT(0.85)])
    for k, fb in enumerate((0.42, 0.28, 0.17)):
        ch = (k + 1) % 2
        shifted = np.zeros(n, dtype=DT)
        shifted[d * (k + 1):] = arp[:-d * (k + 1)] if d * (k + 1) < n else 0
        arp_st[ch] += lowpass(shifted, 2600, 1) * DT(fb)
        del shifted

    # Bass: plucky 8ths on the root.
    b_len = int(0.24 * SR)
    b_env = np.exp(-np.arange(b_len) / SR / 0.11)
    b_env[:int(0.004 * SR)] *= np.linspace(0, 1, int(0.004 * SR))
    for e in range(int(duration / (BEAT / 2)) + 1):
        t0 = e * BEAT / 2
        root = CHORDS[chord_at(int(t0 // BAR))][0] - 12
        tt = np.arange(b_len) / SR
        tone = np.sin(2 * np.pi * hz(root) * tt) + 0.35 * saw(hz(root), b_len)
        add(bass, t0, tone * b_env, 0.16 if e % 2 else 0.2)
    bass = lowpass(bass, 700, 2)

    # Hook (FM bell) every other 8-bar cycle, skipping the intro cycle.
    hook_len = int(1.1 * SR)
    for c in range(1, bars // 8 + 1):
        if c % 2 == 0:
            continue
        for rep in range(4):
            base_t = (c * 8 + rep * 2) * BAR
            for off, m, ln in HOOK:
                t0 = base_t + off * STEP
                if t0 >= duration:
                    break
                tt = np.arange(hook_len) / SR
                idx = 2.6 * np.exp(-tt / 0.18) + 0.4
                sig = np.sin(2 * np.pi * hz(m) * tt + idx * np.sin(2 * np.pi * hz(m) * 2.0 * tt))
                env = np.exp(-tt / (0.12 + 0.05 * ln))
                env[:int(0.004 * SR)] *= np.linspace(0, 1, int(0.004 * SR))
                add(lead, t0, sig * env, 0.045)

    # Drums.
    k_len = int(0.32 * SR)
    kt = np.arange(k_len) / SR
    k_freq = 45 + 85 * np.exp(-kt / 0.03)
    k_sig = np.sin(2 * np.pi * np.cumsum(k_freq) / SR) * np.exp(-kt / 0.13)
    k_sig[:int(0.003 * SR)] += rng.standard_normal(int(0.003 * SR)) * 0.25
    kick_times = [i * BEAT for i in range(int(duration / BEAT) + 1)]
    for t0 in kick_times:
        add(kick, t0, k_sig, 0.42)
    h_len = int(0.06 * SR)
    h_noise = highpass(rng.standard_normal(h_len), 7000, 2) * np.exp(-np.arange(h_len) / SR / 0.018)
    for s in range(steps):
        if s % 4 == 2:
            add(hats, s * STEP, h_noise, 0.11)
        elif s % 2 == 1:
            add(hats, s * STEP, h_noise, 0.04)
    c_len = int(0.22 * SR)
    c_noise = signal.lfilter(*signal.butter(2, [900 / (SR / 2), 2600 / (SR / 2)], btype='band'), rng.standard_normal(c_len))
    c_env = np.zeros(c_len)
    for off in (0.0, 0.011, 0.022):
        i = int(off * SR)
        c_env[i:] += np.exp(-np.arange(c_len - i) / SR / (0.012 if off < 0.02 else 0.09))
    for beat in range(1, int(duration / BEAT) + 1, 2):
        add(claps, beat * BEAT, c_noise * c_env, 0.1)

    # Sidechain pump from the kick on pad, bass and arp.
    duck = np.ones(n, dtype=DT)
    dl = int(0.2 * SR)
    shape = 1 - 0.42 * np.exp(-np.arange(dl) / SR / 0.07)
    for t0 in kick_times:
        i = int(t0 * SR)
        if i < n:
            j = min(n, i + dl)
            duck[i:j] = np.minimum(duck[i:j], shape[:j - i])

    # Arrangement: drums breathe out under cards; hats and claps wait for the first chapter.
    card_windows = [(c['start'], c['start'] + c['duration']) for c in cards]
    drum_mask = smooth_mask(n, card_windows, ramp=0.4, floor=0.0)
    first_clip = min((c['start'] + c['duration'] for c in cards if c['kind'] == 'open'), default=0.0)
    intro = np.clip((np.arange(n, dtype=DT) / DT(SR) - DT(first_clip) + DT(0.5)) / DT(1.5), 0, 1)

    # Card FX: riser into each card and a soft impact on it.
    r_len = int(1.3 * SR)
    rt = np.arange(r_len) / SR
    for c in cards:
        noise = rng.standard_normal(r_len)
        sweep = np.sin(2 * np.pi * np.cumsum(220 + 900 * (rt / rt[-1]) ** 2) / SR)
        riser = (highpass(noise, 1800, 2) * 0.5 + sweep * 0.25) * (rt / rt[-1]) ** 2.2
        if c['kind'] != 'open':
            add(fx, c['start'] - 1.3, riser, 0.065)
        i_len = int(1.6 * SR)
        it = np.arange(i_len) / SR
        boom = np.sin(2 * np.pi * (48 + 30 * np.exp(-it / 0.05)) * it) * np.exp(-it / 0.45)
        tail = lowpass(rng.standard_normal(i_len), 2500) * np.exp(-it / 0.35)
        add(fx, c['start'], boom * 0.55 + tail * 0.1, 0.5)

    # Reverb send.
    # Buffers are combined in place and released as soon as possible to keep peak memory low.
    ir = reverb_ir().astype(DT)
    send = pad * DT(0.5)
    for track, g in ((arp, 0.6), (lead, 0.9), (claps, 0.5), (fx, 0.6)):
        send += track * DT(g)
    mix = np.empty((2, n), dtype=DT)
    for ch in range(2):
        mix[ch] = signal.oaconvolve(send, ir[ch])[:n] * DT(0.32)
    del send, ir, arp

    drums = kick + hats + claps
    del kick, hats, claps
    drums *= intro
    drums *= drum_mask
    mono = pad * duck
    mono += bass * duck * np.maximum(drum_mask, DT(0.55))
    del bass, intro, drum_mask
    mono += lead + drums + fx
    del lead, drums, fx
    arp_st *= duck
    del duck
    mix += mono
    mix += arp_st
    del mono, arp_st
    # Slight stereo width for pad.
    mix[0] += pad * DT(0.12)
    mix[1] -= pad * DT(0.12)
    del pad

    # Fade in / out, soft clip, normalize peak.
    t = np.arange(n, dtype=DT) / DT(SR)
    fade = np.clip(t / DT(1.8), 0, 1) * np.clip((DT(duration + 0.4) - t) / DT(5.0), 0, 1)
    del t
    mix *= fade
    del fade
    mix *= DT(1.1)
    np.tanh(mix, out=mix)
    mix /= DT(np.tanh(1.1))
    mix *= DT(0.89) / max(DT(1e-9), np.max(np.abs(mix)))
    pcm = (mix.T * 32767).astype(np.int16)
    with wave.open(out_path, 'wb') as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    return out_path


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    out_dir = os.path.normpath(os.path.join(here, '../../reports/demo-video'))
    edl_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(out_dir, 'edl.json')
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(out_dir, 'music.wav')
    edl = json.load(open(edl_path, encoding='utf-8'))
    cards = [{'start': it['at'], 'duration': it['duration'], 'kind': it['card']} for it in edl['items'] if it['type'] == 'card']
    render(edl['duration'], cards, out)
    print(f'written {out} ({edl["duration"]:.1f}s)')


if __name__ == '__main__':
    main()
