# fx_reflex

### a reverb that moves

A plate-class reverb built on Dattorro's topology, turned into something more restless. Two specialized modulators reshape the hall in real time: a shift register (modulation™) shifts the character of the space, and an envelope follower lets your playing dynamics control the intensity.

Built for the [norns fx mod framework](https://llllllll.co/t/fx-mod-framework/). The name is a double meaning: a reflex is both an involuntary response – the way the reverb reacts to your playing dynamics – and a reflection, which is what reverberation physically is. Sound reflects off surfaces. The room reflects your input. The modulators reflect each other.

No external UGens required.

---

## How it got here

fx_reflex started as a learning exercise. After building fx_llll – a multitap delay with a shift register and event system – the next question was whether the same modulation architecture could work on a reverb. The answer turned out to be yes, but the interesting part was how differently everything behaved once the modulation targets stopped being concrete and became abstract.

The reverb algorithm is Jon Dattorro's plate topology from his 1997 AES paper "Effect Design." It's a figure-eight network of allpass diffusers, delay lines, and damping filters – the smallest recursive structure he found that produces convincing reverberation. The implementation started from a SuperCollider adaptation by khoin, which provided the tank structure and output tap positions. From there, the design diverged.

The first addition was `size` and `spread`. Size scales all delay times uniformly – a bigger or smaller room. Spread is more interesting: it controls how much the individual delay times diverge from their mean. At spread=1, you get Dattorro's original room. At spread=0, all delays converge – the room collapses into a metallic resonance. Above 1, differences amplify – short delays get shorter, long ones get longer, and the room geometry stretches. The formula is multiplicative (`center × (original/center)^spread`) so delays can never go negative and the scaling feels symmetrical.

The modulation™ came over from fx_llll almost directly, but the targets changed. In a delay, you modulate tap times and feedback amounts – concrete, per-voice parameters. In fx_llll, a modulation™ step produces up to four values simultaneously – one per delay line, each reading the shift register from a different bit rotation. In a reverb, the interesting targets are more abstract: damping (bright to dark), size (room scale), spread (room geometry), input diffusion (how much the incoming signal gets smeared before entering the tank). These are all "character" parameters – they change *how* the reverb sounds, not *how much* of it there is.

The envelope follower came from a different direction entirely. The modulation™ is rhythmic and structural – it changes the room on a grid. The envelope follower is dynamic and reactive – it lets the room respond to how you play. Loud passages can make the decay longer, or push more signal into the tank, or drive the feedback saturation harder. It handles the "amount" domain – parameters that control intensity rather than character.

The two domains never overlap, so both modulators can run simultaneously without conflict. The only feedback loop is in the audio path itself: the tank's recirculation reacts to the modulated parameters, which in turn shapes the input amplitude that the envelope follower tracks. That loop is intentional.

The saturation parameter controls how hard the signal hits the tanh limiter in the feedback path. At 0%, the limiter is nearly transparent – it's just a safety net. At higher values, the drive increases and the feedback path starts to color the sound. At extreme settings, the hall becomes a distortion effect where each recirculation adds harmonic density. This was always implicit in Dattorro's design – the tanh was there for safety – but making it a controllable parameter turns a protection mechanism into a creative tool.

The inspirations for this approach came from hardware reverbs that treat the algorithm as an instrument rather than an emulation. Make Noise's Erbe-Verb, the Mimeophon, and Qu-Bit's Aurora share a philosophy: deep modulation access, open architecture, and the deliberate refusal to sound like a conventional room. These are reverbs that musicians use as voices – not because they can't do traditional hall sounds, but because the interesting territory lies in the spaces between familiar categories. The tradeoff is real: if you want a convincing concert hall, these are not the right tools. But if you want a reverb that rewards curiosity the way a synthesizer does, that's exactly what fx_reflex tries to be.

---

## Install

**Via Maiden (recommended):** Open `http://norns.local/maiden`, type the following into the matron REPL at the bottom:

```
;install https://github.com/notrobintaylor/fx_reflex
```

Restart norns, activate under **SYSTEM > MODS**, restart again.

**Via SSH (manual):**

```bash
ssh we@norns.local
cd ~/dust/code
git clone https://github.com/notrobintaylor/fx_reflex.git fx_reflex
```

Restart norns, activate under **SYSTEM > MODS**, restart again.

**File structure for reference:**

```
dust/code/fx_reflex/
├── lib/
│   └── mod.lua
└── reflex.sc
```

---

## Signal flow

```
input -------> envelope follower (amplitude --> Lua)
  |                  |
  x input gain       +---> envelope target (amount)
  |
  v
mono sum --> predelay --> bandwidth LP
  |
  v
4x input allpass (diffusion)
  |
  v
+--------------------  TANK  --------------------+
|                                                |
|  +-- Branch 1 ------+  +-- Branch 2 ------+    |
|  | AllpassC ~ (mod) |  | AllpassC ~ (mod) |    |
|  | Delay            |  | Delay            |    |
|  | Damping LP       |  | Damping LP       |    |
|  | x decay          |  | x decay          |    |
|  | Allpass          |  | Allpass          |    |
|  | Delay            |  | Delay            |    |
|  +------------------+  +------------------+    |
|      |                      |                  |
|      +----- cross ----------+                  |
|           x decay --> x satDrive --> tanh      |
|                                                |
|  14 output taps --> wetL, wetR                 |
+------------------------------------------------+
  |
  v
width (mid/side stereo)
  |
  v
tilt (spectral shelf)
  |
  v
HPF 60hz --> stereo out
```

The tank is a figure-eight: Branch 1's output crosses into Branch 2's input and vice versa. Every recirculation passes through both branches, both damping filters, and both diffusion stages. The tanh on the feedback path soft-clips the signal, preventing digital clipping at high decay values. The saturation parameter controls how hard the signal hits this limiter – at 0% it's a safety net, at 100% it's a distortion effect.

After the tank, the wet signal passes through two output processors: width controls the stereo image via mid/side processing, and tilt shifts the spectral balance via a first-order shelf at ~1 kHz.

The envelope follower tracks input amplitude in SuperCollider and sends it to Lua ~30 times per second. Each amplitude value modulates the envelope follower's target (decay, input gain, saturation, or mod depth).

---

## Parameters

### Slot

| Parameter | Options |
|-----------|---------|
| **slot** | none / send a / send b / insert |

### Reverb

| Parameter | Range | Unit | Default |
|-----------|-------|------|---------|
| **predelay** | 0–500 | ms | 100 |
| **input gain** | 0–100 | % | 25 |
| **decay** | 0–100 | % | 50 |
| **damping** | 0–100 | % | 25 |
| **saturation** | 0–100 | % | 0 |
| **input diffusion** | 0–100 | % | 25 |
| **size** | 0.10x–3.00x | – | 1.00x |
| **spread** | 0.00x–2.00x | – | 1.00x |
| **width** | 0–200 | % | 100 |
| **tilt** | -100 to +100 | % | 0 |

**predelay** is the gap between the dry signal and the first reflection. At 0 ms the reverb is immediate – good for pad-like washes. At 100–200 ms the space feels large and the reverb sits behind the dry signal.

**input gain** controls how much signal enters the tank. At 0% nothing new goes in, but existing reflections ring out. Useful as an envelope follower target: loud playing pushes more signal into the reverb.

**decay** is how much energy survives each trip around the tank. At 50% the hall is moderate. At 90%+ it sustains almost indefinitely. The tanh limiter catches anything that tries to grow beyond unity.

**damping** controls high-frequency absorption in the tank. At 0% the reverb is bright and glassy. At 25% it's warm. At 70%+ high frequencies die within a few recirculations – the hall gets dark fast.

**saturation** drives the signal harder into the tanh limiter in the feedback path. At 0% the limiter is transparent. At 30% you get warmth and subtle compression. At 100% each recirculation adds harmonic density – the hall becomes an overdrive.

**input diffusion** sets how much the incoming signal is decorrelated before entering the tank. At 25% (default) transients keep their attack while the tank fills evenly. At 0% the incoming signal enters sharp – you hear the tank's structure more clearly. At 75%+ the signal is heavily smeared before it hits the tank. Internally the diffusion coefficient is capped just below 1 to keep the input allpasses stable.

**size** scales all delay times in the tank uniformly. At 1.00x you get Dattorro's original room geometry. Below 1 the room shrinks – reflections arrive faster, the space feels tighter. Above 1 the room expands – longer reflections, bigger space, and pitch-shifting on the tail as the delays catch up.

**spread** controls how much the individual delay times diverge from their average. At 1.00x the original Dattorro ratios are preserved. At 0.00x all delays converge to the same length – the room becomes a metallic comb filter. Above 1.00x the differences amplify – short delays get shorter, long ones get longer, and the room geometry stretches into unusual shapes.

**width** controls the stereo image of the wet signal via mid/side processing. At 0% the reverb is mono. At 100% the original stereo image from the 14 tank taps. At 200% the side information is doubled – an exaggerated, wide stereo field.

**tilt** shifts the spectral balance of the wet output via a first-order shelf at ~1 kHz. At 0 the output is neutral. At -100% the bass is boosted and treble cut – warm and dark. At +100% the treble is boosted and bass cut – thin and bright. This is independent of damping: damping changes the tank's internal decay character per recirculation, tilt changes what you hear at the output once.

### Tank modulation

| Parameter | Range | Unit | Default |
|-----------|-------|------|---------|
| **mod depth** | 0–100 | % | 0 |
| **mod rate** | 0.01–10000 | hz | 0.25 |

Two allpass delays in the tank are modulated by sine oscillators. The second oscillator runs at 80% of `mod rate` and starts at the opposite phase, so the two branches drift in and out of opposition rather than locking. This breaks up the fixed resonances of the network and makes the reverb sound denser.

**mod depth** at 0% means no modulation – the tank resonances are fixed. At 10–20% the reverb gains a subtle shimmer. At higher values the pitch-shifting becomes audible – the echoes wobble.

**mod rate** at 0.5–2 hz gives classic tape-style drift. At higher rates the modulation becomes a chorus effect. The range extends to 10000 hz deliberately: at extreme rates with high depth, the reverb becomes a ring modulator. The boundary between chorus and FM synthesis is where the interesting things happen.

### modulation™

A shift register inspired by Tom Whitwell's [Turing Machine](https://musicthing.co.uk/pages/turing.html). Set **steps > off** to activate. Targets the character domain: parameters that change *how* the reverb sounds.

| Parameter | Range | Default |
|-----------|-------|---------|
| **mod assign** | damping / size / spread / input diffusion | size |
| **mod depth** | 0–100 % | 100 |
| **mod direction** | + / - / + & - | - |
| **slew rate** | 0–2000 ms | 0 |
| **step rate** | 4/1–1/16 | 1/4 |
| **step stability** | 0–100 % | 50 |
| **steps** | off / 1–16 | off |

**step stability** controls pattern mutation. At 100% the pattern is locked – it repeats exactly every N steps. At 0% every step is fully random. At 50% the pattern drifts slowly, recognizable but evolving.

**mod depth** limits the swing to ±100% of the base value. A parameter set to 50% can be modulated up to 100% or down to 0%, but not beyond.

**mod direction** offers three modes: **+** (high register value pushes the parameter up), **-** (high register value pushes it down), and **+ & -** (bipolar – the register swings both ways from the base value).

**slew rate** at 0 ms means instant steps. At 500 ms transitions are smooth. At 2000 ms the shift register's discrete steps dissolve into slow, flowing movement.

Parameters being modulated by the modulation™ are marked with **(M)** in the parameter menu.

### envelope follower

Tracks input amplitude and modulates the amount domain: parameters that control *how much* of the reverb effect is applied. Set **sensitivity > off** to activate.

| Parameter | Range | Default |
|-----------|-------|---------|
| **target** | decay / input gain / saturation / mod depth | decay |
| **sensitivity** | off / 1–100 % | off |
| **direction** | + / - | + |
| **slew rate** | 0–2000 ms | 100 |
| **attack** | 1–1000 ms | 10 |
| **release** | 10–2000 ms | 100 |

**sensitivity** at off disables the envelope follower. At 50% the input amplitude has moderate influence. At 100% the full dynamic range is mapped.

**direction +** means loud = parameter goes up. Loud playing → longer decay, more gain, more saturation, or more mod depth. **Direction -** inverts: loud = parameter goes down.

**slew rate** smooths the resulting parameter changes independently of the modulation™'s slew. At 100 ms (default) the changes are perceptible but not jarring.

**attack** shapes how fast the follower responds to increases in amplitude. At 10 ms it catches transients. At 500 ms+ it averages over longer phrases – good for ambient playing where you want the room to respond to overall volume rather than individual notes.

**release** shapes how fast the follower responds to decreases. At 100 ms it drops quickly. At 1000 ms+ it holds the level, creating a slow fade-out of the modulation effect after you stop playing.

---

## Recipes

**Clean plate.** Default settings, no modulators active. Predelay = 80 ms, decay = 55%, damping = 25%. A warm, well-behaved plate reverb. Start here.

**Breathing room.** modulation™: steps = 8, mod assign = size, mod depth = 40%, mod direction = + & -, step rate = 1/2, stability = 70%, slew = 500 ms. The room gently expands and contracts, as if the walls were breathing. Keep spread at 1.00x to preserve the geometry.

**Dynamic decay.** Envelope follower: target = decay, sensitivity = 60%, direction = +, attack = 10 ms, release = 500 ms. Play loud, the hall sustains. Play soft, it pulls back. The reverb follows your phrasing.

**Geometry shift.** modulation™: steps = 12, mod assign = spread, mod depth = 80%, mod direction = + & -, step rate = 1/4, stability = 40%. The room's shape constantly changes – sometimes tight and metallic, sometimes stretched and diffuse. The Dattorro geometry is just one stop on a continuum.

**Tape wobble.** Mod depth = 15%, mod rate = 0.3 hz. No modulation™, no envelope follower. Just the tank's built-in modulation, slow and wide. Add decay = 70%, damping = 40%. The echoes shimmer like a worn tape machine.

**Saturated feedback.** Saturation = 50%, decay = 85%, damping = 50%. The hall gets dirtier with each pass through the tank. Add envelope follower: target = saturation, sensitivity = 40%, direction = +. Loud playing drives the feedback harder.

**Frozen room.** Decay = 95%, input gain = 0%. Play something, then drop the gain. The reverb tail holds almost indefinitely while nothing new enters. Slowly increase damping to watch the frozen sound darken.

**Ambient swell.** Envelope follower: target = input gain, sensitivity = 80%, direction = +, attack = 500 ms, release = 2000 ms, slew = 500 ms. Decay = 75%. The reverb builds slowly as you play louder and fades slowly when you stop. The long attack smooths out individual notes.

---

## User stories

### 1.0

**Slot management**

- I want send a and send b to work independently of the fx mod so that I can route the reverb to the norns send buses without the fx framework's replacer synth being involved.

- I want the insert dry/wet blend to follow an equal power curve (cosine for dry, sine for wet) so that the perceived loudness stays constant at any blend position — no −3 dB dip at 50%.

- I want slot switching to be click-free so that changing between none, send a, send b, and insert during a live performance is sonically transparent.

- I want a short fade on the fx send level when switching slots so that the audio transitions smoothly without abrupt gain changes on the send bus.

- I want fx spillover when I deselect a slot so that the reverb tank keeps running freely — the fx send input is muted (faded), but the tail rings out in full for as long as the decay dictates, whether that is two seconds or indefinite.

- I want the fx send input to stay muted until a new slot is selected so that no dry signal leaks into an unowned effect bus between slot changes.

## Safety

fx_reflex allows decay up to 100% and saturation up to 100%. At extreme settings, the feedback path can produce loud, spectrally dense audio. The tanh limiter prevents digital clipping, but the resulting sound can still be intense.

**Recommendations:**

- **Use a limiter** on the norns output or on the next device in your signal chain.
- **Start at low volume** when experimenting with high decay and saturation.
- **The envelope follower is your safety valve.** Set target = input gain, direction = -, sensitivity = 30%. When things get loud, less signal enters the tank.
- **Protect your hearing.** This is not a disclaimer. It's advice from someone who has been surprised by feedback loops more than once.

---

## Known issues

- **Size at extreme values:** Very large size values (>2.5x) combined with high spread can push delay times to their maximum (1 second). The sound may clip or alias. If the reverb sounds wrong, reduce size or spread.
- **Envelope follower latency:** The ~33 ms update interval (30 Hz) means the follower cannot track sub-bass modulation or very fast transients. This is by design – faster updates would overload the OSC bus.

---

## Dependencies

- [fx mod framework](https://llllllll.co/t/fx-mod-framework/)

---

## Credits

The reverb algorithm is Jon Dattorro's plate topology from [Effect Design, Part 1](https://ccrma.stanford.edu/~dattorro/EffectDesignPart1.pdf) (AES, 1997) – the smallest recursive network he found that produces good-sounding reverberation, and a masterclass in making complex DSP accessible.

The SuperCollider implementation started from [khoin's adaptation](https://github.com/khoin/dx463-final/) of the Dattorro topology, which provided the tank structure and output tap positions.

The modulation™ section is directly inspired by Tom Whitwell's [Turing Machine](https://musicthing.co.uk/pages/turing.html) for Eurorack – the same shift register concept that drives fx_llll's modulation system, adapted here for reverb parameters instead of delay taps.

Built on sixolet's [fx mod framework](https://llllllll.co/t/fx-mod-framework/), which made it possible to run custom effects alongside any norns script.

The design philosophy – reverb as a modulatable instrument rather than a room emulation – draws from hardware that treats the algorithm as a voice: **Make Noise Erbe-Verb**, whose CV inputs turn a reverb into a playable resonator. **Make Noise Mimeophon**, which blurs the line between delay, reverb, and sampler. And **Qu-Bit Aurora**, whose granular approach to reverberation shows that the space between categories is where the interesting sounds live. These are reverbs that musicians keep returning to not because they sound like rooms, but because they don't – and that inexhaustibility is what fx_reflex tries to be.
