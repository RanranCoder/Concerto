#!/usr/bin/env python3
"""sfx_score.py — 猫猫与毛线球 (pams-cat-yarn-30s) score, on scripts/sfx_palette.py.

One material set, one room. Fewer events than beats: the roll has no per-cell sound,
the burst has three felt hits, the stamp is the loudest moment of the film.
Run:  py sfx_score.py  →  sfx.wav (48 kHz, peak -8 dBFS)
"""
import os, sys
sys.path.insert(0, r"C:\Users\zhara\.claude\skills\onetake\scripts")
from sfx_palette import Score, air, glass, wood, sub, bubble, wobble, pan_of
import numpy as np
rng = np.random.default_rng(11)
s = Score(dur=30.0, T60=1.1)
place = s.place

# 0–1.5 · opening: paw press, ball born
place(wood(170, 0.14), 1.08, 0.55, 0.15, 0.25)                    # paw thud
place(sub(58, 0.5), 1.10, 0.28, 0.15, 0.35)
place(bubble(430, 0.28), 1.14, 0.42, 0.15, 0.3)                   # ball pop
place(air(0.5, 180, 1600, 1.1, 0.5), 1.20, 0.20, 0.15, 0.5, pan_to=-0.3)

# 1.6–2.3 · the roll: a note flips, the ball runs
place(wood(240, 0.08), 1.62, 0.26, -0.25, 0.2)                    # note knocked
place(air(0.7, 200, 1400, 1.4, 0.45), 1.55, 0.30, 0.2, 0.55, pan_to=-0.45)
place(wood(220, 0.07), 1.98, 0.18, -0.35, 0.2)                    # second prop

# 2.9–4.7 · cells snap: three ticks only, not ten
for t, g in ((3.05, 0.34), (3.55, 0.28), (4.30, 0.26)):
    place(wood(195 + rng.uniform(-25, 25), 0.07), t, g, -0.15, 0.22)

# 5.36–6.52 · burst: three felt hits
for t, g, f in ((5.36, 0.52, 74), (5.74, 0.46, 70), (6.10, 0.58, 66)):
    place(sub(f, 0.42), t, g, 0.0, 0.28)
    place(wood(260, 0.07), t, g * 0.4, 0.0, 0.2)

# 7.1–8.6 · cards gather into a stack
place(air(0.42, 240, 2600, 1.3, 0.35), 7.08, 0.32, -0.35, 0.5, pan_to=-0.1)
for i, t in enumerate((7.74, 7.96, 8.18)):
    place(bubble(420 + rng.uniform(-40, 40), 0.2), t, 0.22, -0.2, 0.3)

# 10.05–11.42 · the knot tightens, the stamp lands (the loudest beat)
place(air(0.3, 1200, 300, 1.4), 10.35, 0.26, 0.1, 0.45)
place(wobble(140, 0.5), 10.55, 0.28, 0.1, 0.35)
place(sub(52, 0.8), 11.42, 0.72, 0.05, 0.45)                      # stamp thud
place(wood(150, 0.1), 11.42, 0.40, 0.05, 0.25)
place(glass(880, 0.9, 0.5), 11.52, 0.12, 0.05, 0.55)

# 13.3–16.4 · the plan weaves out, fields type, approved
place(air(0.55, 2600, 300, 1.4, 0.35), 13.32, 0.38, 0.05, 0.55, pan_to=0.4)
for t in (14.55, 15.35):                                          # two typing flutters, not twenty keys
    place(wood(320, 0.05), t, 0.10, 0.35, 0.18)
    place(wood(300, 0.05), t + 0.07, 0.08, 0.35, 0.18)
place(glass(1318, 1.2, 0.5), 16.42, 0.22, 0.35, 0.6)              # 已通过

# 17.6–21 · the ball rolls home and the bow pops
place(air(0.85, 2200, 220, 1.5, 0.4), 18.30, 0.42, 0.35, 0.55, pan_to=0.0)
place(sub(66, 0.55), 19.85, 0.52, 0.0, 0.4)                       # caught
place(bubble(500, 0.3), 19.90, 0.30, 0.0, 0.35)
place(bubble(620, 0.26), 20.22, 0.38, 0.05, 0.4)                  # bow

# 23.4–30 · the lockup settles, one chime, the blink
place(air(0.6, 160, 1800, 1.1, 0.55), 23.38, 0.30, 0.0, 0.6)
place(sub(60, 0.9), 23.55, 0.34, 0.0, 0.45)                       # wordmark lands
place(glass(1046, 1.4, 0.45), 25.05, 0.18, 0.0, 0.65)             # tagline
place(glass(1568, 1.6, 0.35), 26.60, 0.12, 0.0, 0.7)              # org line / hold breath
place(glass(1976, 0.5, 0.3), 28.60, 0.10, 0.0, 0.6)               # the blink

# ── music bed: an original music-box motif (synthesised, ours to license) ──────────────────
# C major pentatonic; gentle, always below the sfx (the sfx own -8 dBFS)
N = {'C4':262, 'D4':294, 'E4':330, 'G4':392, 'A4':440, 'C5':523, 'D5':587, 'E5':659, 'G5':784, 'A5':880, 'C6':1046}
def box(note, t, g, d=1.4, pan=0.0, send=0.55):
    place(glass(N[note], d, 0.4), t, g, pan, send)
def bass(note, t, g=0.14, d=1.6):
    place(sub(N[note] / 2, d), t, g, 0.0, 0.4)

# 0–5.3 · curious opening — three lone notes while the cat peeks and the ball rolls
box('E5', 0.45, 0.10); box('G5', 1.85, 0.11, pan=-0.2); box('D5', 3.30, 0.10, pan=-0.35)
bass('C4', 0.45, 0.12); bass('A4', 3.30, 0.10)
# 5.36–6.52 · the burst owns the silence — music stays out

# 6.5–14 · the working phrases: soft eighth arpeggios, two bars looping
ARP1 = ['C5','E5','G5','E5','A5','G5','E5','D5']
ARP2 = ['D5','G5','A5','G5','C6','A5','G5','E5']
for i in range(8):
    box(ARP1[i], 6.60 + i * 0.34, 0.055 + 0.02 * (i % 2 == 0), 1.2, pan=-0.25 + i * 0.06)
for i in range(8):
    box(ARP2[i], 9.55 + i * 0.34, 0.055 + 0.02 * (i % 2 == 0), 1.2, pan=-0.1 + i * 0.05)
bass('C4', 6.60, 0.11); bass('A4', 9.55, 0.11); bass('G4', 11.42, 0.13)
# 13.3–17.6 · brighter variation while the plan weaves itself
ARP3 = ['E5','G5','A5','C6','A5','G5','E5','G5']
for i in range(8):
    box(ARP3[i], 13.70 + i * 0.36, 0.06, 1.2, pan=0.25 - i * 0.05)
bass('C4', 13.70, 0.11); bass('E4', 16.42, 0.12)
# 17.6–21 · the run home: a descending line with the ball
for i, nt in enumerate(['A5','G5','E5','D5','C5','A4']):
    box(nt, 18.05 + i * 0.30, 0.07, 1.1, pan=0.3 - i * 0.1)
bass('C4', 19.85, 0.15)
# 21–23.4 · the held breath: one chord, nothing else
for nt in ('C5', 'E5', 'G5'): box(nt, 21.55, 0.07, 2.2)
# 23.4–30 · the lockup: a warm ascending arpeggio, then a long sustain
for i, nt in enumerate(['C5','E5','G5','C6']):
    box(nt, 24.55 + i * 0.115, 0.09, 1.6)
for nt in ('C5', 'G5', 'C6'): box(nt, 25.85, 0.075, 2.4, send=0.7)
bass('C4', 24.55, 0.14, 2.6); bass('C4', 26.35, 0.10, 3.0)
for nt in ('E5', 'G5', 'C6'): box(nt, 27.30, 0.05, 3.2, send=0.75)

s.write(os.path.join(os.path.dirname(os.path.abspath(__file__)), "sfx.wav"))
print("wrote sfx.wav")
