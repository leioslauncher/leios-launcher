"""Renders Home's sounds to launcher/app/src/main/res/raw/snd_*.ogg. All original: no samples.

One family, built in layers:
  - transient: a 1-2 ms burst of filtered noise, so a press feels instant;
  - body: a woody FM note (tuned to D major pentatonic) that says what happened;
  - thump: a short low pitch-drop, for weight;
  - air: a whisper of high noise, for a little sparkle on positive actions.
Everyday navigation gets only the first two, soft and warm, because it's heard thousands of times.
Weight and air are kept for actions that matter. Rising means forward or on, falling means back or
off, and only the error sound leaves the key.

    python tools/make-sounds.py            render, then print the measurements
    (needs numpy and soundfile)
"""
import os
import sys

import numpy as np
import soundfile as sf

RATE = 44100
# D major pentatonic, plus the one note outside it: the error's semitone clash.
D4, A4, AS4, D5, E5, FS5, A5, B5, D6 = (293.66, 440.0, 466.16, 587.33, 659.26, 739.99, 880.0, 987.77,
                                        1174.66)


def seconds(ms):
    return np.arange(int(RATE * ms / 1000)) / RATE


def lowpass(x, hz):
    a = 1 - np.exp(-2 * np.pi * hz / RATE)
    y = np.zeros_like(x)
    state = 0.0
    for i, s in enumerate(x):
        state += a * (s - state)
        y[i] = state
    return y


def highpass(x, hz):
    return x - lowpass(x, hz)


def transient(ms, tone_hz, decay_ms, seed):
    """The click: noise band-limited around tone_hz."""
    t = seconds(ms)
    noise = np.random.default_rng(seed).standard_normal(len(t))
    return lowpass(highpass(noise, tone_hz), tone_hz * 2.5) * np.exp(-t / (decay_ms / 1000))


def body(freq, ms, decay_ms):
    """A woody note: FM at an inharmonic ratio, whose brightness fades faster than its level."""
    t = seconds(ms)
    index = 1.4 * np.exp(-t / (decay_ms / 3500))
    voice = np.sin(2 * np.pi * freq * t + index * np.sin(2 * np.pi * freq * 2.76 * t))
    return voice * np.exp(-t / (decay_ms / 1000))


def thump(ms, start_hz=220, end_hz=130, decay_ms=16):
    """Weight, pitched for a small speaker that can't reach real bass."""
    t = seconds(ms)
    freq = end_hz + (start_hz - end_hz) * np.exp(-t / 0.012)
    return np.sin(2 * np.pi * np.cumsum(freq) / RATE) * np.exp(-t / (decay_ms / 1000))


def air(ms, decay_ms, seed):
    t = seconds(ms)
    noise = np.random.default_rng(seed).standard_normal(len(t))
    return highpass(noise, 6500) * np.exp(-t / (decay_ms / 1000))


def mix(ms, *layers):
    """layers: (start ms, samples, level)."""
    out = np.zeros(int(RATE * ms / 1000))
    for start, samples, level in layers:
        at = int(RATE * start / 1000)
        n = min(len(samples), len(out) - at)
        out[at:at + n] += samples[:n] * level
    return out


def finish(tonal, top_hz, click, peak_db, drive=0):
    """The tone goes through the warmth filter; the click stays on top of it, unfiltered."""
    y = lowpass(tonal, top_hz)
    if drive:
        # Soft saturation: more presence for the same peak, and warmer rather than harsher.
        y = np.tanh(y / np.max(np.abs(y)) * drive)
    y[:len(click)] += click * (np.max(np.abs(y)) if drive else 1)
    fade = min(int(RATE * 0.008), len(y))
    y[-fade:] *= 0.5 + 0.5 * np.cos(np.pi * np.arange(fade) / fade)
    return y / np.max(np.abs(y)) * 10 ** (peak_db / 20)


def focus():
    # Heard the most, so the softest: a dull woody tap. No weight, no air.
    return finish(mix(36, (0, body(A5, 36, 9), 1.0)), 3000, 0.22 * transient(4, 1800, 0.9, 1), -9)


def step():
    # Focus's sibling, a little higher and shorter, so a slider reads as a different gesture.
    return finish(mix(30, (0, body(B5, 30, 7), 1.0)), 3400, 0.22 * transient(4, 2200, 0.8, 2), -9)


def select():
    return finish(mix(80, (0, body(D5, 80, 20), 1.0), (0, thump(50, decay_ms=11), 0.55)), 5000,
                  mix(5, (0, transient(5, 3000, 1.1, 3), 0.28), (0, air(5, 1.4, 13), 0.05)), -3, drive=1.6)


def back():
    # Select's mirror: lower, duller, softer click.
    return finish(mix(80, (0, body(A4, 80, 18), 1.0), (0, thump(50, 200, 120, 11), 0.5)), 2800,
                  0.18 * transient(5, 1800, 1.0, 4), -4)


def open_sheet():
    return finish(mix(130, (0, body(A4, 80, 22), 0.75), (42, body(D5, 88, 28), 1.0),
                      (0, thump(50, decay_ms=11), 0.4)), 5000,
                  0.4 * transient(5, 2600, 1.0, 5), -3)


def close_sheet():
    return finish(mix(120, (0, body(D5, 80, 20), 0.75), (40, body(A4, 80, 26), 1.0)), 3000,
                  0.3 * transient(5, 1800, 1.0, 6), -4)


def toggle_on():
    return finish(mix(110, (0, body(D5, 60, 18), 0.8), (38, body(A5, 72, 24), 1.0),
                      (0, thump(40, decay_ms=9), 0.35)), 6500,
                  mix(110, (0, transient(5, 3000, 1.0, 7), 0.45), (38, air(72, 12, 17), 0.035)), -3)


def toggle_off():
    return finish(mix(100, (0, body(A5, 60, 16), 0.8), (38, body(D5, 62, 22), 1.0)), 3000,
                  0.3 * transient(5, 1800, 1.0, 8), -4)


def error():
    # Dull and low, a soft semitone clash, twice. Firm without being an alarm.
    def thud():
        return mix(60, (0, body(A4, 60, 24), 0.8), (0, body(AS4, 60, 24), 0.8), (0, thump(50, 170, 100, 16), 0.5))
    return finish(mix(130, (0, thud(), 1.0), (66, thud(), 0.72)), 1500, 0.15 * transient(4, 1000, 1.0, 9), -3)


def launch():
    # The one that matters most on Home: full weight, a rising fifth into the octave, and air.
    return finish(mix(150, (0, body(D5, 90, 30), 0.8), (0, body(A5, 90, 30), 0.5), (56, body(D6, 94, 40), 0.9),
                      (0, thump(60), 0.8)), 7000,
                  mix(150, (0, transient(5, 3200, 1.2, 10), 0.3), (56, air(94, 16, 20), 0.07)), -3, drive=3)


def startup():
    return finish(mix(150, (0, body(D4, 150, 60), 0.8), (36, body(A4, 114, 56), 0.8), (72, body(D5, 78, 60), 1.0),
                      (0, thump(60, 200, 110, 20), 0.5)), 5000,
                  mix(150, (0, transient(5, 2000, 1.2, 11), 0.25), (72, air(78, 22, 21), 0.05)), -3)


SOUNDS = {
    "focus": focus, "step": step, "select": select, "back": back, "open": open_sheet, "close": close_sheet,
    "toggle_on": toggle_on, "toggle_off": toggle_off, "error": error, "launch": launch, "startup": startup,
}


def band_share(x, low, high):
    power = np.abs(np.fft.rfft(x)) ** 2
    freq = np.fft.rfftfreq(len(x), 1 / RATE)
    return 100 * power[(freq >= low) & (freq < high)].sum() / power.sum()


def measure(name, x, size):
    peak = np.max(np.abs(x))
    attack = np.argmax(np.abs(x) >= 0.7 * peak) / RATE * 1000
    rms = 20 * np.log10(np.sqrt(np.mean(x ** 2)))
    print("%-11s %3d ms  peak %5.1f dB  loudness %6.1f dB  attack %4.1f ms  low %3.0f%%  mid %3.0f%%  high %4.1f%%  %d B"
          % (name, len(x) / RATE * 1000, 20 * np.log10(peak), rms, attack,
             band_share(x, 0, 350), band_share(x, 350, 4000), band_share(x, 4000, 22050), size))


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(root, "launcher", "app", "src", "main", "res", "raw")
    os.makedirs(out, exist_ok=True)
    for name, make in SOUNDS.items():
        path = os.path.join(out, "snd_%s.ogg" % name)
        data = make()
        sf.write(path, data, RATE, format="OGG", subtype="VORBIS")
        measure(name, data, os.path.getsize(path))


if __name__ == "__main__":
    sys.exit(main())
