#!/usr/bin/env node
// CoreMC live player journey.
//
// Boots a Paper server from THIS checkout (plugin jar built from the exact
// HEAD under test), then drives a complete two-player session with
// mineflayer bots + RCON and asserts every headliner flow:
//
//   P1  join + welcome + /is create + /role (Miner + Omni-Tool)
//   P2  economy grants + /money|/credits|/skytokens + shop buy + shop sell
//       + /tokenshop exchange (live fitsDeposit path)
//   P3  /is upgrades: buy border tier 1
//   P4  /is buffs: buy mining-boost tier 1
//   P5  /spawners: 15 lanes, locked zombie submenu, 25 REAL kills,
//       unlock message, buy zombie spawner I, place it
//   P6  Omni-Tool panel -> enchants -> buy treasure-miner (overlay:
//       deterministic), mine 2 blocks -> 2 sky keys
//   P7  /crates: open sky x2 (2nd is deterministically the pity),
//       no-key negative path
//   P8  outsider protection: guest dig denied, block intact
//   P9  /gens: buy cobble gen, place, harvest
//   P10 void rescue back home
//   P11 clean restart: everything persists (live + data-file asserts)
//   P12 full-log audit: zero server ERRORs, zero CoreMC warn/error lines
//
// Run from journey-server/ (cwd is the server dir). Exit 0 = all green.
import mineflayer from 'mineflayer'
import { Vec3 } from 'vec3'
import { Rcon } from 'rcon-client'
import yaml from 'js-yaml'
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'

const ROOT = process.cwd()
const PLUGIN_DIR = path.join(ROOT, 'plugins', 'CoreMC')
const SERVER_LOG = path.join(ROOT, 'server.log')
const JOURNEY_LOG = path.join(ROOT, 'journey.log')
const RCON_CFG = { host: '127.0.0.1', port: 25575, password: 'journey123' }
const OWNER = 'JOwner'
const GUEST = 'JGuest'
const DIM = 'minecraft:islands'

const jlog = fs.createWriteStream(JOURNEY_LOG, { flags: 'w' })
let pass = 0
let fail = 0
const failures = []
function log(...a) {
  const line = a.map(String).join(' ')
  process.stdout.write(line + '\n')
  jlog.write(line + '\n')
}
function ok(label) { pass++; log('  PASS  ' + label) }
function bad(label, extra = '') {
  fail++
  failures.push(label)
  log('  FAIL  ' + label + (extra ? '  :: ' + extra : ''))
}
function check(cond, label, extra = '') {
  if (cond) ok(label)
  else bad(label, extra)
  return !!cond
}
function phase(n, title) { log(''); log(`=== PHASE ${n}: ${title} ===`) }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ------------------------------------------------------------------ server
let serverProc = null
function paperJar() {
  const jars = fs.readdirSync(ROOT).filter((f) => /^paper-.*\.jar$/.test(f))
  if (!jars.length) throw new Error('no paper jar in ' + ROOT)
  return path.join(ROOT, jars[0])
}
function bootCount() {
  if (!fs.existsSync(SERVER_LOG)) return 0
  return (fs.readFileSync(SERVER_LOG, 'utf8').match(/Done \([^)]*\)! For help, type "help"/g) || []).length
}
async function startServer(tag, timeoutMs = 14 * 60 * 1000) {
  const seen = bootCount()
  log(`[server] booting (${tag})...`)
  const out = fs.openSync(SERVER_LOG, 'a')
  serverProc = spawn('java', ['-Xmx3G', '-Xms1G', '-jar', paperJar(), 'nogui'],
    { cwd: ROOT, stdio: ['ignore', out, out] })
  serverProc.on('error', (e) => log('[server] spawn error: ' + e.message))
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    if (serverProc.exitCode !== null && serverProc.exitCode !== undefined) {
      bad(`server ${tag} exited early`, 'code=' + serverProc.exitCode)
      throw new Error('server exited')
    }
    if (bootCount() > seen) { log(`[server] ${tag} ready`); return }
    await sleep(3000)
  }
  throw new Error('server boot timeout: ' + tag)
}
async function stopServer() {
  log('[server] stopping...')
  try { await rcon.send('stop') } catch (e) { log('[server] stop send: ' + e.message) }
  try { rcon.end() } catch { /* already closed */ }
  rcon = null
  const t0 = Date.now()
  while (Date.now() - t0 < 90000) {
    if (!serverProc || (serverProc.exitCode !== null && serverProc.exitCode !== undefined)) {
      log('[server] exited')
      await sleep(3000)
      return
    }
    await sleep(2000)
  }
  try { serverProc.kill('SIGKILL') } catch { /* gone */ }
  bad('server did not stop cleanly', 'SIGKILLed')
}

// -------------------------------------------------------------------- rcon
let rcon = null
async function connectRcon(timeoutMs = 180000) {
  const t0 = Date.now()
  let last = ''
  while (Date.now() - t0 < timeoutMs) {
    try {
      rcon = await Rcon.connect(RCON_CFG)
      log('[rcon] connected')
      return
    } catch (e) { last = e.message; await sleep(3000) }
  }
  throw new Error('rcon connect timeout: ' + last)
}
async function rc(cmd) {
  const res = await rcon.send(cmd)
  return res || ''
}
async function baseSetup() {
  await rc('gamerule doDaylightCycle false')
  await rc('time set midnight')
  await rc('gamerule doMobSpawning false')
  await rc('gamerule doTraderSpawning false')
  await rc('difficulty normal')
  log('[setup] gamerules applied')
}

// -------------------------------------------------------------- log audit
function auditLog(label) {
  const txt = fs.existsSync(SERVER_LOG) ? fs.readFileSync(SERVER_LOG, 'utf8') : ''
  const lines = txt.split('\n')
  const serverErrors = lines.filter((l) => /\] ERROR\]:/.test(l))
  const pluginProblems = lines.filter(
    (l) => /coremc/i.test(l) && /(WARN|ERROR|Exception|Caused by)/.test(l))
  check(serverErrors.length === 0, `${label}: zero server ERROR lines`,
    serverErrors.slice(0, 4).join(' | ').slice(0, 400))
  check(pluginProblems.length === 0, `${label}: zero CoreMC warn/error/exception lines`,
    pluginProblems.slice(0, 4).join(' | ').slice(0, 400))
}

// -------------------------------------------------------------------- bots
function makeBot(name) {
  const bot = mineflayer.createBot({
    host: '127.0.0.1', port: 25565, username: name, auth: 'offline', version: '1.21.11',
  })
  bot.__name = name
  bot.__chat = []
  bot.on('messagestr', (m) => { bot.__chat.push(m); if (bot.__chat.length > 600) bot.__chat.shift() })
  bot.on('error', (e) => log(`[${name}] bot error: ${e.message}`))
  bot.on('kicked', (reason) => bad(`${name} kicked`, String(reason).slice(0, 200)))
  bot.on('end', () => log(`[${name}] connection ended`))
  return bot
}
async function waitSpawn(bot, timeoutMs = 90000) {
  if (bot.entity) return true
  return new Promise((resolve) => {
    const t = setTimeout(() => resolve(false), timeoutMs)
    bot.once('spawn', () => { clearTimeout(t); resolve(true) })
  })
}
function clearChat(bot) { bot.__chat.length = 0 }
async function waitChat(bot, rx, timeoutMs = 25000) {
  const re = rx instanceof RegExp ? rx : new RegExp(rx, 'i')
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    for (const m of bot.__chat) {
      const hit = m.match(re)
      if (hit) return hit
    }
    await sleep(250)
  }
  return null
}
async function waitWindow(bot, timeoutMs = 25000) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    if (bot.currentWindow) { await sleep(800); return bot.currentWindow }
    await sleep(250)
  }
  return null
}
async function openWindow(bot, command, timeoutMs = 25000) {
  try { if (bot.currentWindow) bot.closeWindow(bot.currentWindow) } catch { /* noop */ }
  await sleep(500)
  bot.chat(command)
  return waitWindow(bot, timeoutMs)
}
const slotJson = (win, slot) => {
  try { return JSON.stringify(win.slots[slot] ?? null).toLowerCase() } catch { return '' }
}
const slotType = (win, slot) => {
  const it = win.slots[slot]
  return it ? it.name : null
}
function findSlotByName(win, substr, from = 0, to = 53) {
  const needle = substr.toLowerCase()
  for (let s = from; s <= to; s++) {
    if (slotJson(win, s).includes(needle)) return s
  }
  return -1
}
async function click(bot, slot, button = 0) {
  await bot.clickWindow(slot, button, 0)
  await sleep(900)
}
async function closeWin(bot) {
  try { if (bot.currentWindow) bot.closeWindow(bot.currentWindow) } catch { /* noop */ }
  await sleep(500)
}
function invCount(bot, type) {
  return bot.inventory.items().filter((i) => i.name === type).reduce((a, i) => a + i.count, 0)
}
function invHas(bot, type, substr) {
  return bot.inventory.items().some((i) => i.name === type
    && (!substr || JSON.stringify(i).toLowerCase().includes(substr.toLowerCase())))
}
async function balance(bot, cmd, rx) {
  clearChat(bot)
  bot.chat(cmd)
  const hit = await waitChat(bot, rx, 15000)
  if (!hit) return null
  return parseInt(hit[1].replace(/,/g, ''), 10)
}
const moneyOf = (b) => balance(b, '/money', /you have \$([\d,]+)/i)
const creditsOf = (b) => balance(b, '/credits', /you have ([\d,]+) credits/i)
const tokensOf = (b) => balance(b, '/skytokens', /you have ([\d,]+) sky tokens/i)

const v3 = (x, y, z) => new Vec3(x, y, z)
function solidAt(bot, x, y, z) {
  const b = bot.blockAt(v3(x, y, z))
  return b && b.name !== 'air' && b.name !== 'cave_air' && b.name !== 'void_air' ? b : null
}
function airAbove(bot, x, y, z) {
  const b = bot.blockAt(v3(x, y + 1, z))
  return !b || b.name === 'air' || b.name === 'cave_air' || b.name === 'void_air'
}

// ------------------------------------------------------------------- main
let owner = null
let guest = null
let HX = 0; let HZ = 0; let GY = 0; let HOME = null

async function main() {
  await startServer('boot1')
  await connectRcon()
  await baseSetup()
  auditLog('boot1')

  // ------------------------------------------------ P1 join/create/role
  phase(1, 'join + welcome + /is create + /role')
  owner = makeBot(OWNER)
  check(await waitSpawn(owner), 'owner spawns on first boot')
  check(await waitChat(owner, /welcome to/i, 20000), 'first-join welcome received')
  try { log('[dim] ' + owner.game.dimension) } catch { /* older field */ }
  clearChat(owner)
  owner.chat('/is create')
  check(await waitChat(owner, /has been created/i, 90000), '/is create pastes island')
  await sleep(1500)
  HOME = owner.entity.position.clone()
  HX = Math.floor(HOME.x); HZ = Math.floor(HOME.z)
  let ground = null
  for (let y = Math.floor(HOME.y); y > Math.floor(HOME.y) - 8; y--) {
    if (solidAt(owner, HX, y, HZ)) { ground = y; break }
  }
  check(ground !== null, 'island has solid ground under spawn')
  GY = ground ?? Math.floor(HOME.y) - 1
  log(`[island] home=${HOME.x.toFixed(1)},${HOME.y.toFixed(1)},${HOME.z.toFixed(1)} groundY=${GY}`)
  try {
    check(String(owner.game.dimension).includes('islands'), 'island lives in the islands world',
      String(owner.game.dimension))
  } catch { bad('island lives in the islands world', 'dimension unreadable') }

  let win = await openWindow(owner, '/role')
  check(win && win.slots.length === 54, '/role opens 54-slot panel')
  check(win && slotJson(win, 11).includes('miner'), 'Miner sits at slot 11')
  if (win) {
    clearChat(owner)
    await click(owner, 11)
    check(await waitChat(owner, /now a/i), 'role select confirms Miner')
    check(invHas(owner, 'netherite_pickaxe', 'omni-tool'), 'Omni-Tool minted to inventory')
    await closeWin(owner)
  }

  // ------------------------------------------------ P2 economy + shop
  phase(2, 'economy grants + shop buy/sell + tokenshop exchange')
  const m0 = await moneyOf(owner)
  const c0 = await creditsOf(owner)
  const t0 = await tokensOf(owner)
  check(m0 !== null && c0 !== null && t0 !== null, 'balance commands answer',
    `m=${m0} c=${c0} t=${t0}`)
  await rc(`money give ${OWNER} 100000`)
  await rc(`credits give ${OWNER} 100000`)
  await rc(`skytokens give ${OWNER} 100000`)
  const m1 = await moneyOf(owner)
  const c1 = await creditsOf(owner)
  const t1 = await tokensOf(owner)
  check(m1 === m0 + 100000 && c1 === c0 + 100000 && t1 === t0 + 100000,
    'RCON economy grants land exactly', `m=${m1} c=${c1} t=${t1}`)

  win = await openWindow(owner, '/shop')
  check(win && win.slots.length === 54, '/shop opens 54-slot hub')
  check(win && slotJson(win, 20).includes('gear'), 'gear category at slot 20')
  if (win) {
    await click(owner, 20)
    await sleep(1200)
    const cat = owner.currentWindow
    check(cat && slotJson(cat, 10).includes('iron sword'), 'iron sword is first gear entry')
    if (cat) {
      const before = await moneyOf(owner)
      clearChat(owner)
      await click(owner, 10)
      check(await waitChat(owner, /bought/i), 'buy confirms in chat')
      check(invHas(owner, 'iron_sword'), 'iron sword delivered')
      const after = await moneyOf(owner)
      check(after === before - 250, 'buy debits exactly 250 money', `${before} -> ${after}`)
    }
    await closeWin(owner)
  }

  await rc(`minecraft:give ${OWNER} minecraft:bread 16`)
  await sleep(1000)
  check(invCount(owner, 'bread') >= 16, 'RCON bread grant arrives')
  win = await openWindow(owner, '/shop food')
  check(win && slotJson(win, 10).includes('bread'), 'bread is first food entry')
  if (win) {
    const before = await moneyOf(owner)
    clearChat(owner)
    await click(owner, 10, 1) // right-click = sell
    check(await waitChat(owner, /sold/i), 'sell confirms in chat')
    const after = await moneyOf(owner)
    check(after === before + 16, 'sell pays exactly 16 money (sell-boost 0)', `${before} -> ${after}`)
    check(invCount(owner, 'bread') === 0, 'sold stock leaves inventory')
    await closeWin(owner)
  }

  win = await openWindow(owner, '/tokenshop')
  check(win && win.slots.length === 54, '/tokenshop opens 54-slot exchange')
  check(win && slotJson(win, 20).includes('sky token'), 'token-small at slot 20')
  if (win) {
    const mb = await moneyOf(owner)
    const tb = await tokensOf(owner)
    clearChat(owner)
    await click(owner, 20)
    check(await waitChat(owner, /exchanged/i), 'exchange confirms in chat')
    const ma = await moneyOf(owner)
    const ta = await tokensOf(owner)
    check(ma === mb - 10000 && ta === tb + 1, 'exchange moves -10000 money / +1 token',
      `m ${mb}->${ma}, t ${tb}->${ta}`)
    await closeWin(owner)
  }

  // ------------------------------------------------ P3 upgrades
  phase(3, '/is upgrades: border tier 1')
  win = await openWindow(owner, '/is upgrades')
  check(win && win.slots.length === 54, '/is upgrades opens 54-slot hub')
  check(win && slotJson(win, 25).includes('island'), 'island category at slot 25')
  if (win) {
    await click(owner, 25)
    await sleep(1200)
    const cat = owner.currentWindow
    check(cat && slotJson(cat, 10).includes('border'), 'border is first island track')
    if (cat) {
      const before = await tokensOf(owner)
      clearChat(owner)
      await click(owner, 10)
      check(await waitChat(owner, /upgrade purchased/i), 'upgrade purchase confirms')
      const after = await tokensOf(owner)
      check(after === before - 10, 'border T1 costs exactly 10 tokens', `${before} -> ${after}`)
    }
    await closeWin(owner)
  }

  // ------------------------------------------------ P4 buffs
  phase(4, '/is buffs: mining-boost tier 1')
  win = await openWindow(owner, '/is buffs')
  check(win && win.slots.length === 54, '/is buffs opens 54-slot panel')
  const buffSlot = win ? findSlotByName(win, 'mining', 10, 24) : -1
  check(buffSlot >= 0, 'mining-boost present in buff grid', 'slot=' + buffSlot)
  if (win && buffSlot >= 0) {
    const before = await tokensOf(owner)
    clearChat(owner)
    await click(owner, buffSlot)
    check(await waitChat(owner, /purchased! level/i), 'buff purchase confirms')
    const after = await tokensOf(owner)
    check(after === before - 100, 'mining-boost T1 costs exactly 100 tokens', `${before} -> ${after}`)
    await closeWin(owner)
  }

  // ------------------------------------------------ P5 spawners
  phase(5, '/spawners: lanes, 25 real kills, unlock, buy, place')
  const LANES = [10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 31]
  win = await openWindow(owner, '/spawners')
  check(win && win.slots.length === 54, '/spawners opens 54-slot panel')
  check(win && LANES.every((s) => slotType(win, s)), 'all 15 mob lanes render')
  const laneSlot = win ? findSlotByName(win, 'zombie', 10, 31) : -1
  check(laneSlot >= 0, 'zombie lane present', 'slot=' + laneSlot)
  if (win && laneSlot >= 0) {
    await click(owner, laneSlot)
    await sleep(1200)
    const tier = owner.currentWindow
    check(tier && tier.slots.length === 54, 'zombie submenu is 54 slots')
    check(tier && slotType(tier, 20) === 'barrier', 'tier 1 renders locked (barrier)')
    check(tier && slotJson(tier, 20).includes('25'), 'locked lore shows 25-kill requirement')
    await closeWin(owner)
  }

  const sword = owner.inventory.items().find((i) => i.name === 'iron_sword')
  if (sword) await owner.equip(sword, 'hand')
  await rc(`effect give ${OWNER} minecraft:regeneration infinite 1 true`)
  await rc(`effect give ${OWNER} minecraft:saturation infinite 1 true`)
  clearChat(owner)
  let kills = 0
  let died = false
  for (let i = 0; i < 25; i++) {
    const p = owner.entity.position
    await rc(`execute in ${DIM} run summon minecraft:zombie ${p.x + 2} ${p.y} ${p.z}`)
    let target = null
    const s0 = Date.now()
    while (Date.now() - s0 < 15000) {
      target = owner.nearestEntity(
        (e) => e.name === 'zombie' && e.position.distanceTo(owner.entity.position) < 14)
      if (target) break
      await sleep(500)
    }
    if (!target) { bad(`zombie ${i + 1} never appeared`, 'summon failed?'); break }
    const k0 = Date.now()
    for (;;) {
      if (owner.health <= 0) { died = true; break }
      target = owner.nearestEntity(
        (e) => e.name === 'zombie' && e.position.distanceTo(owner.entity.position) < 16)
      if (!target) break
      if (Date.now() - k0 > 75000) break
      try { owner.lookAt(target.position.offset(0, 1.4, 0)) } catch { /* noop */ }
      owner.attack(target)
      await sleep(600)
    }
    if (died) break
    const still = owner.nearestEntity((e) => e.name === 'zombie'
      && e.position.distanceTo(owner.entity.position) < 16)
    if (still) { bad(`zombie ${i + 1} survived 75s of melee`, 'combat stuck'); break }
    kills++
    await sleep(250)
  }
  check(!died, 'owner survives the 25-kill grind')
  check(kills === 25, '25 zombies summoned and slain', `kills=${kills}`)
  check(await waitChat(owner, /SPAWNER UNLOCKED/i, 20000), 'unlock fanfare at 25 kills')

  win = await openWindow(owner, '/spawners')
  const lane2 = win ? findSlotByName(win, 'zombie', 10, 31) : -1
  if (win && lane2 >= 0) {
    await click(owner, lane2)
    await sleep(1200)
    const tier = owner.currentWindow
    check(tier && slotType(tier, 20) === 'spawner', 'tier 1 unlocks to a spawner item')
    if (tier && slotType(tier, 20) === 'spawner') {
      const before = await tokensOf(owner)
      clearChat(owner)
      await click(owner, 20)
      check(await waitChat(owner, /purchased/i), 'spawner purchase confirms')
      const after = await tokensOf(owner)
      check(after === before - 5, 'zombie spawner I costs exactly 5 tokens', `${before} -> ${after}`)
      check(invHas(owner, 'spawner', 'zombie spawner'), 'spawner item delivered')
    }
    await closeWin(owner)
  } else {
    bad('zombie submenu reopens after unlock')
  }
  // place on the platform, verify the block, dig it back up (no stray spawns)
  let placedAt = null
  for (let r = 1; r <= 5 && !placedAt; r++) {
    for (const [dx, dz] of [[r, 0], [-r, 0], [0, r], [0, -r]]) {
      if (solidAt(owner, HX + dx, GY, HZ + dz) && airAbove(owner, HX + dx, GY, HZ + dz)) {
        placedAt = { x: HX + dx, y: GY, z: HZ + dz }
        break
      }
    }
  }
  check(!!placedAt, 'found a free platform cell for the spawner')
  if (placedAt) {
    const item = owner.inventory.items().find((i) => i.name === 'spawner')
    let placed = false
    if (item) {
      await owner.equip(item, 'hand')
      try {
        await owner.placeBlock(owner.blockAt(v3(placedAt.x, placedAt.y, placedAt.z)), v3(0, 1, 0))
        await sleep(800)
        const b = owner.blockAt(v3(placedAt.x, placedAt.y + 1, placedAt.z))
        placed = !!b && b.name === 'spawner'
      } catch (e) { log('[spawner] place failed: ' + e.message) }
    }
    check(placed, 'spawner places on the island')
    if (placed) {
      try {
        const b = owner.blockAt(v3(placedAt.x, placedAt.y + 1, placedAt.z))
        if (b && owner.canDigBlock(b)) await owner.dig(b)
      } catch (e) { log('[spawner] dig-back failed: ' + e.message) }
      await sleep(500)
      const gone = owner.blockAt(v3(placedAt.x, placedAt.y + 1, placedAt.z))
      check(!gone || gone.name === 'air', 'test spawner removed after verification')
    }
  }

  // ------------------------------------------------ P6 enchants + keys
  phase(6, 'omni panel -> treasure-miner -> mine 2 blocks -> 2 sky keys')
  const omni = owner.inventory.items().find((i) => i.name === 'netherite_pickaxe')
  check(!!omni, 'omni-tool still held for panel test')
  let ewin = null
  if (omni) {
    await owner.equip(omni, 'hand')
    try { if (owner.currentWindow) owner.closeWindow(owner.currentWindow) } catch { /* noop */ }
    await sleep(400)
    owner.setControlState('sneak', true)
    await sleep(300)
    owner.activateItem()
    await sleep(600)
    owner.setControlState('sneak', false)
    const panel = await waitWindow(owner, 15000)
    check(!!panel, 'shift-right-click opens the omni panel')
    if (panel) {
      await click(owner, 4)
      await sleep(1200)
      ewin = owner.currentWindow
    }
  }
  check(ewin && ewin.slots.length === 54, 'miner enchant grid is 54 slots')
  check(ewin && slotJson(ewin, 31).includes('treasure'), 'treasure-miner sits at grid slot 31')
  if (ewin) {
    const before = await tokensOf(owner)
    clearChat(owner)
    await click(owner, 31)
    check(await waitChat(owner, /ENCHANT/i), 'enchant purchase confirms')
    const after = await tokensOf(owner)
    check(after === before - 1, 'overlay: treasure-miner costs 1 token', `${before} -> ${after}`)
    await closeWin(owner)
  }
  // mine two natural platform blocks (overlay: each procs a sky key)
  const mined = []
  for (let r = 2; r <= 6 && mined.length < 2; r++) {
    for (const [dx, dz] of [[r, 1], [r, -1], [1, r], [-1, r], [-r, 0], [0, -r]]) {
      if (mined.length >= 2) break
      const bx = HX + dx; const bz = HZ + dz
      const blk = solidAt(owner, bx, GY, bz)
      if (!blk || !airAbove(owner, bx, GY, bz)) continue
      if (Math.abs(bx - HX) + Math.abs(bz - HZ) < 2) continue
      await rc(`tp ${OWNER} ${bx + 2.5} ${GY + 1} ${bz + 0.5}`)
      await sleep(1200)
      const target = owner.blockAt(v3(bx, GY, bz))
      if (!target || target.name === 'air' || !owner.canDigBlock(target)) continue
      clearChat(owner)
      let digTimedOut = false
      try {
        await Promise.race([owner.dig(target), sleep(90000).then(() => { digTimedOut = true })])
      } catch (e) { bad('digging platform block', e.message); continue }
      if (digTimedOut) { bad('digging platform block', 'dig timeout'); continue }
      const got = await waitChat(owner, /sky key/i, 15000)
      check(!!got, `block ${mined.length + 1} procs a sky key (overlay)`)
      mined.push({ x: bx, y: GY, z: bz, mat: target.name })
    }
  }
  check(mined.length === 2, 'mined 2 platform blocks')
  check(invCount(owner, 'tripwire_hook') === 2, 'exactly 2 sky keys minted',
    `hooks=${invCount(owner, 'tripwire_hook')}`)
  for (const m of mined) {
    await rc(`execute in ${DIM} run setblock ${m.x} ${m.y} ${m.z} minecraft:${m.mat}`)
  }
  await sleep(800)

  // ------------------------------------------------ P7 crates
  phase(7, '/crates: two sky opens (2nd is the pity) + no-key path')
  win = await openWindow(owner, '/crates')
  check(win && win.slots.length === 54, '/crates opens 54-slot lineup')
  check(win && slotJson(win, 19).includes('sky'), 'sky crate leads the lineup')
  if (win) {
    await click(owner, 19)
    await sleep(1200)
    const preview = owner.currentWindow
    check(preview && slotType(preview, 40) === 'emerald_block', 'preview has OPEN at slot 40')
    if (preview) {
      clearChat(owner)
      await click(owner, 40)
      check(await waitChat(owner, /opened.*won/i), 'first open pays a rolled reward')
      check(invCount(owner, 'tripwire_hook') === 1, 'first open consumes one key')
    }
    await closeWin(owner)
  }
  win = await openWindow(owner, '/crates')
  if (win) {
    await click(owner, 19)
    await sleep(1200)
    const preview = owner.currentWindow
    if (preview) {
      const before = await creditsOf(owner)
      clearChat(owner)
      await click(owner, 40)
      check(await waitChat(owner, /PITY!/i), 'second open pays the pity (overlay pity-count 2)')
      const after = await creditsOf(owner)
      check(after === before + 50, 'sky pity pays exactly 50 credits', `${before} -> ${after}`)
      check(invCount(owner, 'tripwire_hook') === 0, 'second open consumes the last key')
      clearChat(owner)
      await click(owner, 40)
      check(await waitChat(owner, /you need/i), 'open without a key refuses cleanly')
    } else {
      bad('sky preview reopens for open #2')
    }
    await closeWin(owner)
  }

  // ------------------------------------------------ P8 protection
  phase(8, 'outsider protection: guest dig denied, block intact')
  guest = makeBot(GUEST)
  check(await waitSpawn(guest), 'guest spawns')
  let victim = null
  for (let r = 2; r <= 5 && !victim; r++) {
    for (const [dx, dz] of [[-r, 1], [-r, -1], [r, 2], [2, r]]) {
      if (solidAt(owner, HX + dx, GY, HZ + dz) && airAbove(owner, HX + dx, GY, HZ + dz)) {
        victim = { x: HX + dx, y: GY, z: HZ + dz }
        break
      }
    }
  }
  check(!!victim, 'found a platform block for the guest to attack')
  if (victim) {
    const matBefore = owner.blockAt(v3(victim.x, victim.y, victim.z)).name
    await rc(`execute in ${DIM} run tp ${GUEST} ${victim.x + 2.5} ${GY + 1} ${victim.z + 0.5}`)
    await sleep(1500)
    clearChat(guest)
    const gTarget = guest.blockAt(v3(victim.x, victim.y, victim.z))
    if (gTarget && gTarget.name !== 'air') {
      try {
        await Promise.race([guest.dig(gTarget).catch(() => {}), sleep(9000)])
      } catch { /* denial may reject — that is the point */ }
    }
    await sleep(1000)
    const matAfter = owner.blockAt(v3(victim.x, victim.y, victim.z)).name
    check(matAfter === matBefore, 'guest dig leaves the block intact', `${matBefore} -> ${matAfter}`)
    check(await waitChat(guest, /protected/i, 10000), 'guest sees the protection denial')
    guest.quit()
    await sleep(1500)
  }

  // ------------------------------------------------ P9 generators
  phase(9, '/gens: buy cobble gen, place, harvest')
  win = await openWindow(owner, '/gens')
  check(win && win.slots.length === 54, '/gens opens 54-slot market')
  check(win && slotJson(win, 20).includes('cobble'), 'cobble gen leads the market')
  if (win) {
    const before = await creditsOf(owner)
    clearChat(owner)
    await click(owner, 20)
    check(await waitChat(owner, /purchased/i), 'gen purchase confirms')
    const after = await creditsOf(owner)
    check(after === before - 5000, 'cobble gen costs exactly 5000 credits', `${before} -> ${after}`)
    check(invHas(owner, 'observer'), 'gen item delivered')
    await closeWin(owner)
  }
  let genAt = null
  for (let r = 1; r <= 5 && !genAt; r++) {
    for (const [dx, dz] of [[0, r], [0, -r], [r, 0], [-r, 0]]) {
      if (solidAt(owner, HX + dx, GY, HZ + dz) && airAbove(owner, HX + dx, GY, HZ + dz)) {
        genAt = { x: HX + dx, y: GY, z: HZ + dz }
        break
      }
    }
  }
  check(!!genAt, 'found a free platform cell for the gen')
  if (genAt) {
    const item = owner.inventory.items().find((i) => i.name === 'observer')
    let placed = false
    if (item) {
      await owner.equip(item, 'hand')
      try {
        await owner.placeBlock(owner.blockAt(v3(genAt.x, genAt.y, genAt.z)), v3(0, 1, 0))
        await sleep(800)
        const b = owner.blockAt(v3(genAt.x, genAt.y + 1, genAt.z))
        placed = !!b && b.name === 'observer'
      } catch (e) { log('[gen] place failed: ' + e.message) }
    }
    check(placed, 'gen places on the island')
    if (placed) {
      await sleep(6500) // cobble cooldown is 5s
      clearChat(owner)
      const genBlock = owner.blockAt(v3(genAt.x, genAt.y + 1, genAt.z))
      try { await owner.activateBlock(genBlock) } catch (e) { log('[gen] harvest click: ' + e.message) }
      check(await waitChat(owner, /\+\d+.*cobble/i, 15000), 'harvest pays cobblestone to chat')
      check(invCount(owner, 'cobblestone') >= 1, 'harvested cobble lands in inventory')
    }
  }

  // ------------------------------------------------ P10 void rescue
  phase(10, 'void rescue')
  clearChat(owner)
  await rc(`tp ${OWNER} ${HX + 0.5} -80 ${HZ + 0.5}`)
  check(await waitChat(owner, /void rejects/i, 20000), 'void rescue message fires')
  await sleep(1000)
  const rescued = owner.entity.position
  const homeDist = rescued.distanceTo(HOME)
  check(homeDist < 3, 'rescue lands back home', `dist=${homeDist.toFixed(2)}`)
  check(owner.health > 0, 'rescue prevents death', `health=${owner.health}`)

  // ------------------------------------------------ P11 restart
  phase(11, 'clean restart: everything persists')
  const snapMoney = await moneyOf(owner)
  const snapCredits = await creditsOf(owner)
  const snapTokens = await tokensOf(owner)
  log(`[snapshot] money=${snapMoney} credits=${snapCredits} tokens=${snapTokens}`)
  owner.quit()
  await sleep(3000)
  await stopServer()
  await startServer('boot2')
  await connectRcon()
  await baseSetup()
  auditLog('boot2')

  owner = makeBot(OWNER)
  check(await waitSpawn(owner), 'owner rejoins after restart')
  await sleep(1000)
  clearChat(owner)
  owner.chat('/is home')
  await sleep(2500)
  const homeDist2 = owner.entity.position.distanceTo(HOME)
  check(homeDist2 < 3, '/is home still works after restart', `dist=${homeDist2.toFixed(2)}`)
  win = await openWindow(owner, '/role')
  check(win && slotJson(win, 4).includes('miner'), 'role still Miner after restart')
  await closeWin(owner)
  const m2 = await moneyOf(owner)
  const c2 = await creditsOf(owner)
  const t2 = await tokensOf(owner)
  check(m2 === snapMoney && c2 === snapCredits && t2 === snapTokens,
    'all three balances persist exactly', `m ${snapMoney}->${m2}, c ${snapCredits}->${c2}, t ${snapTokens}->${t2}`)
  clearChat(owner)
  owner.chat('/is info')
  check(await waitChat(owner, /JOwner/i, 15000), '/is info shows the owner')

  // data-file asserts (post-stop flush => files are authoritative now)
  const users = yaml.load(fs.readFileSync(path.join(PLUGIN_DIR, 'usernames.yml'), 'utf8'))
  const uuid = users && (users[OWNER.toLowerCase()] || users[OWNER])
  check(!!uuid, 'username index maps the owner', String(uuid))
  if (uuid) {
    const prof = yaml.load(fs.readFileSync(
      path.join(PLUGIN_DIR, 'profiles', `${uuid}.yml`), 'utf8'))
    check(prof.role === 'miner', 'profile: role=miner', String(prof.role))
    const zk = (prof['kill-counts'] && prof['kill-counts'].zombie) || 0
    check(zk >= 25, 'profile: zombie kills persist', `zombie=${zk}`)
    const pity = (prof.stats && prof.stats['crate-pity:sky']) || 0
    check(pity === 0, 'profile: sky pity counter reset by payout', `pity=${pity}`)
    const enchLvl = (prof['enchant-levels'] && prof['enchant-levels']['miner.treasure-miner']) || 0
    check(enchLvl >= 1, 'profile: treasure-miner owned', `level=${enchLvl}`)
    check(prof.money === snapMoney && prof.credits === snapCredits && prof['sky-tokens'] === snapTokens,
      'profile file balances match live snapshot')
    const isl = yaml.load(fs.readFileSync(
      path.join(PLUGIN_DIR, 'islands', `${uuid}.yml`), 'utf8'))
    check(isl.world === 'islands', 'island file: world=islands', String(isl.world))
    check(isl.upgrades && isl.upgrades.border === 1, 'island file: border tier persists')
    const buffTier = isl.buffs && (isl.buffs['mining-boost'] || isl.buffs['mining_boost'])
    check(buffTier === 1, 'island file: mining-boost tier persists', `tier=${buffTier}`)
  }

  // ------------------------------------------------ P12 audit
  phase(12, 'full-session log audit')
  auditLog('session')
}

try {
  await main()
} catch (e) {
  bad('journey aborted', (e && e.stack ? e.stack : String(e)).split('\n').slice(0, 6).join(' | '))
}
try { if (owner) owner.quit() } catch { /* noop */ }
try { if (guest) guest.quit() } catch { /* noop */ }
await sleep(2000)
if (serverProc && (serverProc.exitCode === null || serverProc.exitCode === undefined)) {
  try {
    if (!rcon) await connectRcon(30000)
    await stopServer()
  } catch { try { serverProc.kill('SIGKILL') } catch { /* noop */ } }
}
log('')
log(`JOURNEY RESULT: ${pass} passed, ${fail} failed`)
if (failures.length) log('failures: ' + failures.join(' // '))
jlog.end()
await sleep(500)
process.exit(fail > 0 ? 1 : 0)
