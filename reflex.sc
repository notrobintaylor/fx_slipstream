-- =========================================================================
-- fx_reflex — creative reverb with modulation
-- for the norns fx mod framework
--
-- Two specialized modulators, each with its own domain:
--
--   modulation TM: character (how the reverb sounds)
--     damping, size, spread, diffusion
--
--   envelope follower: amount (how much)
--     decay, input gain, saturation, mod depth
--     Source: audio input amplitude OR modulation TM register value.
--
-- The two domains never overlap, so both modulators can run
-- simultaneously without conflict.
-- =========================================================================

local fx = require("fx/lib/fx")
local mod = require 'core/mods'
local hook = require 'core/hook'
local tab = require 'tabutil'

-- =========================================================================
-- post-init hack (boilerplate from fx mod framework)
-- =========================================================================

if hook.script_post_init == nil and mod.hook.patched == nil then
    mod.hook.patched = true
    local old_register = mod.hook.register
    local post_init_hooks = {}
    mod.hook.register = function(h, name, f)
        if h == "script_post_init" then
            post_init_hooks[name] = f
        else old_register(h, name, f) end
    end
    mod.hook.register('script_pre_init', '!replace init for fake post init', function()
        local old_init = init
        init = function()
            old_init()
            for i, k in ipairs(tab.sort(post_init_hooks)) do
                local cb = post_init_hooks[k]
                local ok, err = pcall(cb)
                if not ok then print('hook: ' .. k .. ' failed: ' .. err) end
            end
        end
    end)
end

local FxReflex = fx:new{ subpath = "/fx_reflex" }

-- =========================================================================
-- constants
-- =========================================================================

-- modulation TM targets: character (how the reverb sounds)
local TM_TARGET = {
    DAMPING=1, SIZE=2, SPREAD=3, DIFFUSION=4
}
local tm_target_names = {
    "damping", "size", "spread", "diffusion"
}
local tm_target_info = {
    [TM_TARGET.DAMPING]   = { sc="damping",        base="damping",        lo=0.002,hi=1    },
    [TM_TARGET.SIZE]      = { sc="size",           base="size",           lo=0.1,  hi=3    },
    [TM_TARGET.SPREAD]    = { sc="spread",         base="spread",         lo=0,    hi=2    },
    [TM_TARGET.DIFFUSION] = { sc="inputDiffusion", base="inputDiffusion", lo=0,    hi=0.95 },
}

-- envelope follower targets: amount (how much)
local ENV_TARGET = {
    DECAY=1, INPUT_GAIN=2, SATURATION=3, MOD_DEPTH=4
}
local env_target_names = {
    "decay", "input gain", "saturation", "mod depth"
}
local env_target_info = {
    [ENV_TARGET.DECAY]      = { sc="decay",      base="decay",      lo=0,  hi=0.99 },
    [ENV_TARGET.INPUT_GAIN] = { sc="inputGain",  base="inputGain",  lo=0,  hi=1    },
    [ENV_TARGET.SATURATION] = { sc="saturation", base="saturation", lo=0,  hi=1    },
    [ENV_TARGET.MOD_DEPTH]  = { sc="modDepth",   base="modDepth",   lo=0,  hi=1    },
}

-- envelope follower sources
local ENV_SOURCE = { AUDIO=1, TM=2 }
local env_source_names = { "audio input", "modulation TM" }

local step_rate_names = {"4/1","2/1","1/1","1/2","1/4","1/8","1/16"}
local step_rate_beats = {16, 8, 4, 2, 1, 0.5, 0.25}

local dir_names = {"+", "-", "+ & -"}
local env_dir_names = {"+", "-"}

local steps_names = {"off"}
for i = 1, 16 do steps_names[i + 1] = tostring(i) end

-- =========================================================================
-- formatters (following fx_llll patterns)
-- =========================================================================

local function fmt_pct(param) return param:get() .. " %" end
local function fmt_pct_off(param)
    local v = param:get()
    if v == 0 then return "off" else return v .. " %" end
end
local function fmt_ms(param) return param:get() .. " ms" end

local function fmt_hz(param)
    local v = param:get()
    if v >= 100 then return string.format("%d hz", math.floor(v + 0.5))
    elseif v >= 10 then return string.format("%.1f hz", v)
    else return string.format("%.2f hz", v) end
end

local function fmt_x(param)
    local v = param:get()
    return string.format("%.2fx", v / 100)
end

-- =========================================================================
-- (M) marker system
-- =========================================================================

local tm_param_ids = {}
local env_param_ids = {}
local original_names = {}

local function mark_ids(ids)
    if not ids then return end
    for _, id in ipairs(ids) do
        local idx = params.lookup[id]
        if idx then
            local p = params.params[idx]
            if not original_names[id] then original_names[id] = p.name end
            if not string.find(p.name, "%(M%)") then
                p.name = "(M) " .. original_names[id]
            end
        end
    end
    _menu.rebuild_params()
end

local function unmark_ids(ids)
    if not ids then return end
    for _, id in ipairs(ids) do
        local idx = params.lookup[id]
        if idx and original_names[id] then
            params.params[idx].name = original_names[id]
        end
    end
    _menu.rebuild_params()
end

-- =========================================================================
-- state
-- =========================================================================

local base = {
    preDelay = 0.1,
    inputGain = 0.25,
    decay = 0.5,
    damping = 0.064,
    saturation = 0,
    bandwidth = 0.5,
    inputDiffusion = 0.25,
    modDepth = 0,
    modRate = 0.25,
    size = 1.0,
    spread = 1.0,
    width = 1.0,
    tilt = 0,
}

local turing = {
    register = 0,
    steps = 0,
    target = TM_TARGET.SIZE,
    prev_target = TM_TARGET.SIZE,
    depth = 100,
    direction = -1,
    stability = 50,
    clock_div = 5,        -- index into step_rate_beats (1/4)
    slew = 0,
}

local env = {
    target = ENV_TARGET.DECAY,
    source = ENV_SOURCE.AUDIO,
    sensitivity = 0,
    direction = 1,
    slew = 100,           -- ms
    active = false,
}

local tm_clock_id = nil

-- =========================================================================
-- helpers
-- =========================================================================

local function send(key, val)
    osc.send({"localhost", 57120}, "/fx_reflex/set", {key, val})
end

local function tm_active() return turing.steps > 0 end

local function reg_max()
    if turing.steps <= 0 then return 0 end
    return (1 << turing.steps) - 1
end

local function pct_to_damping_coef(pct)
    local t = pct / 100
    return 0.002 + 0.996 * t * t
end

-- swing is range-based so parameters at 0 (saturation, mod depth) are reachable
local function apply_mod(raw, bv, lo, hi, depth, direction)
    local d = depth / 100
    local swing = (hi - lo) * raw * d
    if direction == 1 then return math.max(lo, math.min(hi, bv + swing))
    elseif direction == -1 then return math.max(lo, math.min(hi, bv - swing))
    else
        local bp = (raw * 2 - 1) * d
        return math.max(lo, math.min(hi, bv + (hi - lo) * bp))
    end
end

local function send_modulated(info, raw, depth, direction)
    if not info then return end
    local bv = base[info.base]
    send(info.sc, apply_mod(raw, bv, info.lo, info.hi, depth, direction))
end

local function restore_info(info, ids)
    if not info then return end
    send(info.sc, base[info.base])
    unmark_ids(ids)
end

-- =========================================================================
-- modulation TM: apply / restore
-- =========================================================================

local function tm_apply_target(raw)
    local info = tm_target_info[turing.target]
    send_modulated(info, raw, turing.depth, turing.direction)
end

local function tm_restore(target)
    restore_info(tm_target_info[target], tm_param_ids[target])
end

-- =========================================================================
-- envelope follower
-- =========================================================================

local function env_from_value(amplitude)
    if not env.active then return end
    local info = env_target_info[env.target]
    if not info then return end

    local bv = base[info.base]
    local swing = (info.hi - info.lo) * amplitude * (env.sensitivity / 100)
    local val

    if env.direction == 1 then
        val = bv + swing
    else
        val = bv - swing
    end

    send("slew", env.slew / 1000)
    send(info.sc, math.max(info.lo, math.min(info.hi, val)))
end

--- Called ~30x/sec with the current input amplitude from SC.
local function env_receive_audio(amplitude)
    if not env.active then return end
    if env.source ~= ENV_SOURCE.AUDIO then return end
    env_from_value(amplitude)
end

local osc_patched = false
local function start_env_osc()
    if osc_patched then return end
    osc_patched = true
    local old_osc = _norns.osc.event
    _norns.osc.event = function(path, args, from)
        if path == '/fx_reflex/env' and args and #args >= 3 then
            env_receive_audio(args[3])
        end
        if old_osc then old_osc(path, args, from) end
    end
end

local function env_activate()
    env.active = true
    mark_ids(env_param_ids[env.target])
end

local function env_deactivate()
    env.active = false
    local info = env_target_info[env.target]
    if info then send(info.sc, base[info.base]) end
    unmark_ids(env_param_ids[env.target])
end

-- =========================================================================
-- modulation TM: step and apply
-- =========================================================================

local function tm_apply()
    if not tm_active() then return end
    local m = reg_max(); if m == 0 then return end
    local raw = turing.register / m
    send("slew", turing.slew / 1000)
    tm_apply_target(raw)
    -- when TM is the env source, feed the register value to the env follower too
    if env.source == ENV_SOURCE.TM and env.active then
        env_from_value(raw)
    end
end

local function tm_step()
    if not tm_active() then return end
    local m = reg_max()
    local msb = (turing.register >> (turing.steps - 1)) & 1
    turing.register = (turing.register << 1) & m
    if math.random(100) > turing.stability then
        turing.register = turing.register | (1 - msb)
    else
        turing.register = turing.register | msb
    end
    tm_apply()
end

-- =========================================================================
-- modulation TM: activation / deactivation
-- =========================================================================

local function start_tm_clock()
    if tm_clock_id then clock.cancel(tm_clock_id); tm_clock_id = nil end
    if not tm_active() then return end
    tm_clock_id = clock.run(function()
        while true do
            clock.sync(step_rate_beats[turing.clock_div])
            tm_step()
        end
    end)
end

local function tm_activate()
    turing.register = math.random(0, reg_max())
    mark_ids(tm_param_ids[turing.target])
    start_tm_clock()
end

local function tm_deactivate()
    tm_restore(turing.target)
    if tm_clock_id then clock.cancel(tm_clock_id); tm_clock_id = nil end
end

-- =========================================================================
-- cleanup
-- =========================================================================

local function cleanup()
    if tm_clock_id then clock.cancel(tm_clock_id); tm_clock_id = nil end
end

-- =========================================================================
-- parameters
-- =========================================================================

function FxReflex:add_params()

    -- slot management (see README 1.0 / user stories):
    -- send a / send b: route directly to the norns send buses, independent of the
    --   insert replacer synth. no drywet parameter involved.
    -- insert: equal power crossfade — dry = cos(drywet·π/2), wet = sin(drywet·π/2).
    --   at drywet=1, cos(π/2)=0 exactly, so no dry signal leaks through at full wet.
    -- click-free switching: the fx send level is faded to 0 (≈20 ms) before the
    --   new slot is armed, preventing audible clicks from abrupt bus-gain changes.
    -- spillover: on slot deselect the send input is muted (faded); the reverb tank
    --   keeps running freely. the tail rings out for as long as decay dictates —
    --   the send stays muted until a new slot is selected.
    params:add_separator("fx_rx", "fx reflex")
    FxReflex:add_slot("fx_rx_slot", "slot")

    do
        local slot_idx = params.lookup["fx_rx_slot"]
        if slot_idx then
            local orig = params.params[slot_idx].action
            params:set_action("fx_rx_slot", function(v)
                if orig then orig(v) end
                local p = params.params[params.lookup["fx_rx_slot"]]
                if p and p.options and p.options[v] and
                   string.lower(p.options[v]) == "insert" then
                    for _, q in ipairs(params.params) do
                        if q.id and string.find(q.id, "drywet") then
                            params:set(q.id, 0); break
                        end
                    end
                end
            end)
        end
    end

    params:add_trigger("fx_rx_init", "initialize")
    params:set_action("fx_rx_init", function()
        for _, p in ipairs(params.params) do
            if p.id and string.sub(p.id, 1, 6) == "fx_rx_" and
               p.id ~= "fx_rx_init" and p.default ~= nil then
                params:set(p.id, p.default)
            end
        end
    end)

    -- =====================================================================
    -- reverb
    -- =====================================================================
    params:add_separator("fx_rx_reverb", "reverb")

    params:add_number("fx_rx_predelay", "predelay", 0, 500, 100, fmt_ms)
    params:set_action("fx_rx_predelay", function(v)
        base.preDelay = v / 1000
        send("preDelay", v / 1000)
    end)

    params:add_number("fx_rx_bandwidth", "bandwidth", 0, 100, 50, fmt_pct)
    params:set_action("fx_rx_bandwidth", function(v)
        base.bandwidth = v / 100
        send("bandwidth", v / 100)
    end)

    params:add_number("fx_rx_input_gain", "input gain", 0, 100, 25, fmt_pct)
    params:set_action("fx_rx_input_gain", function(v)
        base.inputGain = v / 100
        if not (env.active and env.target == ENV_TARGET.INPUT_GAIN) then
            send("inputGain", v / 100)
        end
    end)

    params:add_number("fx_rx_decay", "decay", 0, 100, 50, fmt_pct)
    params:set_action("fx_rx_decay", function(v)
        base.decay = v / 100
        if not (env.active and env.target == ENV_TARGET.DECAY) then
            send("decay", v / 100)
        end
    end)

    params:add_number("fx_rx_damping", "damping", 0, 100, 25, fmt_pct)
    params:set_action("fx_rx_damping", function(v)
        local coef = pct_to_damping_coef(v)
        base.damping = coef
        if not (tm_active() and turing.target == TM_TARGET.DAMPING) then
            send("damping", coef)
        end
    end)

    params:add_number("fx_rx_saturation", "saturation", 0, 100, 0, fmt_pct)
    params:set_action("fx_rx_saturation", function(v)
        base.saturation = v / 100
        if not (env.active and env.target == ENV_TARGET.SATURATION) then
            send("saturation", v / 100)
        end
    end)

    params:add_number("fx_rx_diffusion", "input diffusion", 0, 100, 25, fmt_pct)
    params:set_action("fx_rx_diffusion", function(v)
        base.inputDiffusion = v / 100
        if not (tm_active() and turing.target == TM_TARGET.DIFFUSION) then
            send("inputDiffusion", v / 100)
        end
    end)

    params:add_number("fx_rx_size", "size", 10, 300, 100, fmt_x)
    params:set_action("fx_rx_size", function(v)
        base.size = v / 100
        if not (tm_active() and turing.target == TM_TARGET.SIZE) then
            send("size", v / 100)
        end
    end)

    params:add_number("fx_rx_spread", "spread", 0, 200, 100, fmt_x)
    params:set_action("fx_rx_spread", function(v)
        base.spread = v / 100
        if not (tm_active() and turing.target == TM_TARGET.SPREAD) then
            send("spread", v / 100)
        end
    end)

    params:add_number("fx_rx_width", "width", 0, 200, 100, fmt_pct)
    params:set_action("fx_rx_width", function(v)
        base.width = v / 100
        send("width", v / 100)
    end)

    params:add_number("fx_rx_tilt", "tilt", -100, 100, 0, fmt_pct)
    params:set_action("fx_rx_tilt", function(v)
        base.tilt = v / 100
        send("tilt", v / 100)
    end)

    -- =====================================================================
    -- modulation
    -- =====================================================================
    params:add_separator("fx_rx_mod", "modulation")

    params:add_number("fx_rx_mod_depth", "mod depth", 0, 100, 0, fmt_pct)
    params:set_action("fx_rx_mod_depth", function(v)
        base.modDepth = v / 100
        if not (env.active and env.target == ENV_TARGET.MOD_DEPTH) then
            send("modDepth", v / 100)
        end
    end)

    params:add_control("fx_rx_mod_rate", "mod rate",
        controlspec.new(0.01, 10000, 'exp', 0, 0.25, "hz"), fmt_hz)
    params:set_action("fx_rx_mod_rate", function(v)
        base.modRate = v
        send("modRate", v)
    end)

    -- =====================================================================
    -- modulation TM (character: damping, size, spread, diffusion)
    -- =====================================================================
    params:add_separator("fx_rx_tm", "modulation TM")

    params:add_option("fx_rx_tm_assign", "mod assign", tm_target_names, TM_TARGET.SIZE)
    params:set_action("fx_rx_tm_assign", function(v)
        if tm_active() then tm_restore(turing.prev_target) end
        turing.prev_target = v; turing.target = v
        if tm_active() then tm_activate() end
    end)

    params:add_number("fx_rx_tm_mod_depth", "mod depth", 0, 100, 100, fmt_pct)
    params:set_action("fx_rx_tm_mod_depth", function(v) turing.depth = v end)

    params:add_option("fx_rx_tm_mod_dir", "mod direction", dir_names, 2)
    params:set_action("fx_rx_tm_mod_dir", function(v)
        if v == 1 then turing.direction = 1
        elseif v == 2 then turing.direction = -1
        else turing.direction = 0 end
    end)

    params:add_number("fx_rx_tm_slew", "slew rate", 0, 2000, 0, fmt_ms)
    params:set_action("fx_rx_tm_slew", function(v) turing.slew = v end)

    params:add_option("fx_rx_tm_step_rate", "step rate", step_rate_names, 5)
    params:set_action("fx_rx_tm_step_rate", function(v)
        turing.clock_div = v
        if tm_active() then start_tm_clock() end
    end)

    params:add_number("fx_rx_tm_stability", "step stability", 0, 100, 50, fmt_pct)
    params:set_action("fx_rx_tm_stability", function(v) turing.stability = v end)

    params:add_option("fx_rx_tm_steps", "steps", steps_names, 1)
    params:set_action("fx_rx_tm_steps", function(v)
        local was = tm_active()
        turing.steps = v - 1
        if tm_active() then tm_activate()
        elseif was then tm_deactivate() end
    end)

    params:add_trigger("fx_rx_tm_randomize", "randomize")
    params:set_action("fx_rx_tm_randomize", function()
        if tm_active() then
            turing.register = math.random(0, reg_max())
            tm_apply()
        end
    end)

    -- =====================================================================
    -- envelope follower (amount: decay, input gain, saturation, mod depth)
    -- =====================================================================
    params:add_separator("fx_rx_env", "envelope follower")

    params:add_option("fx_rx_env_source", "source", env_source_names, ENV_SOURCE.AUDIO)
    params:set_action("fx_rx_env_source", function(v)
        env.source = v
    end)

    params:add_option("fx_rx_env_target", "target", env_target_names, ENV_TARGET.DECAY)
    params:set_action("fx_rx_env_target", function(v)
        local was_active = env.active
        if was_active then env_deactivate() end
        env.target = v
        if was_active then env_activate() end
    end)

    params:add_number("fx_rx_env_sensitivity", "sensitivity", 0, 100, 0, fmt_pct_off)
    params:set_action("fx_rx_env_sensitivity", function(v)
        env.sensitivity = v
        if v > 0 and not env.active then env_activate()
        elseif v == 0 and env.active then env_deactivate() end
    end)

    params:add_option("fx_rx_env_dir", "direction", env_dir_names, 1)
    params:set_action("fx_rx_env_dir", function(v)
        if v == 1 then env.direction = 1 else env.direction = -1 end
    end)

    params:add_number("fx_rx_env_slew", "slew rate", 0, 2000, 100, fmt_ms)
    params:set_action("fx_rx_env_slew", function(v) env.slew = v end)

    params:add_number("fx_rx_env_attack", "attack", 1, 1000, 10, fmt_ms)
    params:set_action("fx_rx_env_attack", function(v)
        send("envAttack", v / 1000)
    end)

    params:add_number("fx_rx_env_release", "release", 10, 2000, 100, fmt_ms)
    params:set_action("fx_rx_env_release", function(v)
        send("envRelease", v / 1000)
    end)

    -- =====================================================================
    -- populate (M) marker maps
    -- =====================================================================

    tm_param_ids[TM_TARGET.DAMPING]   = {"fx_rx_damping"}
    tm_param_ids[TM_TARGET.SIZE]      = {"fx_rx_size"}
    tm_param_ids[TM_TARGET.SPREAD]    = {"fx_rx_spread"}
    tm_param_ids[TM_TARGET.DIFFUSION] = {"fx_rx_diffusion"}

    env_param_ids[ENV_TARGET.DECAY]      = {"fx_rx_decay"}
    env_param_ids[ENV_TARGET.INPUT_GAIN] = {"fx_rx_input_gain"}
    env_param_ids[ENV_TARGET.SATURATION] = {"fx_rx_saturation"}
    env_param_ids[ENV_TARGET.MOD_DEPTH]  = {"fx_rx_mod_depth"}

    start_env_osc()
end

-- =========================================================================
-- hooks
-- =========================================================================

mod.hook.register("script_post_init", "fx reflex post init", function()
    FxReflex:add_params()
end)

mod.hook.register("script_post_cleanup", "fx reflex cleanup", function()
    if env.active then env_deactivate() end
    if tm_active() then tm_deactivate() end
    cleanup()
end)

return FxReflex
