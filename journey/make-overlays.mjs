#!/usr/bin/env node
// Builds the journey's deterministic config overlays.
//
// The plugin jar under test is byte-built from the exact HEAD under test;
// only two DATA tables are tuned so stochastic mechanics become
// deterministic and fast. Mechanics (triggers, rolls, keys, pity) run 100%
// through production code paths — only the tuned NUMBERS differ:
//
//   enchants.yml: miner.treasure-miner procs on every mined block,
//     has no cooldown/level-gate, costs 1 token, always rolls a sky key.
//   crates.yml: sky crate pity-count 10 -> 2, legendary weight -> 0
//     (so the second consecutive open is deterministically the pity).
//
// Stock balance numbers are NOT what the journey verifies; it verifies the
// mechanics and the wiring. Run from the repo root.
import { load as yamlLoad, dump as yamlDump } from 'js-yaml'
import fs from 'node:fs'
import path from 'node:path'

const SRC = 'src/main/resources'
const OUT = 'journey-server/plugins/CoreMC'
fs.mkdirSync(OUT, { recursive: true })

const ench = yamlLoad(fs.readFileSync(path.join(SRC, 'enchants.yml'), 'utf8'))
const tm = ench.enchants && ench.enchants['miner.treasure-miner']
if (!tm) throw new Error('stock enchants.yml has no miner.treasure-miner')
tm['chance-base'] = 1.0
tm['chance-scale'] = 0.0
tm['chance-cap'] = 1.0
tm['cooldown-seconds'] = 0
tm['min-role-level'] = 0
tm['cost-base'] = 1
let keyPatched = false
for (const r of tm.values.rewards) {
  if (r.type === 'KEY' && r.key === 'sky') { r.chance = 1.0; keyPatched = true }
}
if (!keyPatched) throw new Error('treasure-miner has no sky KEY reward to tune')
fs.writeFileSync(path.join(OUT, 'enchants.yml'), yamlDump(ench))

const crates = yamlLoad(fs.readFileSync(path.join(SRC, 'crates.yml'), 'utf8'))
const sky = crates.crates.sky
if (!sky) throw new Error('stock crates.yml has no sky crate')
sky['pity-count'] = 2
for (const r of sky.rewards) {
  if (String(r.rarity).toLowerCase() === 'legendary') r.weight = 0
}
fs.writeFileSync(path.join(OUT, 'crates.yml'), yamlDump(crates))

console.log('overlays written to', OUT)
console.log('  miner.treasure-miner: chance 1.0, cooldown 0, min-role-level 0, cost-base 1, sky key chance 1.0')
console.log('  sky crate: pity-count 2, legendary weight 0')
