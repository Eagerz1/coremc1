#!/usr/bin/env node
// CoreMC live player journey (virtual essence edition).
// Boots a Paper server from THIS checkout (plugin jar built from the exact
// tree under test), then drives a full two-player session with mineflayer
// bots + RCON and asserts every headliner flow:
//
//   P1  join + /is create (island pastes in the coremc_islands world)
//   P2  virtual essence commands: zero balances, admin give/take/set,
//       kills pseudo-type, unknown types, permission gates, no negatives
//   P3  placeholders: /papicheck resolves every %coremc_*% and %x_currency%
//       comma-formatted (mock PlaceholderAPI in the sandbox)
//   P4  spawner buy + place + /spawner info + hologram entity
//   P5  upgrade GUI: failed click names the missing requirements
//       (VILLAGER_NO, checklist re-renders), success click consumes
//       money -> essence -> drops but NEVER the kill threshold
//   P6  earning rules: natural mob = Normal rate, advanced spawner mob
//       pays the Advanced rate, passive /kill deaths pay nothing,
//       Mythic auto-kill pays no Slayer, mining pays for natural ores
//       but never for placed blocks, farming pays only for ripe crops
//   P7  clean restart: essences.yml / placed-blocks.yml / spawners-data.yml
//       all survive, and the placed-block guard still holds
//   P8  full-log audit: zero server ERRORs, zero CoreMC warn/error lines
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
const WORLD_ISLANDS = 'coremc_islands'
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
function javaBin() {
  const hint = path.join(ROOT, 'java-path.txt')
  if (fs.existsSync(hint)) {
    const p = fs.readFileSync(hint, 'utf8').trim()
    if (p && fs.existsSync(p)) return p
  }
  return 'java'
}
function bootCount() {
  if (!fs.existsSync(SERVER_LOG)) return 0
  return (fs.readFileSync(SERVER_LOG, 'utf8').match(/Done \([^)]*\)! For help, type "help"/g) || []).length
}
async function startServer(tag, timeoutMs = 14 * 60 * 1000) {
  const seen = bootCount()
  log(`[server] booting (${tag})...`)
  const out = fs.openSync(SERVER_LOG, 'a')
  serverProc = spawn(javaBin(), ['-Xmx3G', '-Xms1G', '-jar', paperJar(), 'nogui'],
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
  return (res || '').replace(/[\u00a7&][0-9a-fk-orx]/gi, '')
}
async function baseSetup() {
  await rc('gamerule doDaylightCycle false')
  await rc('time set day')
  await rc('gamerule doMobSpawning false')
  await rc('gamerule doTraderSpawning false')
  await rc('difficulty normal')
  log('[setup] gamerules applied (natural spawning off — only our summons and spawners)')
}

// -------------------------------------------------------------- log audit
function auditLog(label) {
  const txt = fs.existsSync(SERVER_LOG) ? fs.readFileSync(SERVER_LOG, 'utf8') : ''
  // Platform noise that is not a server problem in this sandbox: egress-
  // blocked update/auth checks, Mojang datafixer boot noise, and Paper's
  // chunk-system "NOT A BUG" wait reports when the island paste syncs
  // chunks (their vanilla stack frames included). Anything plugin-caused
  // still surfaces with a "Could not pass event ... CoreMC" header or
  // com.coremc frames, which the plugin check below catches.
  const NOISE = /yggdrasil|versionfetcher|version information|no key layers|chunktaskscheduler|chunk wait|chunk holder|DO NOT REPORT THIS TO PAPER|java\.base@|net\.minecraft\./i
  const lines = txt.split('\n').filter((l) => !NOISE.test(l))
  const serverErrors = lines.filter((l) => /ERROR\]:/.test(l))
  const pluginProblems = lines.filter(
    (l) => /(WARN|ERROR|Exception|Caused by)/.test(l)
      && (/\[CoreMC\]/.test(l) || /com\.coremc/.test(l) || /to CoreMC v/i.test(l)))
  check(serverErrors.length === 0, `${label}: zero server ERROR lines (excl. platform noise)`,
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
  bot.__world = null
  const readWorld = (packet) => {
    const ws = packet?.worldState ?? packet
    if (ws && typeof ws.name === 'string') bot.__world = ws.name
  }
  bot._client.on('login', readWorld)
  bot._client.on('respawn', readWorld)
  bot.on('messagestr', (m) => { bot.__chat.push(m); if (bot.__chat.length > 800) bot.__chat.shift() })
  bot.on('error', (e) => log(`[${name}] bot error: ${e.message}`))
  bot.on('kicked', (reason) => {
    if (expectedQuit.has(name)) return
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
const recentChat = (bot) => (bot ? bot.__chat.slice(-6).join(' || ') : 'no bot')
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
  try {
    return JSON.stringify(win.slots[slot] ?? null)
      .replace(/[\u00a7][0-9a-fk-orx]/gi, '').toLowerCase()
  } catch { return '' }
}
async function click(bot, slot, button = 0, mode = 0) {
  await bot.clickWindow(slot, button, mode)
  await sleep(700)
}
async function closeWin(bot) {
  try { if (bot.currentWindow) bot.closeWindow(bot.currentWindow) } catch { /* noop */ }
  await sleep(500)
}
const v3 = (x, y, z) => new Vec3(x, y, z)
function solidAt(bot, x, y, z) {
  const b = bot.blockAt(v3(x, y, z))
  return b && b.name !== 'air' && b.name !== 'cave_air' && b.name !== 'void_air' ? b : null
}
function customNameOf(e) {
  try {
    const v = e.metadata && e.metadata[2]
    if (v == null) return ''
    if (typeof v === 'string') return v
    return JSON.stringify(v)
  } catch { return '' }
}
function stackCountOf(e) {
  // stacked mobs carry "Nx <Name>" as their custom name
  const m = String(customNameOf(e) || '').match(/(\d+)x/i)
  return m ? parseInt(m[1], 10) : 1
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
function nearestSolidGround(bot, x, z, radius = 4) {
  let best = null; let bestD = Infinity
  for (let dx = -radius; dx <= radius; dx++) {
    for (let dz = -radius; dz <= radius; dz++) {
      const cx = x + dx; const cz = z + dz
      if (solidAt(bot, cx, GY, cz)) {
        const d = Math.hypot(cx - x, cz - z)
        if (d < bestD) { bestD = d; best = { x: cx, z: cz } }
      }
    }
  }
  return best
}
/** Places one stone on the first free solid support near home. */
async function placeStoneNear(bot, stoneItem) {
  const cands = [[HX - 2, HZ - 1], [HX - 2, HZ + 1], [HX + 1, HZ - 2],
    [HX - 1, HZ + 2], [HX + 3, HZ - 1], [HX - 3, HZ + 1]]
  for (const [cx, cz] of cands) {
    const sup = solidAt(bot, cx, GY, cz)
    if (!sup || solidAt(bot, cx, GY + 1, cz) || solidAt(bot, cx, GY + 2, cz)) continue
    try {
      await bot.equip(stoneItem, 'hand')
      await sleep(250)
      await bot.placeBlock(sup, new Vec3(0, 1, 0))
      return { x: cx, y: GY + 1, z: cz }
    } catch { /* try the next candidate */ }
  }
  return null
}
/** Digs the block at a position, logging the reason when it fails. */
async function digAt(bot, x, y, z) {
  const block = bot.blockAt(v3(x, y, z))
  if (!block) { log(`[dig] ${x},${y},${z}: no block there (chunk gap?)`); return false }
  try {
    await bot.dig(block)
    return true
  } catch (err) {
    log(`[dig] ${x},${y},${z} (${block.name}): ${err && err.message}`)
    return false
  }
}
async function walkTo(bot, x, z, settleMs = 700) {
  // the island is tiny: never walk onto void, retarget to solid ground and
  // stop early if the edge opens up ahead of us
  if (GY && !solidAt(bot, x, GY, z)) {
    const alt = nearestSolidGround(bot, x, z)
    if (alt) { log(`[walk] ${x},${z} is void, retargeting to ${alt.x},${alt.z}`); x = alt.x; z = alt.z }
  }
  for (let i = 0; i < 80; i++) {
    const p = bot.entity.position
    const dx = x - p.x, dz = z - p.z
    if (Math.hypot(dx, dz) < 0.25) break
    if (GY && p.y > GY - 0.5) {
      const ax = Math.floor(p.x + Math.sign(dx) * 0.8)
      const az = Math.floor(p.z + Math.sign(dz) * 0.8)
      if (!solidAt(bot, ax, GY, az)) {
        log(`[walk] void ahead of ${p.x.toFixed(1)},${p.z.toFixed(1)}, stopping`)
        break
      }
    }
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

// ------------------------------------------------------------ essence math
// Reads the bot's own /essence balance line:
//   "COREMC >>> Essence - Slayer: 1,500 | Mining: 425 | Farming: 75 | Kills: 12,345"
async function essenceOf(bot) {
  clearChat(bot)
  bot.chat('/essence')
  const hit = await waitChat(bot,
    /essence - slayer: ([\d,]+) \| mining: ([\d,]+) \| farming: ([\d,]+) \| kills: ([\d,]+)/i, 15000)
  if (!hit) return null
  const num = (s) => parseInt(s.replace(/,/g, ''), 10)
  return { slayer: num(hit[1]), mining: num(hit[2]), farming: num(hit[3]), kills: num(hit[4]) }
}
function checkEssence(got, want, label) {
  if (!got) { bad(label, 'no balance line'); return false }
  const same = got.slayer === want.slayer && got.mining === want.mining
    && got.farming === want.farming && got.kills === want.kills
  return check(same, label,
    `want S${want.slayer}/M${want.mining}/F${want.farming}/K${want.kills}`
    + ` got S${got.slayer}/M${got.mining}/F${got.farming}/K${got.kills}`)
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

  // ------------------------------------------------ P1 join/create island
  phase(1, 'join + /is create')
  owner = makeBot(OWNER)
  check(await waitSpawn(owner), 'owner spawns on first boot')
  clearChat(owner)
  owner.chat('/is create')
  check(await waitChat(owner, /island created/i, 90000), '/is create pastes island')
  const inWorld = await waitUntil(
    () => String(owner.__world || '').endsWith(WORLD_ISLANDS), 60000)
  check(!!inWorld, 'client was sent to the islands level', String(owner.__world))
  const settled = await waitUntil(() => {
    const p = owner.entity.position
    let g = null
    for (let y = Math.floor(p.y); y > Math.floor(p.y) - 12; y--) {
      if (solidAt(owner, Math.floor(p.x), y, Math.floor(p.z))) { g = y; break }
    }
    return g
  }, 60000)
  check(settled !== null, 'island has solid ground under the live spawn position')
  await sleep(2000)
  HOME = owner.entity.position.clone()
  HX = Math.floor(HOME.x); HZ = Math.floor(HOME.z); GY = settled
  log(`[island] home=${HOME.x.toFixed(1)},${HOME.y.toFixed(1)},${HOME.z.toFixed(1)} groundY=${GY}`)

  // ------------------------------------------- P2 essence commands (admin)
  phase(2, 'virtual essence commands')
  // everyone starts at zero
  let e = await essenceOf(owner)
  checkEssence(e, { slayer: 0, mining: 0, farming: 0, kills: 0 }, 'fresh balances are all zero')

  // give (console has coremc.admin.essence)
  let out = await rc('essence give JOwner slayer 1500')
  check(/given 1,500 x slayer essence/i.test(out), 'console give slayer 1500', out)
  await rc('essence give JOwner mining 425')
  await rc('essence give JOwner farming 75')
  await rc('essence set JOwner kills 12345')
  e = await essenceOf(owner)
  checkEssence(e, { slayer: 1500, mining: 425, farming: 75, kills: 12345 },
    'balances after give/set (comma-formatted)')

  // balance by name
  clearChat(owner)
  owner.chat('/essence balance JOwner')
  check(await waitChat(owner, /jowner's essence - slayer: 1,500/i),
    '/essence balance <player> shows another player')

  // take
  out = await rc('essence take JOwner slayer 500')
  check(/taken 500 x slayer essence/i.test(out), 'console take slayer 500', out)
  e = await essenceOf(owner)
  check(e && e.slayer === 1000, 'take subtracts (1,000 left)', String(e && e.slayer))

  // take more than held: refuses, unchanged, never negative
  out = await rc('essence take JOwner slayer 99999')
  check(/only has 1,000 slayer essence/i.test(out), 'overdraw take is refused', out)
  e = await essenceOf(owner)
  check(e && e.slayer === 1000, 'refused take leaves the balance unchanged')

  // set + clamp
  await rc('essence set JOwner slayer 1500')
  await rc('essence set JOwner slayer -99')
  e = await essenceOf(owner)
  check(e && e.slayer === 0, 'negative set clamps to zero', String(e && e.slayer))
  await rc('essence set JOwner slayer 1500')

  // unknown type (console has admin permission, so the type check runs)
  out = await rc('essence give JOwner blood 5')
  check(/unknown essence type/i.test(out), 'unknown essence type is rejected', out)

  // guest permission gate
  guest = makeBot(GUEST)
  check(await waitSpawn(guest), 'guest spawns')
  e = await essenceOf(guest)
  checkEssence(e, { slayer: 0, mining: 0, farming: 0, kills: 0 }, 'guest starts at zero too')
  clearChat(guest)
  guest.chat('/essence give JGuest slayer 100')
  check(await waitChat(guest, /you do not have permission/i),
    'non-admin cannot /essence give')

  // --------------------------------------------- P3 placeholders (/papicheck)
  phase(3, 'placeholderapi placeholders')
  out = await rc('papicheck JOwner')
  // slayer 1,500 | mining 425 | farming 75 | total 2,000 | kills 12,345
  // | coins 500,000 (pre-seeded balances.yml) | x_currency 2,000 (total)
  const wantPapi = 'PAPIRESULT JOwner 1,500|425|75|2,000|12,345|500,000|2,000'
  check(out.includes(wantPapi), '/papicheck resolves every essence placeholder + %x_currency%',
    out.trim())
  out = await rc('papicheck JGuest')
  check(out.includes('PAPIRESULT JGuest 0|0|0|0|0|100|0'), 'guest placeholders are zeroed',
    out.trim())

  // ------------------------------------- P4 spawner buy + place + info + holo
  phase(4, 'spawner buy, place, info, hologram')
  clearChat(owner)
  owner.chat('/spawner buy pig')
  check(await waitChat(owner, /bought a pig spawner for \$2500\.00/i),
    '/spawner buy pig costs $2,500', recentChat(owner))
  check(owner.inventory.items().some((i) => i.name === 'spawner'),
    'the bought spawner is a real spawner item in the inventory')

  // place it a couple of blocks away from the home spot
  const spX = HX + 2; const spZ = HZ + 2
  const refSpot = await waitUntil(async () => {
    // find a solid block with two air blocks above near the target
    for (let dy = 2; dy >= -2; dy--) {
      const base = solidAt(owner, spX, GY + dy, spZ)
      if (base && !solidAt(owner, spX, base.position.y + 1, spZ)
        && !solidAt(owner, spX, base.position.y + 2, spZ)) return base
    }
    return null
  }, 30000)
  check(!!refSpot, 'found a clear spot on the island for the spawner')
  const spawnerAt = refSpot.position.offset(0, 1, 0)
  await walkTo(owner, spX - 1, spZ - 1)
  const spawnerItem = owner.inventory.items().find((i) => i.name === 'spawner')
  await owner.equip(spawnerItem, 'hand')
  await sleep(400)
  let placed = false
  for (let i = 0; i < 10 && !placed; i++) {
    try {
      await owner.placeBlock(refSpot, new Vec3(0, 1, 0))
      placed = true
    } catch (err) { await sleep(600) }
  }
  check(placed, 'bot placed the spawner')
  check(await waitBlockReady(owner, spawnerAt.x, spawnerAt.y, spawnerAt.z, true),
    'the placed block is a real spawner block')
  await sleep(1500)

  // /spawner info while looking at it
  await owner.lookAt(v3(spawnerAt.x + 0.5, spawnerAt.y + 0.5, spawnerAt.z + 0.5))
  await sleep(400)
  clearChat(owner)
  owner.chat('/spawner info')
  check(await waitChat(owner, /pig spawner .{0,3}normal/i), '/spawner info shows [Normal]')
  check(await waitChat(owner, /next: advanced .{0,4}\$5000\.00, 10,000 mob kills, 20 slayer essence, 3x pig tusk/i),
    '/spawner info lists the Advanced requirement list')

  // hologram: a persistent text_display above the cage. `say` output never
  // comes back through RCON, so read the entity data instead — pinned to the
  // exact label position (spawner centre +0.5, +1.4, +0.5) so the schematic's
  // own decorations cannot match, and limit=1 because data get wants one
  out = await rc(`execute in ${DIM} run data get entity @e[type=text_display,x=${spawnerAt.x + 0.3},y=${spawnerAt.y + 1.2},z=${spawnerAt.z + 0.3},dx=0.4,dy=0.5,dz=0.4,limit=1] text`)
  check(/pig spawner/i.test(out), 'stack-label hologram floats above the spawner',
    out.trim().slice(0, 200))

  // ------------------------------------- P5 upgrade GUI: fail then succeed
  phase(5, 'upgrade GUI checklist')
  // starve the owner so three of four requirements fail
  await rc('essence take JOwner slayer 1490')          // 1500 -> 10 (< 20)
  await rc('essence set JOwner kills 5000')            // 5000 (< 10000); no tusks yet
  // money is fine (497,500 >= 5,000)

  await owner.lookAt(v3(spawnerAt.x + 0.5, spawnerAt.y + 0.5, spawnerAt.z + 0.5))
  await sleep(400)
  let win = await openWindow(owner, '/spawner upgrade')
  check(!!win, '"/spawner upgrade" opens the upgrade GUI')
  check(win && JSON.stringify(win.title).toLowerCase().includes('upgrade spawner'),
    'window title is "COREMC - Upgrade Spawner"')
  const buttonJson = win ? slotJson(win, 13) : ''
  // the lore serialises as split colour components ("Upgrading to: " and
  // "PIG SPAWNER " end up in separate text runs), so match the tokens
  // individually instead of one contiguous phrase
  check(buttonJson.includes('upgrading to') && buttonJson.includes('pig spawner')
    && buttonJson.includes('advanced'),
    'slot 13 is the upgrade button naming the target variant', buttonJson.slice(0, 300))
  check(buttonJson.includes('requirements'), 'button lore carries the requirement header')
  check(buttonJson.includes('\\u2714') || buttonJson.includes('✔'), 'a met requirement shows a tick')
  check((buttonJson.match(/\\u2716/g) || buttonJson.match(/✖/g) || []).length === 3,
    'the three shortfalls show crosses')
  check(buttonJson.includes('you have'), 'shortfall lines say (You have N)')
  check(buttonJson.includes('click to upgrade') === false,
    'not clickable-to-upgrade while requirements fail')

  // click with a shortfall: error feedback + named missing list, no upgrade
  clearChat(owner)
  await click(owner, 13)
  check(await waitChat(owner, /you still need: 10,000 mob kills, 20 slayer essence, 3x pig tusk/i),
    'failed click names exactly the missing requirements')
  const stillWin = await waitWindow(owner, 8000)
  check(!!stillWin, 'the checklist re-renders after a failed click')

  // top inventory clicks are dead: clicking own inventory items must not move
  const invBefore = owner.inventory.items().length
  await click(owner, 28) // bottom-inventory hotbar slot
  check(owner.inventory.items().length === invBefore, 'hostile clicks cannot move GUI items')
  await closeWin(owner)

  // seed everything the Advanced tier wants
  await rc('essence give JOwner slayer 30')            // 10 -> 40
  await rc('essence set JOwner kills 10000')           // exactly at threshold
  clearChat(owner)
  await rc('spawner giveitem JOwner drop pig 3')
  check(await waitChat(owner, /given 3 x pig tusk/i),
    'admin giveitem hands out 3 Pig Tusks', recentChat(owner))

  await owner.lookAt(v3(spawnerAt.x + 0.5, spawnerAt.y + 0.5, spawnerAt.z + 0.5))
  await sleep(400)
  win = await openWindow(owner, '/spawner upgrade')
  const readyJson = win ? slotJson(win, 13) : ''
  check((readyJson.match(/\\u2714/g) || readyJson.match(/✔/g) || []).length === 4,
    'all four requirements now tick')
  check(readyJson.includes('click to upgrade'), 'the button invites the click')
  const progressJson = win ? slotJson(win, 15) : ''
  check(progressJson.includes('your progress') && progressJson.includes('mob kills'),
    'slot 15 is the progress book')

  clearChat(owner)
  await click(owner, 13)
  check(await waitChat(owner, /upgraded to advanced! \(x2 spawn rate, 3 per cycle\)/i),
    'successful click upgrades to Advanced')
  await sleep(1200)
  check(!owner.currentWindow, 'the window closed itself after the upgrade')

  // spends: money 497,500 -> 492,500, slayer 40 -> 20, tusks 3 -> 0,
  // kills are a THRESHOLD: 10,000 stays
  e = await essenceOf(owner)
  checkEssence(e, { slayer: 20, mining: 425, farming: 75, kills: 10000 },
    'success consumes slayer but never the kill threshold')
  check(!owner.inventory.items().some((i) => i.name === 'bone'),
    'the three Pig Tusks were consumed')
  out = await rc('papicheck JOwner')
  check(out.includes('|492,500|'), 'coins were spent: %coremc_coins% shows 492,500', out.trim())

  await owner.lookAt(v3(spawnerAt.x + 0.5, spawnerAt.y + 0.5, spawnerAt.z + 0.5))
  await sleep(400)
  clearChat(owner)
  owner.chat('/spawner info')
  check(await waitChat(owner, /pig spawner .{0,3}advanced/i), '/spawner info now says [Advanced]')

  // guest cannot upgrade someone else's spawner (teleport it close first
  // so the look-at ray actually finds the spawner)
  await rc(`execute in ${DIM} run tp JGuest ${spawnerAt.x + 0.5} ${spawnerAt.y + 1.0} ${spawnerAt.z + 0.5}`)
  await sleep(1500)
  await guest.lookAt(v3(spawnerAt.x + 0.5, spawnerAt.y - 0.5, spawnerAt.z + 0.5))
  await sleep(500)
  clearChat(guest)
  guest.chat('/spawner upgrade')
  check(await waitChat(guest, /not on your island/i), 'a guest cannot open the upgrade flow',
    recentChat(guest))
  await rc(`execute in ${DIM} run tp JGuest ${HOME.x + 1.5} ${HOME.y + 1} ${HOME.z}`)

  // ------------------------------------------------- P6 earning rules
  phase(6, 'earning rules (slayer/mining/farming)')
  // 6a. natural (summoned) mob counts as Normal: 1 slayer, 1 kill.
  // A cow: no cow spawner exists nearby, so it can carry no variant tag
  // and cannot stack into a spawner-born pig group.
  const natY = GY + 1
  // NoAI: the island is a few blocks wide — a wandering cow walks into the
  // void and its death pays nothing, so pin it in place instead
  await rc(`execute in ${DIM} run summon cow ${HX - 2}.5 ${natY}.0 ${HZ - 1}.5 {NoAI:1b}`)
  let cow = await waitForMob(owner, 'cow', 20000)
  check(!!cow, 'a summoned natural cow appeared')
  if (cow) {
    const killed = await killMob(owner, cow, 60000)
    check(killed, 'bot killed the natural cow')
  }
  await sleep(1500)
  e = await essenceOf(owner)
  checkEssence(e, { slayer: 21, mining: 425, farming: 75, kills: 10001 },
    'natural mob kill pays Normal rate: +1 slayer, +1 kill')
  const NAT = { slayer: 21, kills: 10001 }

  // 6b. passive death pays nothing (console /kill, no damager). Summoned
  // at the cow's proven-solid spot with NoAI so it cannot wander off.
  await rc(`execute in ${DIM} run summon zombie ${HX - 2}.5 ${natY}.0 ${HZ - 1}.5 {NoAI:1b}`)
  await sleep(1000)
  out = await rc(`execute in ${DIM} run kill @e[type=zombie,limit=1]`)
  await sleep(1500)
  e = await essenceOf(owner)
  checkEssence(e, { slayer: 21, mining: 425, farming: 75, kills: 10001 },
    'passive /kill death pays no slayer and no kills')

  // 6c. advanced spawner-born pig pays the Advanced rate (2 per mob)
  // clear any pigs spawned while the spawner was still Normal (several
  // passes: killing a stack leaves a count-1 replacement behind)
  await walkTo(owner, spX - 1, spZ - 1)
  for (let i = 0; i < 4; i++) {
    await rc(`execute in ${DIM} run kill @e[type=pig]`)
    await sleep(2000)
  }
  let spawned = null
  for (let i = 0; i < 36 && !spawned; i++) {
    spawned = await waitForMob(owner, 'pig', 5000)
    if (!spawned) await sleep(1000)
  }
  check(!!spawned, 'the Advanced spawner spawned a pig (faster variant cycle)')
  if (spawned) {
    // A tracked pig can vanish WITHOUT dying — merged into a nearby stack
    // moments after the spawn packet, or spawned over the void and fallen
    // away — which looks like a kill but pays nothing. Retry, chasing only
    // grounded pigs, until a kill actually moves the balance.
    const e0 = await essenceOf(owner)
    const S0 = e0 ? e0.slayer : 21
    const K0 = e0 ? e0.kills : 10001
    let credited = false
    let n = 1
    for (let attempt = 0; attempt < 10 && !credited; attempt++) {
      const pig = await waitUntil(() => {
        const list = nearbyMobs(owner, 'pig', 14)
          .filter((p) => p.position && p.position.y > GY - 0.5)
        return list.length ? list[0] : null
      }, 20000, 300)
      if (!pig) continue
      n = stackCountOf(pig)
      const killed = await killMob(owner, pig, 60000)
      await sleep(1500)
      e = await essenceOf(owner)
      if (killed && e && (e.slayer > S0 || e.kills > K0)) {
        credited = true
      } else {
        log(`[essence] advanced kill attempt ${attempt + 1} did not register, retrying`)
      }
    }
    // invariant: every advanced pig pays exactly 2 slayer per kill credit
    check(credited && e && e.slayer - S0 === 2 * (e.kills - K0) && e.kills > K0,
      `advanced spawner pigs pay exactly 2 slayer per kill (stack of ${n})`,
      `got S${e && e.slayer}/K${e && e.kills} (base S${S0}/K${K0})`)
    log(`[essence] advanced stack count was ${n}`)
  }

  // 6d. mythic auto-kill: drops credited, but zero slayer / zero kills
  clearChat(owner)
  await rc('spawner give JOwner pig mythic')
  check(await waitChat(owner, /pig spawner \[mythic\]/i),
    'admin gives a Mythic pig spawner', recentChat(owner))
  // candidates all sit within ~3 blocks of home — the island is small and
  // anything farther risks standing (and placing) over the void
  const mythCands = [[HX - 2, HZ + 2], [HX - 2, HZ - 2], [HX - 3, HZ], [HX - 1, HZ + 3]]
  let mythX = 0; let mythZ = 0; let mythRef = null
  for (const [cx, cz] of mythCands) {
    const r = await waitUntil(async () => {
      for (let dy = 2; dy >= -2; dy--) {
        const base = solidAt(owner, cx, GY + dy, cz)
        if (base && !solidAt(owner, cx, base.position.y + 1, cz)
          && !solidAt(owner, cx, base.position.y + 2, cz)) return base
      }
      return null
    }, 4000)
    if (r) { mythRef = r; mythX = cx; mythZ = cz; break }
  }
  const mythOk = !!mythRef
  if (!mythOk) bad('found a clear spot for the mythic spawner')
  const mythAt = mythRef ? mythRef.position.offset(0, 1, 0) : null
  if (mythOk) {
    await walkTo(owner, mythX + 1, mythZ - 1)
    const mythItem = owner.inventory.items().find((i) => i.name === 'spawner')
    await owner.equip(mythItem, 'hand')
    await sleep(400)
    for (let i = 0; i < 10; i++) {
      try { await owner.placeBlock(mythRef, new Vec3(0, 1, 0)); break } catch { await sleep(600) }
    }
  }
  check(mythOk && await waitBlockReady(owner, mythAt.x, mythAt.y, mythAt.z, true),
    'mythic spawner placed')
  await sleep(2000)

  // break the ADVANCED spawner so its pigs stop mixing into the counts
  // (sneak-break takes the whole 1x stack back as an item)
  // the pickaxe is handed out here on purpose: fists would need ~25s on a
  // spawner block and the dig window matters
  await rc('give JOwner iron_pickaxe 1')
  await sleep(800)
  const beforeSpawners = owner.inventory.items().filter((i) => i.name === 'spawner').length
  const pickBreak = owner.inventory.items().find((i) => i.name === 'iron_pickaxe')
  if (pickBreak) await owner.equip(pickBreak, 'hand')
  await sleep(300)
  owner.setControlState('sneak', true) // sneak-break takes the whole stack
  const brokeSpawner = await digAt(owner, spawnerAt.x, spawnerAt.y, spawnerAt.z)
  owner.setControlState('sneak', false)
  await sleep(1000)
  // the recovered spawner is handed straight to the breaker's inventory
  // (a natural drop could bounce off the island edge into the void) — the
  // sweep below is just a safety net in case a version drops it naturally
  let recovered = false
  const vacuumSpots = [
    [spawnerAt.x + 0.5, spawnerAt.z + 0.5],
    [spawnerAt.x - 0.5, spawnerAt.z + 0.5],
    [spawnerAt.x + 1.5, spawnerAt.z + 0.5],
    [spawnerAt.x + 0.5, spawnerAt.z - 0.5],
    [spawnerAt.x + 0.5, spawnerAt.z + 1.5],
  ]
  for (const [vx, vz] of (brokeSpawner ? vacuumSpots : [])) {
    await walkTo(owner, vx, vz)
    await sleep(1200)
    if (owner.inventory.items().filter((i) => i.name === 'spawner').length > beforeSpawners) {
      recovered = true
      break
    }
  }
  check(brokeSpawner && recovered,
    'sneak-break recovers the advanced spawner as an item',
    `broke=${brokeSpawner} spawners=${owner.inventory.items().filter((i) => i.name === 'spawner').length}`)

  // stand near the mythic spawner and let it auto-kill a few cycles.
  // first sweep any leftover pigs (passive /kill pays 0, so it cannot skew
  // the slayer/kills comparison and nothing tramples the later farm test)
  await rc(`execute in ${DIM} run kill @e[type=pig]`)
  await sleep(1000)
  if (mythOk) {
    const eBefore = await essenceOf(owner)
    await walkTo(owner, mythX + 1, mythZ - 1)
    await sleep(25000) // several spawn + auto-kill cycles (delay/4, 60t kill timer)
    e = await essenceOf(owner)
    check(e && e.slayer === eBefore.slayer && e.kills === eBefore.kills,
      'mythic auto-kill pays ZERO slayer and ZERO kills (drops only)',
      `before S${eBefore && eBefore.slayer}/K${eBefore && eBefore.kills}`
      + ` after S${e && e.slayer}/K${e && e.kills}`)
  } else {
    log('[skip] mythic auto-kill window skipped (no placement spot)')
  }

  // 6e. mining: natural ore pays, placed block never pays
  await rc('give JOwner iron_pickaxe 1')
  const inv = owner.inventory
  const pick = inv.items().find((i) => i.name === 'iron_pickaxe')
  if (pick) await owner.equip(pick, 'hand')
  const mineX = HX + 3; const mineZ = HZ - 2
  await walkTo(owner, mineX - 1, mineZ + 1)
  const oreY = GY + 1
  await rc(`execute in ${DIM} run setblock ${mineX} ${oreY} ${mineZ} minecraft:diamond_ore`)
  await waitBlockReady(owner, mineX, oreY, mineZ, true)
  const oreDug = await digAt(owner, mineX, oreY, mineZ)
  await sleep(1500)
  e = await essenceOf(owner)
  check(oreDug && e && e.mining === 433, 'mining natural diamond ore pays +8 Mining Essence',
    `dug=${oreDug} mining=${e && e.mining}`)

  // player-PLACED mining-eligible block: tracked, breaking pays nothing
  await rc('give JOwner stone 2')
  await sleep(800)
  const stoneItem = owner.inventory.items().find((i) => i.name === 'stone')
  check(!!stoneItem, 'bot holds stone to place')
  if (stoneItem) {
    await owner.equip(stoneItem, 'hand')
    await sleep(300)
    // scan for any solid support near home (never assume the far end of
    // the island is there — it may be void)
    const stonePos = await placeStoneNear(owner, stoneItem)
    check(!!stonePos, 'bot placed a stone block (player-placed)')
    if (stonePos) {
      await waitBlockReady(owner, stonePos.x, stonePos.y, stonePos.z, true)
      await sleep(500)
      const mBefore = (await essenceOf(owner)).mining
      await owner.equip(pick, 'hand')
      await digAt(owner, stonePos.x, stonePos.y, stonePos.z)
      await sleep(1500)
      e = await essenceOf(owner)
      check(e && e.mining === mBefore,
        'breaking your own placed stone pays NOTHING (anti place-mine loop)',
        `${mBefore} -> ${e && e.mining}`)
      STONE_SPOT = { x: stonePos.x, y: stonePos.y, z: stonePos.z }
    }
  }

  // 6f. farming: ripe crop pays, unripe does not
  const farmX = HX + 3; const farmZ = HZ - 2 // reuses the mined-out column
  await rc(`execute in ${DIM} run setblock ${farmX} ${GY + 1} ${farmZ} minecraft:farmland`)
  await rc(`execute in ${DIM} run setblock ${farmX} ${GY + 2} ${farmZ} minecraft:wheat[age=7]`)
  await waitBlockReady(owner, farmX, GY + 2, farmZ, true)
  await digAt(owner, farmX, GY + 2, farmZ)
  await sleep(1500)
  e = await essenceOf(owner)
  check(e && e.farming === 77, 'harvesting ripe wheat pays +2 Farming Essence', String(e && e.farming))

  await rc(`execute in ${DIM} run setblock ${farmX} ${GY + 2} ${farmZ} minecraft:wheat[age=2]`)
  await waitBlockReady(owner, farmX, GY + 2, farmZ, true)
  await digAt(owner, farmX, GY + 2, farmZ)
  await sleep(1500)
  e = await essenceOf(owner)
  check(e && e.farming === 77, 'unripe wheat pays nothing', String(e && e.farming))

  // ------------------------------------------- P7 restart persistence
  phase(7, 'restart persistence')
  const eFinal = await essenceOf(owner)
  await quitBot(owner, OWNER)
  await quitBot(guest, GUEST)
  await sleep(2000)
  await stopServer()

  // data-file asserts (the stores are the source of truth)
  const essYaml = yamlLoad(fs.readFileSync(path.join(PLUGIN_DIR, 'essence-balances.yml'), 'utf8'))
  const ownerUuid = Object.keys(essYaml || {}).find((k) => essYaml[k].slayer === eFinal.slayer)
  check(!!essYaml && !!ownerUuid, 'essence-balances.yml persisted the owner profile',
    JSON.stringify(essYaml).slice(0, 200))
  if (essYaml && ownerUuid) {
    check(essYaml[ownerUuid].slayer === eFinal.slayer
      && essYaml[ownerUuid].mining === eFinal.mining
      && essYaml[ownerUuid].farming === eFinal.farming
      && essYaml[ownerUuid].kills === eFinal.kills,
      'essence-balances.yml numbers match the live balance')
  }
  const placedYaml = yamlLoad(
    fs.readFileSync(path.join(PLUGIN_DIR, 'placed-blocks.yml'), 'utf8'))
  check(!!placedYaml && Array.isArray(placedYaml.placed) && placedYaml.placed.length === 0,
    'placed-blocks.yml has no stale entries (the placed stone was consumed)',
    JSON.stringify(placedYaml).slice(0, 200))

  await startServer('boot2')
  await connectRcon()
  auditLog('boot2')

  owner = makeBot(OWNER)
  check(await waitSpawn(owner), 'owner spawns after restart')
  await waitUntil(() => String(owner.__world || '').endsWith(WORLD_ISLANDS), 60000)
  await sleep(2500)
  const eAfter = await essenceOf(owner)
  checkEssence(eAfter, eFinal, 'balances survived the restart exactly')

  out = await rc('papicheck JOwner')
  const wantLine = `PAPIRESULT JOwner ${eFinal.slayer.toLocaleString('en-US')}|${eFinal.mining}|${eFinal.farming}|${(eFinal.slayer + eFinal.mining + eFinal.farming).toLocaleString('en-US')}|${eFinal.kills.toLocaleString('en-US')}|492,500|${(eFinal.slayer + eFinal.mining + eFinal.farming).toLocaleString('en-US')}`
  check(out.includes(wantLine), 'placeholders resolve the persisted state', out.trim())

  // the mythic spawner is still mythic with its hologram
  if (mythOk) {
    await walkTo(owner, mythX + 1, mythZ - 1)
    await owner.lookAt(v3(mythAt.x + 0.5, mythAt.y + 0.5, mythAt.z + 0.5))
    await sleep(400)
    clearChat(owner)
    owner.chat('/spawner info')
    check(await waitChat(owner, /pig spawner .{0,3}mythic/i), 'mythic spawner survived as [Mythic]')
    check(await waitChat(owner, /auto-kill/i), 'mythic info mentions auto-kill')
  } else {
    log('[skip] post-restart mythic check skipped (no placement spot)')
  }

  // placed-block guard persists: place + break a stone again, still no pay
  await rc('give JOwner stone 1')
  await sleep(800)
  const stoneItem2 = owner.inventory.items().find((i) => i.name === 'stone')
  if (stoneItem2) {
    const spot = await placeStoneNear(owner, stoneItem2)
    check(!!spot, 'bot placed a fresh stone after the restart')
    if (spot) {
      await waitBlockReady(owner, spot.x, spot.y, spot.z, true)
      await sleep(500)
    const mBefore = (await essenceOf(owner)).mining
    const pick2 = owner.inventory.items().find((i) => i.name === 'iron_pickaxe')
    if (pick2) await owner.equip(pick2, 'hand')
    await digAt(owner, spot.x, spot.y, spot.z)
    await sleep(1500)
    e = await essenceOf(owner)
      check(e && e.mining === mBefore, 'post-restart placed stone still pays nothing',
        `${mBefore} -> ${e && e.mining}`)
    }
  }

  // ------------------------------------------------ P8 final audit
  phase(8, 'final audit')
  await quitBot(owner, OWNER)
  await sleep(1500)
  await stopServer()
  auditLog('final')

  log('')
  log(`========================================`)
  log(`JOURNEY RESULT: ${pass} passed, ${fail} failed`)
  if (fail) {
    log('failures:')
    for (const f of failures) log('  - ' + f)
  }
  log(`========================================`)
  process.exit(fail ? 1 : 0)
}

let STONE_SPOT = null

main().catch((e) => {
  log('JOURNEY CRASHED: ' + (e && e.stack || e))
  process.exit(1)
})
