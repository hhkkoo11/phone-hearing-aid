import math
import random

SAMPLE_RATE = 44100
SECONDS = 3
N = SAMPLE_RATE * SECONDS


def high_pass(samples, alpha=0.97):
    prev_in = 0.0
    prev_out = 0.0
    out = []
    for x in samples:
        y = alpha * (prev_out + x - prev_in)
        prev_in = x
        prev_out = y
        out.append(y)
    return out


def voice_shape(samples):
    prev = 0.0
    smooth = 0.0
    out = []
    for x in samples:
        edge = x - prev
        prev = x
        smooth = smooth * 0.72 + edge * 0.28
        out.append(x + smooth * 0.32)
    return out


def compress_limit(x, limit):
    sign = -1 if x < 0 else 1
    a = abs(x)
    knee = limit * 0.70
    if a > knee:
        a = knee + (a - knee) * 0.25
    return sign * min(a, limit)


def process(samples, gain, output_limit, old_bone):
    shaped = voice_shape(high_pass(samples))
    out = []
    avg = sum(abs(x) for x in shaped) / len(shaped)
    quiet_noise = avg < 520
    for x in shaped:
        a = abs(x)
        if not old_bone:
            if a < 260:
                x *= 0.12
            elif a < 620:
                x *= 0.38 if quiet_noise else 0.62
            if quiet_noise:
                x *= 0.72
            a = abs(x)
            if 45 < a < 2200:
                x *= 1.12
        else:
            if 45 < a < 2200:
                x *= 1.45
        out.append(compress_limit(x * gain, 32767 * output_limit))
    return out


def rms(xs):
    return math.sqrt(sum(x * x for x in xs) / len(xs))


def synth(noise_only=False):
    random.seed(7)
    wind = 0.0
    out = []
    for i in range(N):
        t = i / SAMPLE_RATE
        wind = 0.985 * wind + random.uniform(-1, 1) * 50
        electrical = math.sin(2 * math.pi * 60 * t) * 45 + random.uniform(-1, 1) * 120
        voice = 0.0
        if not noise_only and 0.7 < t < 2.4:
            envelope = min(1.0, (t - 0.7) * 4, (2.4 - t) * 4)
            voice = envelope * (
                math.sin(2 * math.pi * 170 * t) * 900
                + math.sin(2 * math.pi * 620 * t) * 360
                + math.sin(2 * math.pi * 1450 * t) * 180
            )
        out.append(voice + wind + electrical)
    return out


noise = synth(noise_only=True)
mixed = synth(noise_only=False)
voice_component = [m - n for m, n in zip(mixed, noise)]

old_noise = process(noise, 16.0, 0.90, True)
new_noise = process(noise, 18.0, 0.90, False)
old_mixed = process(mixed, 16.0, 0.90, True)
new_mixed = process(mixed, 18.0, 0.90, False)
old_voice = process(voice_component, 16.0, 0.90, True)
new_voice = process(voice_component, 18.0, 0.90, False)

print(f"old_noise_rms={rms(old_noise):.1f}")
print(f"new_noise_rms={rms(new_noise):.1f}")
print(f"old_voice_rms={rms(old_voice):.1f}")
print(f"new_voice_rms={rms(new_voice):.1f}")
print(f"old_voice_to_noise={rms(old_voice)/max(1,rms(old_noise)):.2f}")
print(f"new_voice_to_noise={rms(new_voice)/max(1,rms(new_noise)):.2f}")
print(f"old_mixed_rms={rms(old_mixed):.1f}")
print(f"new_mixed_rms={rms(new_mixed):.1f}")
