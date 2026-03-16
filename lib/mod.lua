-- =========================================================================
-- fx_slipstream — creative reverb with modulation
-- for the norns fx mod framework
--
-- Two specialized modulators:
--   Modulation TM: character targets (how it sounds)
--     damping, size, spread, mod phase, input diffusion
--   Envelope Follower: amount targets (how much)
--     decay, input gain, saturation, mod depth
--
-- The domains don't overlap, so both modulators can run
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

local FxSlipstream = fx:new{ subpath = "/fx_slipstream" }

-- =========================================================================
-- constants
-- =========================================================================

-- TM targets: character (how the reverb sounds)
local TM_TARGET = {
    DAMPING=1, SIZE=2, SPREAD=3, MOD_PHASE=4, DIFFUSION=5
}
local tm_target_names = {
    "damping", "size", "spread", "mod phase", "input diffusion"
}
local tm_target_info = {
    [TM_TARGET.DAMPING]   = { sc="damping",        base="damping",        lo=0.002,hi=1     },
    [TM_TARGET.SIZE]      = { sc="size",           base="size",           lo=0.1,  hi=3     },
    [TM_TARGET.SPREAD]    = { sc="spread",         base="spread",         lo=0,    hi=2     },
    [TM_TARGET.MOD_PHASE] = { sc="modPhase",       base="modPhase",       lo=0,    hi=1     },
    [TM_TARGET.DIFFUSION] = { sc="inputDiffusion", base="inputDiffusion", lo=0,    hi=0.95  },
}

-- Envelope follower targets: amount (how much)
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

local step_rate_names = {"1/1","1/2","1/4","1/8","1/16"}
local step_rate_beats = {4, 2, 1, 0.5, 0.25}

local dir_names = {"+", "-", "+ & -"}
local env_dir_names = {"+", "-"}

local steps_names = {"off"}
for i = 1, 16 do steps_names[i + 1] = tostring(i) end

local cd_subdiv_names = {"1/1","1/2","1/4","1/8","1/16"}
local cd_subdiv_beats = {4, 2, 1, 0.5, 0.25}

-- =========================================================================
-- formatters (following fx_llll patterns)
-- =========================================================================

local function fmt_pct(param) return param:get() .. " %" end
local function fmt_ms(param) return param:get() .. " ms" end
local function fmt_deg(param) return math.floor(param:get() * 360 + 0.5) .. "°" end

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
    inputGain = 1.0,
    decay = 0.5,
    damping = 0.092,
    saturation = 0,
    bandwidth = 0.9995,
    inputDiffusion = 0.75,
    modDepth = 0.2,
    modRate = 1.0,
    modPhase = 0.5,
    size = 1.0,
    spread = 1.0,
}

local turing = {
    register = 0,
    steps = 0,
    target = TM_TARGET.SIZE,
    prev_target = TM_TARGET.SIZE,
    depth = 100,
    direction = -1,
    stability = 50,
    clock_div = 3,
    slew = 0,
}

local ctrl_delay = {
    repeats = 0,
    decay = 75,
    subdiv = 3,
    echo_clocks = {},
}

local env = {
    target = ENV_TARGET.DECAY,
    sensitivity = 0,
    direction = 1,
    slew = 200,
    active = false,
}

local tm_clock_id = nil

-- =========================================================================
-- helpers
-- =========================================================================

local function send(key, val)
    osc.send({"localhost", 57120}, "/fx_slipstream/set", {key, val})
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

--- Modulation math: max swing = ±100% of base value, clamped to limits.
local function apply_mod(raw, bv, lo, hi, depth, direction)
    local d = depth / 100
    local swing = bv * raw * d
    if direction == 1 then return math.max(lo, math.min(hi, bv + swing))
    elseif direction == -1 then return math.max(lo, math.min(hi, bv - swing))
    else
        local bp = (raw * 2 - 1) * d
        return math.max(lo, math.min(hi, bv + bv * bp))
    end
end

-- =========================================================================
-- TM: apply / restore
-- =========================================================================

local function tm_apply_target(raw, strength)
    local info = tm_target_info[turing.target]
    if not info then return end
    local bv = base[info.base]
    local modded = apply_mod(raw, bv, info.lo, info.hi, turing.depth, turing.direction)
    local val = bv + (modded - bv) * strength
    val = math.max(info.lo, math.min(info.hi, val))
    send(info.sc, val)
end

local function tm_restore(target)
    local info = tm_target_info[target]
    if not info then return end
    send(info.sc, base[info.base])
    unmark_ids(tm_param_ids[target])
end

-- =========================================================================
-- control delay
-- =========================================================================

local function cancel_echoes()
    for _, id in ipairs(ctrl_delay.echo_clocks) do
        clock.cancel(id)
    end
    ctrl_delay.echo_clocks = {}
end

local function schedule_echoes(raw)
    if ctrl_delay.repeats <= 0 then return end
    local beats = cd_subdiv_beats[ctrl_delay.subdiv]
    local decay_factor = ctrl_delay.decay / 100
    local target_at_schedule = turing.target

    for i = 1, ctrl_delay.repeats do
        local strength = decay_factor ^ i
        local delay_beats = beats * i
        local id = clock.run(function()
            clock.sync(delay_beats)
            if tm_active() and turing.target == target_at_schedule then
                send("slew", turing.slew / 1000)
                tm_apply_target(raw, strength)
            end
        end)
        table.insert(ctrl_delay.echo_clocks, id)
    end
end

-- =========================================================================
-- TM: step and apply
-- =========================================================================

local function tm_apply()
    if not tm_active() then return end
    local m = reg_max(); if m == 0 then return end
    local raw = turing.register / m
    send("slew", turing.slew / 1000)
    tm_apply_target(raw, 1.0)
    cancel_echoes()
    schedule_echoes(raw)
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
-- TM: activation / deactivation
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
    cancel_echoes()
    tm_restore(turing.target)
    if tm_clock_id then clock.cancel(tm_clock_id); tm_clock_id = nil end
end

-- =========================================================================
-- envelope follower
-- =========================================================================

local function env_receive(amplitude)
    if not env.active then return end

    local info = env_target_info[env.target]
    if not info then return end

    local bv = base[info.base]
    local mod_amount = amplitude * (env.sensitivity / 100)
    local val

    if env.direction == 1 then
        val = bv + bv * mod_amount
    else
        val = bv - bv * mod_amount
    end

    val = math.max(info.lo, math.min(info.hi, val))
    send("slew", env.slew / 1000)
    send(info.sc, val)
end

local function start_env_osc()
    local old_osc = _norns.osc.event
    _norns.osc.event = function(path, args, from)
        if path == '/fx_slipstream/env' and args and #args >= 3 then
            env_receive(args[3])
        end
        if old_osc then old_osc(path, args, from) end
    end
end

local function env_activate()
    env.active = true
    mark_ids(env_param_ids[env.target])
    start_env_osc()
end

local function env_deactivate()
    env.active = false
    local info = env_target_info[env.target]
    if info then send(info.sc, base[info.base]) end
    unmark_ids(env_param_ids[env.target])
end

-- =========================================================================
-- cleanup
-- =========================================================================

local function cleanup()
    if tm_clock_id then clock.cancel(tm_clock_id); tm_clock_id = nil end
    cancel_echoes()
end

-- =========================================================================
-- parameters
-- =========================================================================

function FxSlipstream:add_params()

    -- slot --
    params:add_separator("fx_ss", "fx slipstream")
    FxSlipstream:add_slot("fx_ss_slot", "slot")

    -- =====================================================================
    -- reverb
    -- =====================================================================
    params:add_separator("fx_ss_reverb", "reverb")

    -- predelay (0–500 ms, integer, ms unit)
    params:add_number("fx_ss_predelay", "predelay", 0, 500, 100, fmt_ms)
    params:set_action("fx_ss_predelay", function(v)
        base.preDelay = v / 1000
        send("preDelay", v / 1000)
    end)

    -- input gain (0–100%, integer)
    params:add_number("fx_ss_input_gain", "input gain", 0, 100, 100, fmt_pct)
    params:set_action("fx_ss_input_gain", function(v)
        base.inputGain = v / 100
        if not (env.active and env.target == ENV_TARGET.INPUT_GAIN) then
            send("inputGain", v / 100)
        end
    end)

    -- decay (0–100%, integer)
    params:add_number("fx_ss_decay", "decay", 0, 100, 50, fmt_pct)
    params:set_action("fx_ss_decay", function(v)
        base.decay = v / 100
        if not (env.active and env.target == ENV_TARGET.DECAY) then
            send("decay", v / 100)
        end
    end)

    -- damping (0–100%, integer)
    params:add_number("fx_ss_damping", "damping", 0, 100, 30, fmt_pct)
    params:set_action("fx_ss_damping", function(v)
        local coef = pct_to_damping_coef(v)
        base.damping = coef
        if not (tm_active() and turing.target == TM_TARGET.DAMPING) then
            send("damping", coef)
        end
    end)

    -- saturation (0–100%, integer)
    params:add_number("fx_ss_saturation", "saturation", 0, 100, 0, fmt_pct)
    params:set_action("fx_ss_saturation", function(v)
        base.saturation = v / 100
        if not (env.active and env.target == ENV_TARGET.SATURATION) then
            send("saturation", v / 100)
        end
    end)

    -- input diffusion (0–100%, integer)
    params:add_number("fx_ss_diffusion", "input diffusion", 0, 100, 75, fmt_pct)
    params:set_action("fx_ss_diffusion", function(v)
        base.inputDiffusion = v / 100
        if not (tm_active() and turing.target == TM_TARGET.DIFFUSION) then
            send("inputDiffusion", v / 100)
        end
    end)

    -- size (10–300%, integer, displayed as multiplier)
    params:add_number("fx_ss_size", "size", 10, 300, 100, fmt_x)
    params:set_action("fx_ss_size", function(v)
        base.size = v / 100
        if not (tm_active() and turing.target == TM_TARGET.SIZE) then
            send("size", v / 100)
        end
    end)

    -- spread (0–200%, integer, displayed as multiplier)
    params:add_number("fx_ss_spread", "spread", 0, 200, 100, fmt_x)
    params:set_action("fx_ss_spread", function(v)
        base.spread = v / 100
        if not (tm_active() and turing.target == TM_TARGET.SPREAD) then
            send("spread", v / 100)
        end
    end)

    -- =====================================================================
    -- tank modulation
    -- =====================================================================
    params:add_separator("fx_ss_mod", "tank modulation")

    -- mod depth (0–100%, integer)
    params:add_number("fx_ss_mod_depth", "mod depth", 0, 100, 20, fmt_pct)
    params:set_action("fx_ss_mod_depth", function(v)
        base.modDepth = v / 100
        if not (env.active and env.target == ENV_TARGET.MOD_DEPTH) then
            send("modDepth", v / 100)
        end
    end)

    -- mod rate (0.01–10 Hz, exponential)
    params:add_control("fx_ss_mod_rate", "mod rate",
        controlspec.new(0.01, 10, 'exp', 0, 1.0, "hz"), fmt_hz)
    params:set_action("fx_ss_mod_rate", function(v)
        base.modRate = v
        send("modRate", v)
    end)

    -- mod phase (0–100 → 0–360°)
    params:add_number("fx_ss_mod_phase", "mod phase", 0, 100, 50, fmt_deg)
    params:set_action("fx_ss_mod_phase", function(v)
        base.modPhase = v / 100
        if not (tm_active() and turing.target == TM_TARGET.MOD_PHASE) then
            send("modPhase", v / 100)
        end
    end)

    -- =====================================================================
    -- modulation TM (character: damping, size, spread, mod phase, diffusion)
    -- =====================================================================
    params:add_separator("fx_ss_tm", "modulation TM")

    params:add_option("fx_ss_tm_assign", "mod assign", tm_target_names, TM_TARGET.SIZE)
    params:set_action("fx_ss_tm_assign", function(v)
        if tm_active() then tm_restore(turing.prev_target) end
        turing.prev_target = v; turing.target = v
        if tm_active() then tm_activate() end
    end)

    params:add_number("fx_ss_tm_mod_depth", "mod depth", 0, 100, 100, fmt_pct)
    params:set_action("fx_ss_tm_mod_depth", function(v) turing.depth = v end)

    params:add_option("fx_ss_tm_mod_dir", "mod direction", dir_names, 2)
    params:set_action("fx_ss_tm_mod_dir", function(v)
        if v == 1 then turing.direction = 1
        elseif v == 2 then turing.direction = -1
        else turing.direction = 0 end
    end)

    params:add_number("fx_ss_tm_slew", "slew rate", 0, 2000, 0, fmt_ms)
    params:set_action("fx_ss_tm_slew", function(v) turing.slew = v end)

    params:add_option("fx_ss_tm_step_rate", "step rate", step_rate_names, 3)
    params:set_action("fx_ss_tm_step_rate", function(v)
        turing.clock_div = v
        if tm_active() then start_tm_clock() end
    end)

    params:add_number("fx_ss_tm_stability", "step stability", 0, 100, 50, fmt_pct)
    params:set_action("fx_ss_tm_stability", function(v) turing.stability = v end)

    params:add_option("fx_ss_tm_steps", "steps", steps_names, 1)
    params:set_action("fx_ss_tm_steps", function(v)
        local was = tm_active()
        turing.steps = v - 1
        if tm_active() then tm_activate()
        elseif was then tm_deactivate() end
    end)

    -- =====================================================================
    -- control delay (echoes of TM modulation)
    -- =====================================================================
    params:add_separator("fx_ss_cd", "control delay")

    params:add_number("fx_ss_cd_repeats", "repeats", 0, 4, 0)
    params:set_action("fx_ss_cd_repeats", function(v)
        ctrl_delay.repeats = v
        if v == 0 then cancel_echoes() end
    end)

    params:add_number("fx_ss_cd_decay", "echo decay", 0, 100, 75, fmt_pct)
    params:set_action("fx_ss_cd_decay", function(v) ctrl_delay.decay = v end)

    params:add_option("fx_ss_cd_subdiv", "echo subdiv", cd_subdiv_names, 3)
    params:set_action("fx_ss_cd_subdiv", function(v) ctrl_delay.subdiv = v end)

    -- =====================================================================
    -- envelope follower (amount: decay, input gain, saturation, mod depth)
    -- =====================================================================
    params:add_separator("fx_ss_env", "envelope follower")

    params:add_option("fx_ss_env_target", "target", env_target_names, ENV_TARGET.DECAY)
    params:set_action("fx_ss_env_target", function(v)
        local was_active = env.active
        if was_active then env_deactivate() end
        env.target = v
        if was_active then env_activate() end
    end)

    params:add_number("fx_ss_env_sensitivity", "sensitivity", 0, 100, 0, fmt_pct)
    params:set_action("fx_ss_env_sensitivity", function(v)
        env.sensitivity = v
        if v > 0 and not env.active then env_activate()
        elseif v == 0 and env.active then env_deactivate() end
    end)

    params:add_option("fx_ss_env_dir", "direction", env_dir_names, 1)
    params:set_action("fx_ss_env_dir", function(v)
        if v == 1 then env.direction = 1 else env.direction = -1 end
    end)

    params:add_number("fx_ss_env_slew", "slew rate", 0, 2000, 200, fmt_ms)
    params:set_action("fx_ss_env_slew", function(v) env.slew = v end)

    params:add_number("fx_ss_env_attack", "attack", 1, 500, 10, fmt_ms)
    params:set_action("fx_ss_env_attack", function(v)
        send("envAttack", v / 1000)
    end)

    params:add_number("fx_ss_env_release", "release", 10, 2000, 100, fmt_ms)
    params:set_action("fx_ss_env_release", function(v)
        send("envRelease", v / 1000)
    end)

    -- =====================================================================
    -- populate (M) marker maps
    -- =====================================================================

    -- TM targets (character)
    tm_param_ids[TM_TARGET.DAMPING]   = {"fx_ss_damping"}
    tm_param_ids[TM_TARGET.SIZE]      = {"fx_ss_size"}
    tm_param_ids[TM_TARGET.SPREAD]    = {"fx_ss_spread"}
    tm_param_ids[TM_TARGET.MOD_PHASE] = {"fx_ss_mod_phase"}
    tm_param_ids[TM_TARGET.DIFFUSION] = {"fx_ss_diffusion"}

    -- ENV targets (amount)
    env_param_ids[ENV_TARGET.DECAY]      = {"fx_ss_decay"}
    env_param_ids[ENV_TARGET.INPUT_GAIN] = {"fx_ss_input_gain"}
    env_param_ids[ENV_TARGET.SATURATION] = {"fx_ss_saturation"}
    env_param_ids[ENV_TARGET.MOD_DEPTH]  = {"fx_ss_mod_depth"}

    start_env_osc()
end

-- =========================================================================
-- hooks
-- =========================================================================

mod.hook.register("script_post_init", "fx slipstream post init", function()
    FxSlipstream:add_params()
end)

mod.hook.register("script_post_cleanup", "fx slipstream cleanup", function()
    if env.active then env_deactivate() end
    if tm_active() then tm_deactivate() end
    cleanup()
end)

return FxSlipstream
