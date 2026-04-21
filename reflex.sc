// =========================================================================
// FxReflex — creative reverb with modulation
// =========================================================================
//
// A plate-class reverb based on Dattorro's topology (AES 1997),
// adapted for the norns fx mod framework.
//
// No external UGens required.
// =========================================================================

FxReflex : FxBase {

    *new {
        var ret = super.newCopyArgs(nil, \none, (
            preDelay: 0.1,
            inputGain: 0.25,
            decay: 0.5,
            damping: 0.064,
            saturation: 0,
            bandwidth: 0.5,
            inputDiffusion: 0.25,
            modDepth: 0,
            modRate: 0.25,
            size: 1.0,
            spread: 1.0,
            width: 1.0,
            tilt: 0,
            envAttack: 0.01,
            envRelease: 0.1,
            slew: 0,
        ), nil, 0.5);
        ^ret;
    }

    *initClass { FxSetup.register(this.new); }
    subPath { ^"/fx_reflex"; }
    symbol { ^\fxReflex; }

    addSynthdefs {
        SynthDef(\fxReflex, {|inBus, outBus|

            // ---- ALL VAR DECLARATIONS (SC requires these at block top) ----
            var dSR = 29761;
            var exMax = 24;
            var gFacT60 = { |delay, gFac|
                gFac.sign * (-3 * delay / log10(gFac.abs));
            };

            var slew       = \slew.kr(0);
            var inputGain  = \inputGain.kr(0.25).lag(slew);
            var decay      = \decay.kr(0.5).lag(slew);
            var damping    = \damping.kr(0.0005).lag(slew);
            var saturation = \saturation.kr(0).lag(slew);
            var bandwidth  = \bandwidth.kr(0.5);
            var modDepth   = \modDepth.kr(0.2).lag(slew);
            var modRate    = \modRate.kr(0.25).lag(slew);
            var size       = \size.kr(1.0).lag(0.3);
            var spread     = \spread.kr(1.0).lag(0.3);
            var width      = \width.kr(1.0).lag(slew);
            var tilt       = \tilt.kr(0).lag(slew);

            var diffUser   = \inputDiffusion.kr(0.25).lag(slew);
            var diff1      = diffUser;
            var diff2      = (diffUser * 0.833).clip(0, 0.75);
            var decayDiff2 = (decay + 0.15).clip(0.25, 0.5);
            var satDrive   = 1 + (saturation * 9);

            var idCenter = 200;
            var tkCenter = 2240;
            var si = { |samples|
                (idCenter * (samples / idCenter).pow(spread) / dSR * size).clip(0.00003, 0.1);
            };
            var st = { |samples|
                (tkCenter * (samples / tkCenter).pow(spread) / dSR * size).clip(0.00003, 1);
            };
            var modExc = (exMax / dSR) * size;

            var input = In.ar(inBus, 2);
            var envFollow = Amplitude.kr(
                Mix.ar(input) * 0.5,
                \envAttack.kr(0.01),
                \envRelease.kr(0.1)
            );
            var mono = Mix.ar(input) * 0.5 * inputGain;
            var fb = LocalIn.ar(1);
            var wetL = Silent.ar;
            var wetR = Silent.ar;
            var tank, tank2;
            var mid, side, tiltAbs, wet, wetLP, wetHP;

            // ---- ENVELOPE FOLLOWER → LUA ----
            SendReply.kr(Impulse.kr(30), '/fx_reflex/env', envFollow);

            // ---- PREDELAY → BANDWIDTH ----
            mono = DelayN.ar(mono, 0.5, \preDelay.kr(0.1).lag(0.1));
            mono = OnePole.ar(mono, 1 - bandwidth);

            // ---- INPUT DIFFUSION ----
            mono = AllpassN.ar(mono, 0.1, si.(142), gFacT60.(si.(142), diff1));
            mono = AllpassN.ar(mono, 0.1, si.(107), gFacT60.(si.(107), diff1));
            mono = AllpassN.ar(mono, 0.1, si.(379), gFacT60.(si.(379), diff2));
            mono = AllpassN.ar(mono, 0.1, si.(277), gFacT60.(si.(277), diff2));

            // ---- TANK: BRANCH 1 ----
            tank = AllpassC.ar(
                mono + (decay * fb),
                maxdelaytime: 1,
                delaytime: st.(672) + (modExc * SinOsc.ar(modRate, 0, modDepth)),
                decaytime: gFacT60.(st.(672), -0.7)
            );

            wetL = (0.6 * DelayN.ar(tank, 1, st.(1990)).neg) + wetL;
            wetR = (0.6 * tank) + wetR;
            wetR = (0.6 * DelayN.ar(tank, 1, st.(3627))) + wetR;

            tank = DelayN.ar(tank, 1, st.(4453));
            tank = OnePole.ar(tank, damping) * decay;

            wetL = (0.6 * tank).neg + wetL;

            tank = AllpassN.ar(tank, 1, st.(1800),
                gFacT60.(st.(1800), decayDiff2));

            wetR = (0.6 * tank).neg + wetR;

            tank = DelayN.ar(tank, 1, st.(3720));
            wetR = (0.6 * tank) + wetR;

            // ---- TANK: BRANCH 2 ----
            tank2 = AllpassC.ar(
                (tank * decay) + mono,
                maxdelaytime: 1,
                delaytime: st.(908) + (modExc * SinOsc.ar(modRate * 0.8, pi, modDepth)),
                decaytime: gFacT60.(st.(908), -0.7)
            );

            wetL = (0.6 * tank2) + wetL;
            wetL = (0.6 * DelayN.ar(tank2, 1, st.(2974))) + wetL;
            wetR = (0.6 * DelayN.ar(tank2, 1, st.(2111))).neg + wetR;

            tank2 = DelayN.ar(tank2, 1, st.(4217));
            tank2 = OnePole.ar(tank2, damping) * decay;

            tank2 = AllpassN.ar(tank2, 1, st.(2656),
                gFacT60.(st.(2656), decayDiff2));

            wetL = (0.6 * tank2).neg + wetL;
            wetR = (0.6 * DelayN.ar(tank2, 1, st.(335))).neg + wetR;

            tank2 = DelayN.ar(tank2, 1, st.(3163));
            wetL = (0.6 * tank2) + wetL;

            // ---- FEEDBACK (via saturation + tanh) ----
            LocalOut.ar((tank2 * decay * satDrive).tanh);

            // ---- OUTPUT: WIDTH (mid/side stereo) ----
            mid  = (wetL + wetR) * 0.5;
            side = (wetL - wetR) * 0.5;
            wetL = mid + (side * width);
            wetR = mid - (side * width);

            // ---- OUTPUT: TILT EQ (first-order shelf at ~1kHz) ----
            tiltAbs = tilt.abs;
            wet = [wetL, wetR];
            wetLP = OnePole.ar(wet, (-2pi * (1000 / SampleRate.ir)).exp);
            wetHP = wet - wetLP;
            wet = Select.ar(tilt > 0, [
                (wet * (1 - tiltAbs)) + (wetLP * tiltAbs * 2),
                (wet * (1 - tiltAbs)) + (wetHP * tiltAbs * 2)
            ]);

            // ---- OUTPUT: HPF + OUT ----
            Out.ar(outBus, HPF.ar(wet, 60));
        }).add;
    }
}
