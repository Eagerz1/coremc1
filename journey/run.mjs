#!/usr/bin/env node
// CoreMC live player journey.
//
// Boots a Paper server from THIS checkout (plugin jar built from the exact
// HEAD under test), then drives a complete two-player session with
// mineflayer bots + RCON and asserts every headliner flow:
//
//   P1  join + welcome + /is create (tracks the real level name across the
//       same-dimension world swap) + /role (Miner + Omni-Tool)
//   P2  economy grants + /money|/credits|/skytokens + shop buy + shop sell
//       + /tokenshop exchange (live fitsDeposit path)
//   P3  /is upgrades: six categories render; buy border tier 1
//   P4  /is buffs: all 12 buffs render; buy mining-boost tier 1
//   P5  /spawners: 15 lanes, locked submenu (I..IV + Ancient), 25 REAL
//       kills, unlock fanfare, buy + place tier I; spawner-born kill pays
//       Core money/tokens WITHOUT counting wild progress; hostile GUI
//       interactions (shift/number-key/drop/double-click) cannot steal
//   P5b Ancient tier: admin kill grant, buy + place Ancient spawner,
//       kill the Ancient zombie -> +3 progress toward SKELETON as ONE
//       kill event (source lane counter untouched)
//   P6  Omni-Tool panel -> enchants (15-grid) -> buy treasure-miner
//       (overlay: deterministic), mine 2 blocks -> 2 sky keys
//   P7  /crates: six crates render; open sky x2 (2nd is deterministically
//       the pity), no-key negative path
//   P8  outsider protection: guest dig denied, block intact
//   P9  /gens: four generators render; buy cobble gen, place, harvest
//   P10 void rescue back home
//   P11 clean restart: everything persists (live + data-file asserts,
//       incl. dotted enchant ids and ancient skeleton progress)
//   P12 full-log audit: zero server ERRORs, zero CoreMC warn/error lines
//
// Run from journey-server/ (cwd is the server dir). Exit 0 = all green.
import mineflayer from 'mineflayer'
import { Vec3 } from 'vec3'
import { Rcon } from 'rcon-client'
import { load as yamlLoad } from 'js-yaml'
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
const WORLD_ISLANDS = 'minecraft:islands'
const DIM = WORLD_ISLANDS

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
  // Gamerules/time/difficulty are per-level in modern Paper: apply them to
  // BOTH the hub world and the islands world. Monster spawners obey the
  // vanilla darkness rule (torches stop dungeon spawners), so the islands
  // must stay at night for P5/P5b — natural spawning is still disabled on
  // that world at creation (setSpawnFlags), so no wild mobs can leak.
  for (const dim of ['minecraft:overworld', 'minecraft:islands']) {
    const inDim = (cmd) => rc(`execute in ${dim} run ${cmd}`)
    await inDim('gamerule doDaylightCycle false')
    await inDim('time set midnight')
    await inDim('gamerule doMobSpawning true')
    await inDim('gamerule doTraderSpawning false')
    await inDim('difficulty normal')
  }
  log('[setup] gamerules applied (overworld + islands)')
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
//
// mineflayer 1.21.11 quirk: both the hub and the islands world use
// dimension type 0, so the client library never swaps bot.world and
// reports the dimension TYPE ("overworld") rather than the level name.
// We track the level name ourselves from the nested worldState of the
// login/respawn packets, and always sample blocks at the bot's LIVE
// position after the teleport settles (islands are grid-allocated).
function makeBot(name) {
  const bot = mineflayer.createBot({
    host: '127.0.0.1', port: 25565, username: name, auth: 'offline', version: '1.21.11',
  })
  bot.__name = name
  bot.__chat = []
  bot.__world = null
  const readWorld = (packet) => {
    const ws = packet?.worldState ?? packet
    if (ws && typeof ws.name === 'string') bot.__world = ws.name
  }
  bot._client.on('login', readWorld)
  bot._client.on('respawn', readWorld)
  bot.on('messagestr', (m) => { bot.__chat.push(m); if (bot.__chat.length > 600) bot.__chat.shift() })
  bot.on('error', (e) => log(`[${name}] bot error: ${e.message}`))
  bot.on('kicked', (reason) => {
    if (expectedQuit.has(name)) return // our own .quit() surfaces as a kick
    bad(`${name} kicked`, String(reason).slice(0, 200))
  })
  bot.on('end', () => log(`[${name}] connection ended`))
  return bot
}
function quitBot(bot, name) {
  expectedQuit.add(name)
  try { bot.quit() } catch { /* already gone */ }
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
async function waitUntil(predicate, timeoutMs = 30000, pollMs = 500) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    const value = await predicate()
    if (value) return value
    await sleep(pollMs)
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
async function click(bot, slot, button = 0, mode = 0) {
  await bot.clickWindow(slot, button, mode)
  await sleep(700)
}
// Sends a hostile-gesture window click at the wire level so the harness can
// exercise modes the vanilla client library refuses to craft locally
// (creative-middle-click, double-click with empty cursor, out-of-window
// number swaps). The server must parse and ignore them; a refusal must not
// hand over items or currency.
function rawClick(bot, slot, mouseButton, mode) {
  const win = bot.currentWindow
  if (!win) return false
  try {
    bot._client.write('window_click', {
      windowId: win.id,
      stateId: win.stateId ?? 0,
      slot,
      mouseButton,
      actionNumber: bot._rawClickAction = (bot._rawClickAction || 0) + 1,
      mode,
      changedSlots: [],
      cursorItem: null,
    })
    return true
  } catch { return false }
}
async function closeWin(bot) {
  try { if (bot.currentWindow) bot.closeWindow(bot.currentWindow) } catch { /* noop */ }
  await sleep(500)
}
function invCount(bot, type) {
  return bot.inventory.items().filter((i) => i.name === type).reduce((a, i) => a + i.count, 0)
}
function invSnapshot(bot) {
  return bot.inventory.items().map((i) => `${i.name}:${i.count}`).sort().join(',')
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
function customNameOf(e) {
  // 1.21 carries the custom name on the shared entity metadata key 2 as a
  // text component (plain string for Paper custom names).
  try {
    const v = e.metadata && e.metadata[2]
    if (v == null) return ''
    if (typeof v === 'string') return v
    return JSON.stringify(v)
  } catch { return '' }
}
function nearbyMobs(bot, kind, maxDist = 18, displayMatch = null) {
  return Object.values(bot.entities).filter((e) => {
    if (e === bot.entity || e.name !== kind) return false
    if (!e.position || e.position.distanceTo(bot.entity.position) > maxDist) return false
    if (displayMatch) {
      const dn = String(customNameOf(e) || e.displayName || e.username || '').toLowerCase()
      if (!displayMatch.test(dn)) return false
    }
    return true
  })
}
// Short walk on flat ground; mineflayer client receives chunks it walks into
// (unlike an RCON teleport, which can leave the client with stale/empty data).
async function walkTo(bot, x, z, settleMs = 700) {
  const goal = v3(x, bot.entity.position.y, z)
  for (let i = 0; i < 60; i++) {
    const p = bot.entity.position
    const dx = x - p.x, dz = z - p.z
    const dist = Math.hypot(dx, dz)
    if (dist < 0.25) break
    await bot.look(Math.atan2(-dx, -dz), 0, true)
    bot.setControlState('forward', true)
    await sleep(180)
    if (Math.hypot(x - bot.entity.position.x, z - bot.entity.position.z) < 0.25) break
    await sleep(20)
  }
  bot.setControlState('forward', false)
  await bot.look(0, 0, true).catch(() => {})
  await sleep(settleMs)
  return Math.hypot(x - bot.entity.position.x, z - bot.entity.position.z)
}
// Polls until the client actually holds the target block (post-tp chunk safety).
async function waitBlockReady(bot, x, y, z, wantSolid = true, timeoutMs = 20000) {
  return waitUntil(() => {
    const b = bot.blockAt(v3(x, y, z))
    if (!b) return false
    const solid = !['air', 'cave_air', 'void_air'].includes(b.name)
    return wantSolid ? solid : !solid
  }, timeoutMs, 300)
}
async function waitForMob(bot, kind, timeoutMs, displayMatch = null) {
  return waitUntil(() => {
    const list = nearbyMobs(bot, kind, 20, displayMatch)
    return list.length ? list[0] : null
  }, timeoutMs, 500)
}
async function killMob(bot, initial, timeoutMs = 90000) {
  const k0 = Date.now()
  let target = initial
  while (Date.now() - k0 < timeoutMs) {
    if (bot.health <= 0) return false
    target = Object.values(bot.entities).find((e) => e.id === target.id)
    if (!target || target.isValid === false) return true
    try { bot.lookAt(target.position.offset(0, 1.2, 0)) } catch { /* noop */ }
    bot.attack(target)
    await sleep(550)
  }
  return false
}

// ------------------------------------------------------------------- main
let owner = null
let guest = null
const expectedQuit = new Set()
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
  clearChat(owner)
  owner.chat('/is create')
  check(await waitChat(owner, /has been created/i, 90000), '/is create pastes island')
  // Wait for the real world swap (level name from the respawn packet) and
  // for chunks + the teleport to settle at the bot's live position.
  const inWorld = await waitUntil(() => owner.__world === WORLD_ISLANDS, 60000)
  check(!!inWorld, 'client was sent to the islands level', String(owner.__world))
  let ground = null
  const settled = await waitUntil(() => {
    const p = owner.entity.position
    let g = null
    for (let y = Math.floor(p.y); y > Math.floor(p.y) - 10; y--) {
      if (solidAt(owner, Math.floor(p.x), y, Math.floor(p.z))) { g = y; break }
    }
    if (g) return g
    return null
  }, 60000)
  check(settled !== null, 'island has solid ground under the live spawn position')
  await sleep(2000) // let the teleport + chunk batch fully settle
  HOME = owner.entity.position.clone()
  HX = Math.floor(HOME.x); HZ = Math.floor(HOME.z); GY = settled
  log(`[island] home=${HOME.x.toFixed(1)},${HOME.y.toFixed(1)},${HOME.z.toFixed(1)} groundY=${GY} world=${owner.__world}`)
  check(owner.__world === WORLD_ISLANDS, 'island lives in the islands world', String(owner.__world))
  // Grid cell sanity: island platforms are allocated on a 256-block grid.
  check(HX % 256 === 0 && HZ % 256 === 0, 'spawn landed on an island grid cell', `x=${HX} z=${HZ}`)

  let win = await openWindow(owner, '/role')
  check(win && win.inventoryStart === 54, '/role opens 54-slot panel')
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
  check(win && win.inventoryStart === 54, '/shop opens 54-slot hub')
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
  check(win && win.inventoryStart === 54, '/tokenshop opens 54-slot exchange')
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
  phase(3, '/is upgrades: six categories render; buy border tier 1')
  win = await openWindow(owner, '/is upgrades')
  check(win && win.inventoryStart === 54, '/is upgrades opens 54-slot hub')
  for (const [slot, label] of [[19, 'mining'], [20, 'fishing'], [21, 'farming'],
    [23, 'slaying'], [24, 'logging'], [25, 'island']]) {
    check(win && slotJson(win, slot).includes(label), `upgrade category ${label} renders`, 'slot=' + slot)
  }
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
  phase(4, '/is buffs: all 12 buffs render; buy mining-boost tier 1')
  win = await openWindow(owner, '/is buffs')
  check(win && win.inventoryStart === 54, '/is buffs opens 54-slot panel')
  const BUFF_SLOTS = [10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23]
  const buffWords = ['mining', 'farming', 'fishing', 'slaying', 'logging', 'generator',
    'spawner', 'token', 'credit', 'xp', 'sell', 'luck']
  let renderedBuffs = 0
  for (const [i, s] of BUFF_SLOTS.entries()) {
    if (win && slotJson(win, s).includes(buffWords[i])) renderedBuffs++
  }
  check(renderedBuffs === 12, 'all 12 island buffs render', `${renderedBuffs}/12`)
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
  phase(5, '/spawners: lanes, 25 real kills, unlock, buy, place, spawner kill rewards')
  const LANES = [10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 31]
  win = await openWindow(owner, '/spawners')
  check(win && win.inventoryStart === 54, '/spawners opens 54-slot panel')
  check(win && LANES.every((s) => slotType(win, s)), 'all 15 mob lanes render')
  const laneSlot = win ? findSlotByName(win, 'zombie', 10, 31) : -1
  check(laneSlot >= 0, 'zombie lane present', 'slot=' + laneSlot)
  // tier slots are 19..23 = I, II, III, IV, Ancient
  const TIER_SLOTS = [19, 20, 21, 22, 23]
  if (win && laneSlot >= 0) {
    await click(owner, laneSlot)
    await sleep(1200)
    const tier = owner.currentWindow
    check(tier && tier.inventoryStart === 54, 'zombie submenu is 54 slots')
    check(tier && slotType(tier, 19) === 'barrier', 'tier 1 renders locked (barrier)')
    check(tier && slotJson(tier, 19).includes('25'), 'locked lore shows 25-kill requirement')
    check(tier && slotJson(tier, 23) !== null && /ancient/i.test(slotJson(tier, 23)),
      '5th slot is the Ancient variant')
    check(TIER_SLOTS.every((s) => tier && slotType(tier, s) === 'barrier'),
      'all five tiers (I..IV + Ancient) start locked')
    await closeWin(owner)
  }

  const sword = owner.inventory.items().find((i) => i.name === 'iron_sword')
  if (sword) await owner.equip(sword, 'hand')
  await rc(`effect give ${OWNER} minecraft:regeneration infinite 1 true`)
  await rc(`effect give ${OWNER} minecraft:saturation infinite 1 true`)
  await rc(`effect give ${OWNER} minecraft:strength infinite 1 true`)
  clearChat(owner)
  let kills = 0
  let died = false
  for (let i = 0; i < 25; i++) {
    const p = owner.entity.position
    // NoAI keeps the target on the 7x7 starter platform (knockback over the
    // edge would otherwise credit kills to the void, not the player).
    await rc(`execute in ${DIM} run summon minecraft:zombie ${p.x + 0.6} ${p.y} ${p.z + 0.4} {NoAI:1b,Silent:1b}`)
    const target = await waitForMob(owner, 'zombie', 15000)
    if (!target) { bad(`zombie ${i + 1} never appeared`, 'summon failed?'); break }
    const killed = await killMob(owner, target, 75000)
    if (owner.health <= 0) { died = true; break }
    if (!killed) { bad(`zombie ${i + 1} survived 75s of melee`, 'combat stuck'); break }
    kills++
    await sleep(200)
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
    check(tier && slotType(tier, 19) === 'spawner', 'tier 1 unlocks to a spawner item')
    check(tier && slotType(tier, 20) === 'barrier', 'tier 2 stays locked at 25 kills')
    if (tier && slotType(tier, 19) === 'spawner') {
      const before = await tokensOf(owner)
      clearChat(owner)
      await click(owner, 19)
      check(await waitChat(owner, /purchased/i), 'spawner purchase confirms')
      const after = await tokensOf(owner)
      check(after === before - 5, 'zombie spawner I costs exactly 5 tokens', `${before} -> ${after}`)
      check(invHas(owner, 'spawner', 'zombie spawner'), 'spawner item delivered')
    }
    await closeWin(owner)
  } else {
    bad('zombie submenu reopens after unlock')
  }

  // ---- hostile GUI interactions on a LOCKED skeleton submenu
  win = await openWindow(owner, '/spawners')
  if (win) {
    const skel = findSlotByName(win, 'skeleton', 10, 31)
    check(skel >= 0, 'skeleton lane present for hostile-GUI test', 'slot=' + skel)
    if (skel >= 0) {
      await click(owner, skel)
      await sleep(1000)
      const sub = owner.currentWindow
      check(sub && slotType(sub, 19) === 'barrier', 'skeleton tier 1 locked for hostile test')
      if (sub) {
        const beforeSnap = invSnapshot(owner)
        const beforeTok = await tokensOf(owner)
        clearChat(owner)
        // Hostile gesture storm: shift-click, hotbar number-swap, drop,
        // middle-click, double-click, right-click, filler click, and a
        // shift-click from the lower player inventory into the menu. Modes
        // the client library refuses to craft are sent on the wire so the
        // server parser itself is exercised; all must be refused cleanly.
        const gestures = [
          [19, 0, 1], [19, 0, 2], [19, 1, 2], [19, 2, 2],
          [19, 0, 4], [19, 0, 3], [19, 0, 6], [19, 1, 0],
          [4, 0, 0], [4, 1, 0],
          [54 + 13, 0, 1], [54 + 14, 0, 2], [54 + 14, 3, 2],
        ]
        for (const [s, b, m] of gestures) {
          try { await owner.clickWindow(s, b, m) } catch { /* lib refuses: fire raw */ }
          rawClick(owner, s, b, m)
          await sleep(150)
        }
        await sleep(800)
        check(owner.currentWindow === sub, 'menu survives hostile clicks (no close/crash)')
        check(slotType(sub, 19) === 'barrier', 'locked tier item cannot be taken')
        check(invSnapshot(owner) === beforeSnap, 'no item moved into or out of inventory')
        const afterTok = await tokensOf(owner)
        check(afterTok === beforeTok, 'locked/hostile clicks never spend currency',
          `${beforeTok} -> ${afterTok}`)
        await closeWin(owner)
      }
    } else {
      await closeWin(owner)
    }
  }

  // place the tier-I spawner and verify a spawner-born kill pays rewards
  let t1cell = null
  for (let r = 1; r <= 3 && !t1cell; r++) {
    for (const [dx, dz] of [[r, 0], [-r, 0], [0, r], [0, -r]]) {
      if (solidAt(owner, HX + dx, GY, HZ + dz) && airAbove(owner, HX + dx, GY, HZ + dz)) {
        t1cell = { x: HX + dx, y: GY, z: HZ + dz }
        break
      }
    }
  }
  check(!!t1cell, 'found a free platform cell for the tier-I spawner')
  let t1Placed = false
  if (t1cell) {
    const item = owner.inventory.items().find((i) => i.name === 'spawner'
      && !/ancient/i.test(JSON.stringify(i)))
    if (item) {
      await owner.equip(item, 'hand')
      try {
        await owner.placeBlock(owner.blockAt(v3(t1cell.x, t1cell.y, t1cell.z)), v3(0, 1, 0))
        await sleep(800)
        const b = owner.blockAt(v3(t1cell.x, t1cell.y + 1, t1cell.z))
        t1Placed = !!b && b.name === 'spawner'
      } catch (e) { log('[spawner] place failed: ' + e.message) }
    }
    check(t1Placed, 'tier-I spawner places on the island')
  }
  if (t1Placed) {
    // Re-equip the sword (placement left the spent spawner stack selected).
    const sword1 = owner.inventory.items().find((i) => i.name === 'iron_sword')
    if (sword1) await owner.equip(sword1, 'hand')
    // Stay at home: an RCON teleport can leave the client without chunks, so
    // walk a half-step toward the r=1 spawner; mobs aggro and path to us.
    await walkTo(owner, (HX + 0.5) * 0.5 + (t1cell.x + 0.5) * 0.5,
      (HZ + 0.5) * 0.5 + (t1cell.z + 0.5) * 0.5)
    const mob = await waitForMob(owner, 'zombie', 60000)
    check(!!mob, 'placed spawner cycles and produces a zombie')
    if (mob) {
      const moneyBefore = await moneyOf(owner)
      const tokensBefore = await tokensOf(owner)
      clearChat(owner)
      const dead = await killMob(owner, mob, 60000)
      check(dead, 'spawner-born zombie killed')
      const reward = await waitChat(owner, /spawner kill/i, 12000)
      check(!!reward, 'spawner kill pays Core money + Sky Tokens message',
        reward ? reward[0] : 'no reward line')
      const moneyAfter = await moneyOf(owner)
      const tokensAfter = await tokensOf(owner)
      check(moneyAfter > moneyBefore, 'spawner kill grants Core money',
        `${moneyBefore} -> ${moneyAfter}`)
      check(tokensAfter > tokensBefore, 'spawner kill grants Sky Tokens',
        `${tokensBefore} -> ${tokensAfter}`)
    }
  }

  // ----------------------------------------------- P5b Ancient variant
  phase('5b', 'Ancient tier: unlock, buy, place; Ancient kill = triple NEXT-lane progress')
  // 25 wild kills already counted; grant adds 250 to reach the Ancient gate of 275.
  await rc(`coremc kills ${OWNER} zombie 250`)
  await sleep(800)
  check(await waitChat(owner, /SPAWNER UNLOCKED/i, 15000), 'admin grant crosses unlock boundaries')
  win = await openWindow(owner, '/spawners')
  if (win) {
    const lane = findSlotByName(win, 'zombie', 10, 31)
    if (lane >= 0) {
      await click(owner, lane)
      await sleep(1200)
      const tier = owner.currentWindow
      check(tier && slotType(tier, 23) === 'spawner', 'Ancient tier unlocks to a spawner item')
      if (tier && slotType(tier, 23) === 'spawner') {
        const before = await tokensOf(owner)
        clearChat(owner)
        await click(owner, 23)
        check(await waitChat(owner, /purchased/i), 'Ancient spawner purchase confirms')
        const after = await tokensOf(owner)
        check(after === before - 100, 'Ancient zombie spawner costs exactly 100 tokens',
          `${before} -> ${after}`)
        check(invHas(owner, 'spawner', 'ancient'), 'Ancient spawner item delivered')
      }
      await closeWin(owner)
    }
  }
  let aCell = null
  for (let r = 2; r <= 3 && !aCell; r++) {
    for (const [dx, dz] of [[0, -r], [0, r], [r, 0], [-r, 0]]) {
      if (t1cell && Math.abs((HX + dx) - t1cell.x) + Math.abs((HZ + dz) - t1cell.z) < 2) continue
      if (solidAt(owner, HX + dx, GY, HZ + dz) && airAbove(owner, HX + dx, GY, HZ + dz)) {
        aCell = { x: HX + dx, y: GY, z: HZ + dz }
        break
      }
    }
  }
  check(!!aCell, 'found a separate platform cell for the Ancient spawner')
  if (aCell) {
    const item = owner.inventory.items().find((i) => i.name === 'spawner'
      && /ancient/i.test(JSON.stringify(i)))
    if (item) {
      await owner.equip(item, 'hand')
      try {
        await owner.placeBlock(owner.blockAt(v3(aCell.x, aCell.y, aCell.z)), v3(0, 1, 0))
        await sleep(800)
        const b = owner.blockAt(v3(aCell.x, aCell.y + 1, aCell.z))
        check(!!b && b.name === 'spawner', 'Ancient spawner places on the island')
      } catch (e) { bad('Ancient spawner places on the island', e.message) }
    }
    // No RCON teleport: sweep ordinary zombies away each second so the
    // nearest client-side zombie is the named Ancient when the cycle
    // awakens it. The Ancient carries the plugin's vanilla scoreboard
    // tag coremc.ancient (its display name has colour/style components,
    // which a name="..." selector cannot match reliably).
    const ancient = await waitUntil(async () => {
      await rc(`execute in ${DIM} positioned ${HX + 0.5} ${GY + 1} ${HZ + 0.5} run kill @e[type=zombie,tag=!coremc.ancient,distance=..16]`)
      return nearbyMobs(owner, 'zombie', 20, /ancient/)[0] || null
    }, 90000, 1000)
    check(!!ancient, 'Ancient spawner awakens a named Ancient zombie')
    if (ancient) {
      // Fight from the island centre with the sword out: the Ancient is
      // knockback-immune and toughened, and a quick kill keeps the fight
      // on the platform (no void deaths with no player killer).
      const swordA = owner.inventory.items().find((i) => i.name === 'iron_sword')
      if (swordA) await owner.equip(swordA, 'hand')
      await walkTo(owner, HX + 0.5, HZ + 0.5)
      clearChat(owner)
      const dead = await killMob(owner, ancient, 90000)
      check(dead, 'Ancient zombie killed')
      // Match the FULL line in one pattern: waitChat's match[0] is only the
      // regex match substring, so a separate /\+3/ test on it could never
      // see the +3 that follows elsewhere on the line.
      const progress = await waitChat(owner, /ancient kill.*\+3/i, 12000)
      check(!!progress,
        'Ancient kill grants +3 progress in one event', progress ? progress.input : 'none')
    }
  }
  // Recover both spawner blocks with the owner's Omni-Tool pickaxe. Freshly
  // cycled zombies would interrupt the dig, so sweep zombies every second
  // while digging; retry a few times (ancient cycles every ~10s).
  const omniPick = owner.inventory.items().find((i) => i.name === 'netherite_pickaxe')
  if (omniPick) await owner.equip(omniPick, 'hand')
  for (const cell of [t1cell, aCell]) {
    if (!cell) continue
    for (let attempt = 0; attempt < 3; attempt++) {
      await rc(`execute in ${DIM} run kill @e[type=zombie]`)
      let b = owner.blockAt(v3(cell.x, cell.y + 1, cell.z))
      if (!b || b.name === 'air') break
      // Stand on the cell one step toward island centre; target stays in
      // reach (RCON teleports strand the mineflayer client without chunks).
      const sx = cell.x - Math.sign(cell.x - HX)
      const sz = cell.z - Math.sign(cell.z - HZ)
      await walkTo(owner, sx + 0.5, sz + 0.5)
      const ready = await waitBlockReady(owner, cell.x, cell.y + 1, cell.z, true, 10000)
      if (!ready) { log(`[spawner] ${cell.x},${cell.z} not ready on attempt ${attempt}`); continue }
      let sweeper = setInterval(() => {
        rc(`execute in ${DIM} positioned ${HX + 0.5} ${GY + 1} ${HZ + 0.5} run kill @e[type=zombie,distance=..12]`).catch(() => {})
      }, 1000)
      try {
        b = owner.blockAt(v3(cell.x, cell.y + 1, cell.z))
        if (b && b.name === 'spawner') {
          await Promise.race([owner.dig(b), sleep(15000)])
        }
      } catch (e) { log('[spawner] dig attempt ' + attempt + ' failed: ' + e.message) }
      clearInterval(sweeper)
      await sleep(600)
      const after = owner.blockAt(v3(cell.x, cell.y + 1, cell.z))
      if (!after || after.name === 'air') {
        log(`[spawner] recovered ${cell.x},${cell.z}`)
        break
      }
      log(`[spawner] ${cell.x},${cell.z} still present after attempt ${attempt}`)
    }
  }
  await rc(`execute in ${DIM} run kill @e[type=zombie]`)
  await sleep(1000)

  // ------------------------------------------------ P6 enchants + keys
  phase(6, 'omni panel -> 15-enchant grid -> treasure-miner -> mine 2 -> 2 sky keys')
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
  check(ewin && ewin.inventoryStart === 54, 'miner enchant grid is 54 slots')
  const GRID = [11, 12, 13, 14, 15, 20, 21, 22, 23, 24, 29, 30, 31, 32, 33]
  check(ewin && GRID.every((s) => slotType(ewin, s) !== null), 'all 15 miner enchants render')
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
  for (let r = 2; r <= 3 && mined.length < 2; r++) {
    for (const [dx, dz] of [[r, 1], [r, -1], [1, r], [-1, r]]) {
      if (mined.length >= 2) break
      const bx = HX + dx; const bz = HZ + dz
      const blk = solidAt(owner, bx, GY, bz)
      if (!blk || !airAbove(owner, bx, GY, bz)) continue
      if (Math.abs(bx - HX) + Math.abs(bz - HZ) < 2) continue
      // Walk onto the platform cell just inside the target (RCON teleports
      // can strand the client without chunks); the target stays within reach.
      const sx = bx - Math.sign(bx - HX), sz = bz - Math.sign(bz - HZ)
      await walkTo(owner, sx + 0.5, sz + 0.5)
      const ready = await waitBlockReady(owner, bx, GY, bz, true, 10000)
      if (!ready) continue
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
  await walkTo(owner, HX + 0.5, HZ + 0.5)

  // ------------------------------------------------ P7 crates
  phase(7, '/crates: six crates render; two sky opens (2nd pity) + no-key path')
  win = await openWindow(owner, '/crates')
  check(win && win.inventoryStart === 54, '/crates opens 54-slot lineup')
  const CRATE_SLOTS = [19, 20, 21, 23, 24, 25]
  check(win && CRATE_SLOTS.every((s) => slotType(win, s) !== null), 'all six crates render')
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
  const gWorld = await waitUntil(() => guest.__world, 30000)
  check(!!gWorld, 'guest reports its level name', String(guest.__world))
  let victim = null
  for (let r = 2; r <= 3 && !victim; r++) {
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
    await waitUntil(() => guest.__world === WORLD_ISLANDS
      && guest.entity.position.distanceTo(v3(victim.x + 2.5, GY + 1, victim.z + 0.5)) < 6, 30000)
    await waitBlockReady(guest, victim.x, victim.y, victim.z, true, 20000)
    await sleep(1000)
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
    quitBot(guest, GUEST)
    await sleep(1500)
  }

  // ------------------------------------------------ P9 generators
  phase(9, '/gens: four generators render; buy cobble gen, place, harvest')
  win = await openWindow(owner, '/gens')
  check(win && win.inventoryStart === 54, '/gens opens 54-slot market')
  const GEN_SLOTS = [20, 21, 23, 24]
  check(win && GEN_SLOTS.every((s) => slotType(win, s) !== null), 'all four generators render')
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
  for (let r = 1; r <= 3 && !genAt; r++) {
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
  await rc(`execute in ${DIM} run tp ${OWNER} ${HX + 0.5} -80 ${HZ + 0.5}`)
  check(await waitChat(owner, /void rejects/i, 20000), 'void rescue message fires')
  const rescuedOkay = await waitUntil(() => {
    const p = owner.entity.position
    return p.y > 0 && p.distanceTo(HOME) < 3
  }, 20000)
  check(!!rescuedOkay, 'rescue lands back home',
    rescuedOkay ? '' : `dist=${owner.entity.position.distanceTo(HOME).toFixed(2)}`)
  check(owner.health > 0, 'rescue prevents death', `health=${owner.health}`)

  // ------------------------------------------------ P11 restart
  phase(11, 'clean restart: everything persists')
  const snapMoney = await moneyOf(owner)
  const snapCredits = await creditsOf(owner)
  const snapTokens = await tokensOf(owner)
  log(`[snapshot] money=${snapMoney} credits=${snapCredits} tokens=${snapTokens}`)
  quitBot(owner, OWNER)
  await sleep(3000)
  await stopServer()
  await startServer('boot2')
  await connectRcon()
  await baseSetup()
  auditLog('boot2')

  owner = makeBot(OWNER)
  check(await waitSpawn(owner), 'owner rejoins after restart')
  await waitUntil(() => owner.__world !== null, 30000)
  await sleep(1000)
  clearChat(owner)
  owner.chat('/is home')
  const homeBack = await waitUntil(() => {
    const p = owner.entity.position
    return owner.__world === WORLD_ISLANDS && p.y > 0 && p.distanceTo(HOME) < 3
  }, 30000)
  check(!!homeBack, '/is home still works after restart',
    homeBack ? '' : `dist=${owner.entity.position.distanceTo(HOME).toFixed(2)} world=${owner.__world}`)
  win = await openWindow(owner, '/role')
  check(win && slotJson(win, 4).includes('miner'), 'role still Miner after restart')
  await closeWin(win)
  const m2 = await moneyOf(owner)
  const c2 = await creditsOf(owner)
  const t2 = await tokensOf(owner)
  check(m2 === snapMoney && c2 === snapCredits && t2 === snapTokens,
    'all three balances persist exactly', `m ${snapMoney}->${m2}, c ${snapCredits}->${c2}, t ${snapTokens}->${t2}`)
  clearChat(owner)
  owner.chat('/is info')
  check(await waitChat(owner, /JOwner/i, 15000), '/is info shows the owner')

  // data-file asserts (post-stop flush => files are authoritative now)
  // The username index lives INSIDE the profiles directory.
  const users = yamlLoad(fs.readFileSync(path.join(PLUGIN_DIR, 'profiles', 'usernames.yml'), 'utf8'))
  const uuid = users && (users[OWNER.toLowerCase()] || users[OWNER])
  check(!!uuid, 'username index maps the owner', String(uuid))
  if (uuid) {
    const prof = yamlLoad(fs.readFileSync(
      path.join(PLUGIN_DIR, 'profiles', `${uuid}.yml`), 'utf8'))
    const profile = prof.profile || prof
    check(profile.role === 'miner', 'profile: role=miner', String(profile.role))
    const zk = (profile['kill-counts'] && profile['kill-counts'].zombie) || 0
    check(zk === 275, 'profile: zombie kills = 275 (25 wild + admin grant, no spawner farming)',
      `zombie=${zk}`)
    const sk = (profile['kill-counts'] && profile['kill-counts'].skeleton) || 0
    check(sk === 3, 'profile: Ancient zombie kill = 3 skeleton progress (one event)',
      `skeleton=${sk}`)
    const spawnerKills = (profile.stats && profile.stats['spawner-mobs-killed']) || 0
    check(spawnerKills >= 1, 'profile: spawner-mobs-killed stat recorded', String(spawnerKills))
    const pity = (profile.stats && profile.stats['crate-pity:sky']) || 0
    check(pity === 0, 'profile: sky pity counter reset by payout', `pity=${pity}`)
    const enchLvl = (profile['enchant-levels'] && profile['enchant-levels']['miner.treasure-miner']) || 0
    check(enchLvl >= 1, 'profile: dotted treasure-miner id persisted literally', `level=${enchLvl}`)
    check(profile.money === snapMoney && profile.credits === snapCredits
      && profile['sky-tokens'] === snapTokens,
      'profile file balances match live snapshot')
    const isl = yamlLoad(fs.readFileSync(
      path.join(PLUGIN_DIR, 'islands', `${uuid}.yml`), 'utf8'))
    const island = isl.island || isl
    check(island.world === 'islands', 'island file: world=islands', String(island.world))
    check(island.upgrades && island.upgrades.border === 1, 'island file: border tier persists')
    const buffTier = island.buffs && (island.buffs['mining-boost'] || island.buffs.mining_boost)
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
try { if (owner) quitBot(owner, OWNER) } catch { /* noop */ }
try { if (guest) quitBot(guest, GUEST) } catch { /* noop */ }
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
