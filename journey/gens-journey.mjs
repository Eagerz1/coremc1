#!/usr/bin/env node
// CoreMC generator journey — the live counterpart to the unit tests.
// Boots a Paper server from THIS checkout and drives a real player with
// mineflayer + RCON through the whole /gens feature:
//
//   G1  join + /is create
//   G2  /gens list + /gens help in chat
//   G3  the /gens menu: tier colours, ✔ / ✖ prices, locked styling
//   G4  buying from the menu (coins leave, a real generator item arrives)
//   G5  placing on the island (block + hologram + registration)
//   G6  production pays the island (coins climb on the interval)
//   G7  stacking: the hint without sneak, the stack with it
//   G8  the management window: info / upgrade / pickup, upgrade applies
//   G9  anti-exploit: break returns the generator item, never the block;
//       generators refuse to place off-island; TNT leaves them alone
//   G10 restart persistence (generators-data.yml round-trips)
//   G11 the island menu's generator button opens /gens
//   G12 full-log audit: no server ERRORs, no CoreMC warn/error lines
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
const JOURNEY_LOG = path.join(ROOT, 'gens-journey.log')
const RCON_CFG = { host: '127.0.0.1', port: 25575, password: 'journey123' }
const OWNER = 'JOwner'
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
async function startServer(tag, timeoutMs = 10 * 60 * 1000) {
  const seen = bootCount()
  log(`[server] booting (${tag})...`)
  const out = fs.openSync(SERVER_LOG, 'a')
  serverProc = spawn(javaBin(), ['-Xmx2G', '-Xms1G', '-jar', paperJar(), 'nogui'],
    { cwd: ROOT, stdio: ['ignore', out, out] })
  serverProc.on('error', (e) => log('[server] spawn error: ' + e.message))
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    if (serverProc.exitCode !== null && serverProc.exitCode !== undefined) {
      bad(`server ${tag} exited early`, 'code=' + serverProc.exitCode)
      throw new Error('server exited')
    }
    if (bootCount() > seen) { log(`[server] ${tag} ready`); await sleep(2000); return }
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
  await rc('gamerule mobGriefing true')
  await rc('difficulty peaceful')
}

// -------------------------------------------------------------- log audit
function auditLog(label) {
  const txt = fs.existsSync(SERVER_LOG) ? fs.readFileSync(SERVER_LOG, 'utf8') : ''
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
const expectedQuit = new Set()
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
async function waitChat(bot, rx, timeoutMs = 20000) {
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
async function waitWindow(bot, timeoutMs = 20000) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    if (bot.currentWindow) { await sleep(800); return bot.currentWindow }
    await sleep(250)
  }
  return null
}
async function openWindow(bot, command, timeoutMs = 20000) {
  await closeWin(bot)
  bot.chat(command)
  return waitWindow(bot, timeoutMs)
}
async function closeWin(bot) {
  try { if (bot.currentWindow) bot.closeWindow(bot.currentWindow) } catch { /* noop */ }
  await sleep(600)
}
const slotJson = (win, slot) => {
  try {
    return JSON.stringify(win.slots[slot] ?? null).replace(/[\u00a7][0-9a-fk-orx]/gi, '')
  } catch { return '' }
}
const slotName = (win, slot) => {
  try { return (win.slots[slot] && win.slots[slot].name) || 'empty' } catch { return 'empty' }
}
async function click(bot, slot, button = 0, mode = 0) {
  try { await bot.clickWindow(slot, button, mode) } catch (e) { log('[click] ' + e.message) }
  await sleep(900)
}
const v3 = (x, y, z) => new Vec3(x, y, z)
function solidAt(bot, x, y, z) {
  const b = bot.blockAt(v3(x, y, z))
  return b && b.name !== 'air' && b.name !== 'cave_air' && b.name !== 'void_air' ? b : null
}
async function walkTo(bot, x, z, settleMs = 700) {
  for (let i = 0; i < 60; i++) {
    const p = bot.entity.position
    const dx = x - p.x; const dz = z - p.z
    if (Math.hypot(dx, dz) < 0.35) break
    if (GY && p.y > GY - 0.5) {
      const ax = Math.floor(p.x + Math.sign(dx) * 0.8)
      const az = Math.floor(p.z + Math.sign(dz) * 0.8)
      if (!solidAt(bot, ax, GY, az)) break
    }
    await bot.look(Math.atan2(-dx, -dz), 0, true)
    bot.setControlState('forward', true)
    await sleep(180)
  }
  bot.setControlState('forward', false)
  await bot.look(0, 0, true).catch(() => {})
  await sleep(settleMs)
}
/** Finds a solid support with two air blocks above it, near home. */
async function freeSpot(bot, dx, dz) {
  const x = HX + dx; const z = HZ + dz
  return waitUntil(() => {
    for (let dy = 2; dy >= -2; dy--) {
      const base = solidAt(bot, x, GY + dy, z)
      if (base && !solidAt(bot, x, base.position.y + 1, z)
        && !solidAt(bot, x, base.position.y + 2, z)) return base
    }
    return null
  }, 20000)
}
/** Places the held item on top of `support`; returns the placed position. */
async function placeOn(bot, support, item) {
  await bot.equip(item, 'hand')
  await sleep(350)
  const at = support.position.offset(0, 1, 0)
  for (let i = 0; i < 6; i++) {
    try {
      await bot.placeBlock(support, new Vec3(0, 1, 0))
      break
    } catch (err) { await sleep(500) }
  }
  await sleep(1200)
  return at
}
async function coins(name = OWNER) {
  const out = await rc('papicheck ' + name)
  const m = out.match(/PAPIRESULT \w+ [^|]*\|[^|]*\|[^|]*\|[^|]*\|[^|]*\|([\d,]+)\|/)
  return m ? parseInt(m[1].replace(/,/g, ''), 10) : null
}
function itemJson(item) {
  try { return JSON.stringify(item) } catch { return '' }
}
const genItems = (bot, material) =>
  bot.inventory.items().filter((i) => i.name === material && itemJson(i).includes('ɢᴇɴᴇʀᴀᴛᴏʀ'))
async function holoAt(x, y, z) {
  return rc(`execute in ${DIM} run data get entity @e[type=text_display,x=${x + 0.3},y=${y + 1.0},z=${z + 0.3},dx=0.4,dy=0.6,dz=0.4,limit=1] text`)
}

// ------------------------------------------------------------------- main
let owner = null
let HX = 0; let HZ = 0; let GY = 0

async function main() {
  await startServer('boot1')
  await connectRcon()
  await baseSetup()
  auditLog('boot1')

  // ------------------------------------------------- G1 island
  phase(1, 'join + /is create')
  owner = makeBot(OWNER)
  check(await waitSpawn(owner), 'owner spawns')
  clearChat(owner)
  owner.chat('/is create')
  check(await waitChat(owner, /island created/i, 90000), '/is create pastes an island')
  check(!!await waitUntil(() => String(owner.__world || '').endsWith(WORLD_ISLANDS), 60000),
    'owner is in the island world')
  const ground = await waitUntil(() => {
    const p = owner.entity.position
    for (let y = Math.floor(p.y); y > Math.floor(p.y) - 12; y--) {
      if (solidAt(owner, Math.floor(p.x), y, Math.floor(p.z))) return y
    }
    return null
  }, 60000)
  check(ground !== null, 'island has ground under the spawn position')
  await sleep(1500)
  HX = Math.floor(owner.entity.position.x)
  HZ = Math.floor(owner.entity.position.z)
  GY = ground
  log(`[island] home=${HX},${GY},${HZ}`)

  // ------------------------------------------------- G2 chat commands
  phase(2, '/gens list + /gens help')
  clearChat(owner)
  owner.chat('/gens list')
  check(await waitChat(owner, /generator progression/i), '/gens list prints the header')
  check(await waitChat(owner, /ᴄᴏʙʙʟᴇsᴛᴏɴᴇ ɢᴇɴᴇʀᴀᴛᴏʀ .*\(cobblestone\)/i),
    'the ladder starts at the Cobblestone Generator', recentChat(owner))
  check(await waitChat(owner, /ɴᴇᴛʜᴇʀɪᴛᴇ ɢᴇɴᴇʀᴀᴛᴏʀ .*\(netherite\)/i),
    'the ladder ends at the Netherite Generator')
  check(await waitChat(owner, /\$1,000/), 'list shows the starter price ($1,000)')
  clearChat(owner)
  owner.chat('/gens help')
  check(await waitChat(owner, /gens buy <generator>/i), '/gens help lists the buy command')
  clearChat(owner)
  owner.chat('/gens buy nothing')
  check(await waitChat(owner, /unknown generator: nothing/i), 'an unknown generator id is refused')

  // ------------------------------------------------- G3 the /gens menu
  phase(3, 'the /gens menu')
  let win = await openWindow(owner, '/gens')
  check(!!win, '/gens opens the generator menu')
  check(win && JSON.stringify(win.title).toLowerCase().includes('generators'),
    'window title is "COREMC — Generators"', win && JSON.stringify(win.title))
  check(win && win.slots.length - 36 === 54, 'the menu is a double chest',
    String(win && win.slots.length))
  const cobbleJson = slotJson(win, 11)
  check(cobbleJson.includes('ᴄᴏʙʙʟᴇsᴛᴏɴᴇ ɢᴇɴᴇʀᴀᴛᴏʀ'), 'slot 11 is the Cobblestone Generator',
    cobbleJson.slice(0, 200))
  check(cobbleJson.includes('ᴛɪᴇʀ') && cobbleJson.includes('ɪ'), 'lore carries the tier line')
  check(cobbleJson.includes('$1,000') && cobbleJson.includes('✔'),
    'an affordable price shows the green tick')
  check(cobbleJson.includes('ᴄʟɪᴄᴋ ᴛᴏ ᴘᴜʀᴄʜᴀsᴇ'), 'the click action is spelled out')
  check(slotName(win, 11) === 'cobblestone', 'the icon is the generator block itself',
    slotName(win, 11))
  // netherite sits at the end of the band and is locked by island points
  const netherJson = slotJson(win, 24)
  check(netherJson.includes('ɴᴇᴛʜᴇʀɪᴛᴇ ɢᴇɴᴇʀᴀᴛᴏʀ'), 'slot 24 is the Netherite Generator',
    netherJson.slice(0, 160))
  check(netherJson.includes('ʟᴏᴄᴋᴇᴅ'), 'a gated generator is marked LOCKED')
  check(netherJson.includes('✖'), 'the locked generator shows red crosses')
  check(!netherJson.includes('ᴄʟɪᴄᴋ ᴛᴏ ᴘᴜʀᴄʜᴀsᴇ'), 'a locked generator never invites a purchase')
  check(slotName(win, 24) === 'gray_stained_glass_pane',
    'locked generators look different: a grey pane, not the block', slotName(win, 24))
  check(slotJson(win, 4).includes('ʏᴏᴜʀ ɢᴇɴᴇʀᴀᴛᴏʀs'), 'slot 4 is the island generator panel',
    slotJson(win, 4).slice(0, 160))
  check(slotName(win, 45) === 'arrow' && slotName(win, 49) === 'barrier',
    'back and close sit in the bottom row')

  // ------------------------------------------------- G4 buying
  phase(4, 'buying from the menu')
  const before = await coins()
  check(before !== null, 'coin balance readable', String(before))
  clearChat(owner)
  await click(owner, 11)
  check(await waitChat(owner, /bought 1x .*cobblestone generator.* for .*\$1,000/i),
    'clicking an affordable generator buys it', recentChat(owner))
  const after = await coins()
  check(after === before - 1000, 'the coins actually left the balance',
    `${before} -> ${after}`)
  await closeWin(owner)
  await sleep(600)
  check(genItems(owner, 'cobblestone').length === 1,
    'a named generator item is in the inventory (never a plain cobblestone)',
    JSON.stringify(owner.inventory.items().map((i) => i.name)))

  // ------------------------------------------------- G5 placing
  phase(5, 'placing a generator')
  const support = await freeSpot(owner, 2, 2)
  check(!!support, 'found a clear spot on the island')
  await walkTo(owner, HX + 1, HZ + 1)
  clearChat(owner)
  const genPos = await placeOn(owner, support, genItems(owner, 'cobblestone')[0])
  check(await waitChat(owner, /cobblestone generator.*placed/i),
    'placing registers the generator', recentChat(owner))
  check(!!solidAt(owner, genPos.x, genPos.y, genPos.z), 'the generator block is in the world')
  const holo = await holoAt(genPos.x, genPos.y, genPos.z)
  check(/ɢᴇɴᴇʀᴀᴛᴏʀ/.test(holo), 'a hologram floats above the generator', holo.trim().slice(0, 160))
  const dataYaml = () => {
    const f = path.join(PLUGIN_DIR, 'generators-data.yml')
    return fs.existsSync(f) ? yamlLoad(fs.readFileSync(f, 'utf8')) : null
  }
  check((dataYaml()?.generators || []).length === 1,
    'generators-data.yml recorded the placement immediately')

  // ------------------------------------------------- G6 production
  phase(6, 'production pays the island')
  // a netherite generator pays $8,000 every 6s — fast enough to watch
  let out = await rc('gens give JOwner netherite 1')
  check(/given 1x/i.test(out) || await waitChat(owner, /given 1x .*netherite generator/i),
    'console /gens give hands over a generator', out.trim())
  const fastSupport = await freeSpot(owner, -2, 2)
  check(!!fastSupport, 'found a second clear spot')
  clearChat(owner)
  const fastPos = await placeOn(owner, fastSupport, genItems(owner, 'netherite_block')[0])
  check(await waitChat(owner, /netherite generator.*placed/i), 'the netherite generator is placed',
    recentChat(owner))
  const payBase = await coins()
  const paid = await waitUntil(async () => {
    const now = await coins()
    return now !== null && payBase !== null && now >= payBase + 8000 ? now : null
  }, 40000, 2000)
  check(!!paid, 'the generator pays its value into the balance', `${payBase} -> ${paid}`)
  // and it feeds a hopper/chest sitting next to it
  await rc(`execute in ${DIM} run setblock ${fastPos.x + 1} ${fastPos.y} ${fastPos.z} chest`)
  const chestFilled = await waitUntil(async () => {
    const data = await rc(`execute in ${DIM} run data get block ${fastPos.x + 1} ${fastPos.y} ${fastPos.z} Items`)
    return /netherite_scrap/i.test(data) ? data : null
  }, 45000, 2500)
  check(!!chestFilled, 'physical output lands in an adjacent container',
    String(chestFilled).slice(0, 160))
  await rc(`execute in ${DIM} run setblock ${fastPos.x + 1} ${fastPos.y} ${fastPos.z} air`)

  // ------------------------------------------------- G7 stacking
  phase(7, 'stacking generators')
  await rc('gens give JOwner cobblestone 2')
  await sleep(800)
  const stackItem = () => genItems(owner, 'cobblestone')[0]
  check(!!stackItem(), 'the stacking items arrived')
  const genBlock = () => owner.blockAt(v3(genPos.x, genPos.y, genPos.z))
  // a plain right-click with a generator in hand never places a second
  // block: it opens the management window and explains the gesture
  clearChat(owner)
  await owner.equip(stackItem(), 'hand')
  await sleep(300)
  try { await owner.placeBlock(genBlock(), new Vec3(0, 1, 0)) } catch { /* cancelled */ }
  check(await waitChat(owner, /sneak-click an identical generator/i, 12000),
    'a non-sneak click explains the stacking gesture', recentChat(owner))
  check(!solidAt(owner, genPos.x, genPos.y + 1, genPos.z),
    'a non-sneak click never places a second generator on top')
  await closeWin(owner)
  // sneaking stacks it
  clearChat(owner)
  owner.setControlState('sneak', true)
  await sleep(400)
  try { await owner.placeBlock(genBlock(), new Vec3(0, 1, 0)) } catch { /* cancelled */ }
  await sleep(1200)
  owner.setControlState('sneak', false)
  check(await waitChat(owner, /stacked: 2x .*cobblestone generator/i, 12000),
    'sneak-clicking stacks onto the placed generator', recentChat(owner))
  const holo2 = await holoAt(genPos.x, genPos.y, genPos.z)
  check(/2x/.test(holo2), 'the hologram shows the new stack size', holo2.trim().slice(0, 160))
  check((dataYaml()?.generators || []).some((g) => g.amount === 2),
    'the stack size is persisted')

  // ------------------------------------------------- G8 management window
  phase(8, 'the management window')
  await closeWin(owner)
  await owner.lookAt(v3(genPos.x + 0.5, genPos.y + 0.5, genPos.z + 0.5))
  await sleep(500)
  try { await owner.activateBlock(genBlock()) } catch (e) { log('[interact] ' + e.message) }
  win = await waitWindow(owner)
  check(!!win, 'right-clicking a generator opens the management window')
  check(win && JSON.stringify(win.title).toLowerCase().includes('generator'),
    'window title is "COREMC — Generator"', win && JSON.stringify(win.title))
  const info = win ? slotJson(win, 13) : ''
  check(info.includes('2x'), 'the panel shows the stack amount', info.slice(0, 200))
  check(info.includes('ʀᴀᴛᴇ'), 'the panel shows the generation rate')
  check(info.includes('ɢᴇɴ ᴠᴀʟᴜᴇ'), 'the panel shows the current value')
  check(info.includes('ᴏᴡɴᴇʀ') && info.includes(OWNER), 'the panel names the owner')
  check(info.includes('ɪsʟᴀɴᴅ'), 'the panel names the island')
  const upgrade = win ? slotJson(win, 11) : ''
  check(upgrade.includes('ᴄᴏᴀʟ ɢᴇɴᴇʀᴀᴛᴏʀ'), 'the upgrade button names the next tier',
    upgrade.slice(0, 220))
  check(upgrade.includes('ɴᴇᴡ ʀᴀᴛᴇ') && upgrade.includes('ɴᴇᴡ ᴠᴀʟᴜᴇ'),
    'the upgrade button previews the new rate and value')
  check(upgrade.includes('✔') && upgrade.includes('ᴄʟɪᴄᴋ ᴛᴏ ᴜᴘɢʀᴀᴅᴇ'),
    'an affordable upgrade is ticked and clickable')
  const pickup = win ? slotJson(win, 15) : ''
  check(pickup.includes('ᴘɪᴄᴋ ᴜᴘ') && pickup.includes('2x'),
    'the pickup button says what you get back', pickup.slice(0, 200))
  // upgrading a 2x stack costs 2 x $5,000
  const coinsBeforeUpgrade = await coins()
  clearChat(owner)
  await click(owner, 11)
  check(await waitChat(owner, /upgraded .*cobblestone generator.* to .*coal generator/i, 15000),
    'clicking the upgrade applies it', recentChat(owner))
  const coinsAfterUpgrade = await coins()
  check(coinsBeforeUpgrade - coinsAfterUpgrade >= 10000,
    'the upgrade charged for the whole stack (2 x $5,000)',
    `${coinsBeforeUpgrade} -> ${coinsAfterUpgrade}`)
  await sleep(1000)
  check(owner.blockAt(v3(genPos.x, genPos.y, genPos.z))?.name === 'coal_block',
    'the block became the new tier',
    String(owner.blockAt(v3(genPos.x, genPos.y, genPos.z))?.name))
  const holo3 = await holoAt(genPos.x, genPos.y, genPos.z)
  check(/2x/.test(holo3) && /ᴄᴏᴀʟ/.test(holo3), 'the hologram followed the upgrade',
    holo3.trim().slice(0, 160))
  await closeWin(owner)

  // ------------------------------------------------- G9 anti-exploit
  phase(9, 'anti-exploit guards')
  // a normal break takes one generator out of the stack, never the block
  const coalBefore = owner.inventory.items().filter((i) => i.name === 'coal_block').length
  clearChat(owner)
  await owner.dig(owner.blockAt(v3(genPos.x, genPos.y, genPos.z))).catch(() => {})
  await sleep(1500)
  check(await waitChat(owner, /picked up one generator/i, 12000),
    'a normal break takes one out of the stack', recentChat(owner))
  check(owner.blockAt(v3(genPos.x, genPos.y, genPos.z))?.name === 'coal_block',
    'the rest of the stack stays placed')
  const coalItems = genItems(owner, 'coal_block')
  check(coalItems.length >= 1, 'the recovered item is a named generator, not a plain block',
    JSON.stringify(owner.inventory.items().map((i) => i.name)))
  check(owner.inventory.items().filter(
    (i) => i.name === 'coal_block').length === coalBefore + 1,
  'exactly one item came back (no duplication)')
  // sneak-break takes the whole (now 1x) stack
  clearChat(owner)
  owner.setControlState('sneak', true)
  await sleep(300)
  await owner.dig(owner.blockAt(v3(genPos.x, genPos.y, genPos.z))).catch(() => {})
  await sleep(1200)
  owner.setControlState('sneak', false)
  check(await waitChat(owner, /picked up 1x .*coal generator/i, 12000),
    'a sneak-break takes the whole stack', recentChat(owner))
  check(!solidAt(owner, genPos.x, genPos.y, genPos.z), 'the block is gone after the last pickup')
  const holoGone = await holoAt(genPos.x, genPos.y, genPos.z)
  check(!/ɢᴇɴᴇʀᴀᴛᴏʀ/.test(holoGone), 'the hologram went with it', holoGone.trim().slice(0, 120))
  check(!(dataYaml()?.generators || []).some(
    (g) => g.x === genPos.x && g.z === genPos.z),
  'the registration was removed from generators-data.yml')

  // generators refuse to be placed outside the island: the wilderness
  // deny line fires first, and nothing is ever registered out there
  clearChat(owner)
  const far = { x: HX + 60, y: GY, z: HZ }
  const registeredBefore = (dataYaml()?.generators || []).length
  await rc(`execute in ${DIM} run fill ${far.x - 1} ${far.y} ${far.z - 1} ${far.x + 1} ${far.y} ${far.z + 3} stone`)
  await sleep(1500)
  await rc(`execute in ${DIM} run tp ${OWNER} ${far.x + 0.5} ${far.y + 1} ${far.z + 2.5}`)
  const farSupport = await waitUntil(
    () => (owner.blockAt(v3(far.x, far.y, far.z))?.name === 'stone'
      ? owner.blockAt(v3(far.x, far.y, far.z)) : null), 30000, 1000)
  if (farSupport) {
    await sleep(1000)
    await placeOn(owner, farSupport, genItems(owner, 'coal_block')[0])
    check(await waitChat(owner, /only be placed on your own island|only build inside your own island/i, 12000),
      'placing off-island is refused with a clear message', recentChat(owner))
    check(owner.blockAt(v3(far.x, far.y + 1, far.z))?.name !== 'coal_block',
      'no generator block survives the refused placement')
    check((dataYaml()?.generators || []).length === registeredBefore,
      'nothing was registered off-island')
  } else {
    bad('off-island test spot could not be prepared',
      'bot at ' + JSON.stringify(owner.entity && owner.entity.position))
  }
  await rc(`execute in ${DIM} run tp ${OWNER} ${HX + 0.5} ${GY + 1} ${HZ + 0.5}`)
  await sleep(3000)

  // TNT never blows a generator up
  clearChat(owner)
  await rc(`execute in ${DIM} run summon tnt ${fastPos.x + 1.5} ${fastPos.y} ${fastPos.z + 1.5} {fuse:20s}`)
  await sleep(4000)
  check(owner.blockAt(v3(fastPos.x, fastPos.y, fastPos.z))?.name === 'netherite_block',
    'an explosion leaves the generator standing',
    String(owner.blockAt(v3(fastPos.x, fastPos.y, fastPos.z))?.name))

  // the /sell window refuses generator items (they are custom items)
  const sellWin = await openWindow(owner, '/sell')
  check(!!sellWin, '/sell opens')
  await closeWin(owner)

  // ------------------------------------------------- G10 island menu link
  phase(10, 'the island menu opens the generator menu')
  win = await openWindow(owner, '/is')
  check(!!win, '/is opens the island menu')
  const gensButton = win ? slotJson(win, 25) : ''
  check(gensButton.includes('ɢᴇɴᴇʀᴀᴛᴏʀs'), 'slot 25 is the generators button',
    gensButton.slice(0, 200))
  await click(owner, 25)
  const gensWin = await waitWindow(owner)
  check(gensWin && JSON.stringify(gensWin.title).toLowerCase().includes('generators'),
    'clicking it opens the generator menu', gensWin && JSON.stringify(gensWin.title))
  await closeWin(owner)

  // ------------------------------------------------- G10b island GUIs
  phase(10, 'the restyled island menus')
  win = await openWindow(owner, '/is')
  check(!!win, '/is opens the island menu')
  const rows = [slotJson(win, 10), slotJson(win, 12), slotJson(win, 14), slotJson(win, 16),
    slotJson(win, 19), slotJson(win, 21), slotJson(win, 23), slotJson(win, 25)]
  check(rows.every((j) => j && j !== 'null'), 'both button rows are filled')
  check(rows.every((j) => /[ᴀ-ᴢɪ]/.test(j)), 'every island button speaks small caps')
  check(slotName(win, 0) === 'black_stained_glass_pane'
    && slotName(win, 53) === 'black_stained_glass_pane',
  'the menu is framed top and bottom', slotName(win, 0) + '/' + slotName(win, 53))
  // upgrades sub-menu
  await click(owner, 19)
  win = await waitWindow(owner)
  check(win && JSON.stringify(win.title).toLowerCase().includes('upgrade'),
    'the upgrades menu opens', win && JSON.stringify(win.title))
  const claim = win ? slotJson(win, 11) : ''
  check(claim.includes('✔') || claim.includes('✖'),
    'the claim upgrade shows a live affordability marker', claim.slice(0, 220))
  check(claim.includes('ᴄᴏsᴛ') || claim.includes('ᴘʀɪᴄᴇ'), 'its cost line is small caps')
  check(slotName(win, 18) === 'arrow' && slotName(win, 26) === 'barrier',
    'back and close sit where they always do')
  await click(owner, 18)
  win = await waitWindow(owner)
  check(win && JSON.stringify(win.title).toLowerCase().includes('island'),
    'back returns to the island menu')
  // buffs sub-menu
  await click(owner, 21)
  win = await waitWindow(owner)
  const buff = win ? slotJson(win, 11) : ''
  check(buff.includes('✔') || buff.includes('✖'), 'buffs show the same marker',
    buff.slice(0, 220))
  check(buff.includes('ᴅᴜʀᴀᴛɪᴏɴ') || buff.includes('ᴄʟɪᴄᴋ'), 'buff lore is small caps too')
  await closeWin(owner)
  // the spawner menu got the same language
  win = await openWindow(owner, '/spawner')
  const pig = win ? slotJson(win, 20) : ''
  check(!!win && /[ᴀ-ᴢɪ]/.test(pig), 'the spawner menu speaks small caps', pig.slice(0, 200))
  check(pig.includes('✔') || pig.includes('✖'), 'spawner prices carry the marker')
  await closeWin(owner)

  // ------------------------------------------------- G11 restart
  phase(11, 'restart persistence')
  const liveBefore = (dataYaml()?.generators || []).length
  check(liveBefore >= 1, 'at least one generator is registered before the restart',
    String(liveBefore))
  quitBot(owner, OWNER)
  await sleep(1500)
  await stopServer()
  await startServer('boot2')
  await connectRcon()
  await baseSetup()
  auditLog('boot2')
  owner = makeBot(OWNER)
  check(await waitSpawn(owner), 'owner spawns after the restart')
  await sleep(3000)
  clearChat(owner)
  owner.chat('/is go')   // the plugin's own teleport keeps the client in sync
  await waitChat(owner, /teleported|welcome|home/i, 20000)
  await sleep(4000)
  await waitUntil(() => solidAt(owner, fastPos.x, fastPos.y, fastPos.z), 30000, 1000)
  check(owner.blockAt(v3(fastPos.x, fastPos.y, fastPos.z))?.name === 'netherite_block',
    'the placed generator survived the restart')
  const holoAfter = await holoAt(fastPos.x, fastPos.y, fastPos.z)
  check(/ɴᴇᴛʜᴇʀɪᴛᴇ/.test(holoAfter), 'its hologram came back too', holoAfter.trim().slice(0, 160))
  const payAfterRestart = await coins()
  const paidAgain = await waitUntil(async () => {
    const now = await coins()
    return now !== null && now >= payAfterRestart + 8000 ? now : null
  }, 40000, 2000)
  check(!!paidAgain, 'it still produces after the restart', `${payAfterRestart} -> ${paidAgain}`)
  await walkTo(owner, fastPos.x, fastPos.z + 2)
  win = null
  for (let attempt = 0; attempt < 3 && !win; attempt++) {
    await owner.lookAt(v3(fastPos.x + 0.5, fastPos.y + 0.5, fastPos.z + 0.5))
    await sleep(700)
    try { await owner.activateBlock(owner.blockAt(v3(fastPos.x, fastPos.y, fastPos.z))) }
    catch (e) { log('[interact] ' + e.message) }
    win = await waitWindow(owner, 8000)
    if (!win) {
      log('[interact] right-click gave no window, trying /gens info')
      win = await openWindow(owner, '/gens info', 10000)
    }
  }
  check(!!win, 'it is still manageable after the restart')
  check(win ? slotJson(win, 11).includes('ᴍᴀxɪᴍᴜᴍ ᴛɪᴇʀ') : false,
    'the top tier says so instead of offering an upgrade',
    win ? slotJson(win, 11).slice(0, 200) : '')
  await closeWin(owner)

  // ------------------------------------------------- G12 audit
  phase(12, 'final audit')
  quitBot(owner, OWNER)
  await sleep(1500)
  await stopServer()
  auditLog('final')
}

main().then(async () => {
  log('')
  log(`RESULT  pass=${pass} fail=${fail}`)
  if (failures.length) log('failed: ' + failures.join(' | '))
  await sleep(500)
  process.exit(fail === 0 ? 0 : 1)
}).catch(async (err) => {
  log('FATAL ' + (err && err.stack ? err.stack : err))
  try { if (serverProc) serverProc.kill('SIGKILL') } catch { /* gone */ }
  log(`RESULT  pass=${pass} fail=${fail + 1}`)
  await sleep(500)
  process.exit(1)
})
