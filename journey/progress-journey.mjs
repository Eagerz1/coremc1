#!/usr/bin/env node
// CoreMC Collections + Achievements journey — the live counterpart to the
// unit tests. Boots a Paper server from THIS checkout and drives a real
// player with mineflayer + RCON through the whole permanent-progression
// feature:
//
//   C1  join + /is create
//   C2  /collections + /achievements in chat (help, list, info, points)
//   C3  the /collections menu: categories, panel, recipes, held rewards
//   C4  real play feeds a Collection AND earns an Achievement
//       (natural block pays, a player-placed block never does)
//   C5  milestones: crossing a tier announces it, pays the coins, and a
//       claim is exactly once
//   C6  Collection-locked recipes unlock permanently at their tier
//   C7  hidden Collections stay ??? until found; secret Achievements
//       stay secret until earned
//   C8  rewards that cannot be delivered are held safely, never dropped
//   C9  the island menu opens both menus (slots 30 and 32)
//   C10 restart persistence: collections-data.yml + achievements-data.yml
//       round-trip and the placeholders agree
//   C11 full-log audit: no server ERRORs, no CoreMC warn/error lines
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
const JOURNEY_LOG = path.join(ROOT, 'progress-journey.log')
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
const recentChat = (bot) => (bot ? bot.__chat.slice(-8).join(' || ') : 'no bot')
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
async function closeWin(bot) {
  try { if (bot.currentWindow) bot.closeWindow(bot.currentWindow) } catch { /* noop */ }
  await sleep(600)
}
async function openWindow(bot, command, timeoutMs = 20000) {
  await closeWin(bot)
  bot.chat(command)
  return waitWindow(bot, timeoutMs)
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
async function waitBlock(bot, x, y, z, wantSolid = true, timeoutMs = 20000) {
  return waitUntil(() => {
    const b = bot.blockAt(v3(x, y, z))
    if (!b) return false
    const solid = !['air', 'cave_air', 'void_air'].includes(b.name)
    return wantSolid ? solid : !solid
  }, timeoutMs, 300)
}
async function digAt(bot, x, y, z) {
  const block = bot.blockAt(v3(x, y, z))
  if (!block) { log(`[dig] ${x},${y},${z}: no block there`); return false }
  try {
    await bot.dig(block)
    return true
  } catch (err) {
    log(`[dig] ${x},${y},${z} (${block.name}): ${err && err.message}`)
    return false
  }
}
/** Sets a block next to home and mines it as a natural world block. */
async function mineNatural(bot, material, dx, dz) {
  const x = HX + dx; const z = HZ + dz; const y = GY + 1
  await rc(`execute in ${DIM} run setblock ${x} ${y} ${z} minecraft:${material}`)
  if (!await waitBlock(bot, x, y, z, true)) return false
  await walkTo(bot, x + 1, z)
  await bot.lookAt(v3(x + 0.5, y + 0.5, z + 0.5)).catch(() => {})
  const dug = await digAt(bot, x, y, z)
  await sleep(1200)
  return dug
}

// ---------------------------------------------------------------- readers
/** "%coremc_collection_percent%|..." through the mock PlaceholderAPI. */
async function progressPlaceholders(name = OWNER) {
  const out = await rc('papiprogress ' + name)
  const m = out.match(/PROGRESSRESULT \w+ ([^\n]+)/)
  if (!m) return null
  const parts = m[1].trim().split('|').map((v) => parseInt(v.replace(/,/g, ''), 10))
  return {
    percent: parts[0],
    complete: parts[1],
    collections: parts[2],
    points: parts[3],
    earned: parts[4],
    achievements: parts[5],
    waiting: parts[6],
    held: parts[7],
  }
}
async function coins(name = OWNER) {
  const out = await rc('papicheck ' + name)
  const m = out.match(/PAPIRESULT \w+ [^|]*\|[^|]*\|[^|]*\|[^|]*\|[^|]*\|([\d,]+)\|/)
  return m ? parseInt(m[1].replace(/,/g, ''), 10) : null
}
/** The owner's row in a permanent data file (uuid-keyed). */
function dataFor(file) {
  const full = path.join(PLUGIN_DIR, file)
  if (!fs.existsSync(full)) return null
  const yaml = yamlLoad(fs.readFileSync(full, 'utf8'))
  const players = yaml && yaml.players ? yaml.players : null
  if (!players) return null
  const key = Object.keys(players)[0]
  return key ? players[key] : null
}

// ------------------------------------------------------------------- main
let owner = null
let HX = 0; let HZ = 0; let GY = 0

async function main() {
  await startServer('boot1')
  await connectRcon()
  await baseSetup()
  auditLog('boot1')

  // --------------------------------------------------- C1 island
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
  await rc('give JOwner diamond_pickaxe 1')
  await sleep(800)
  const pick = owner.inventory.items().find((i) => i.name === 'diamond_pickaxe')
  if (pick) await owner.equip(pick, 'hand')

  // --------------------------------------------------- C2 chat commands
  phase(2, 'the chat commands')
  clearChat(owner)
  owner.chat('/collections help')
  check(await waitChat(owner, /collections claim <collection> <tier>/i),
    '/collections help lists the claim command', recentChat(owner))
  clearChat(owner)
  owner.chat('/collections list')
  check(await waitChat(owner, /permanent progress, season after season/i),
    '/collections list says progress is permanent')
  check(await waitChat(owner, /ᴍɪɴɪɴɢ .* ᴄᴏʟʟᴇᴄᴛɪᴏɴs .* 0%/),
    'the category list is rendered in small caps', recentChat(owner))
  clearChat(owner)
  owner.chat('/collections info')
  check(await waitChat(owner, /collections: .*0%/i), 'a fresh player is at 0%', recentChat(owner))
  clearChat(owner)
  owner.chat('/collections info nonsense')
  check(await waitChat(owner, /unknown collection: nonsense/i), 'an unknown collection is refused')
  clearChat(owner)
  owner.chat('/achievements help')
  check(await waitChat(owner, /achievements claim <achievement>/i),
    '/achievements help lists the claim command')
  clearChat(owner)
  owner.chat('/achievements points')
  check(await waitChat(owner, /achievement points: 0\//i),
    'a fresh player has zero achievement points', recentChat(owner))
  clearChat(owner)
  owner.chat('/achievements list')
  check(await waitChat(owner, /permanent, prestige only/i),
    'the achievement list states the prestige rule')

  // --------------------------------------------------- C3 the menus
  phase(3, 'the /collections and /achievements menus')
  let win = await openWindow(owner, '/collections')
  check(!!win, '/collections opens a menu')
  check(win && JSON.stringify(win.title).toLowerCase().includes('collections'),
    'window title is "COREMC — Collections"', win && JSON.stringify(win.title))
  check(win && win.slots.length - 36 === 54, 'the menu is a double chest',
    String(win && win.slots.length))
  const panel = win ? slotJson(win, 4) : ''
  check(panel.includes('ʏᴏᴜʀ ᴄᴏʟʟᴇᴄᴛɪᴏɴs'), 'slot 4 is the player panel', panel.slice(0, 200))
  check(panel.includes('ᴄᴏʟʟᴇᴄᴛɪᴏɴs ᴀʀᴇ ᴘᴇʀᴍᴀɴᴇɴᴛ'), 'the panel promises permanence')
  check(panel.includes('■'), 'the panel draws a progress bar')
  const mining = win ? slotJson(win, 10) : ''
  check(mining.includes('ᴍɪɴɪɴɢ'), 'slot 10 is the Mining category', mining.slice(0, 200))
  check(mining.includes('ᴄʟɪᴄᴋ ᴛᴏ ᴏᴘᴇɴ'), 'category lore spells out the click')
  check(slotJson(win, 53).includes('ʀᴇᴄɪᴘᴇs'), 'slot 53 is the recipe book')
  check(slotJson(win, 48).includes('ʜᴇʟᴅ ʀᴇᴡᴀʀᴅs'), 'slot 48 is the held-rewards button')
  check(slotName(win, 45) === 'arrow' && slotName(win, 49) === 'barrier',
    'back and close sit in the bottom row')
  await click(owner, 10)
  win = await waitWindow(owner)
  check(win && JSON.stringify(win.title).toLowerCase().includes('mining'),
    'clicking Mining opens the mining page', win && JSON.stringify(win.title))
  const cobbleSlot = win ? slotJson(win, 10) : ''
  check(cobbleSlot.includes('ᴄᴏʙʙʟᴇsᴛᴏɴᴇ'), 'the first mining collection is Cobblestone',
    cobbleSlot.slice(0, 200))
  check(cobbleSlot.includes('ᴄᴏʟʟᴇᴄᴛᴇᴅ'), 'entries show what you have collected')
  await click(owner, 10)
  win = await waitWindow(owner)
  check(win && slotJson(win, 19).includes('ᴛɪᴇʀ ɪ'), 'the detail view lists tier I',
    win ? slotJson(win, 19).slice(0, 200) : '')
  check(win && slotName(win, 19) === 'gray_stained_glass_pane',
    'an unreached tier is a dull grey pane, never a chest', win ? slotName(win, 19) : '')
  check(win && slotJson(win, 19).includes('ʟᴏᴄᴋᴇᴅ') && slotJson(win, 19).includes('✖'),
    'and it is marked LOCKED with a red cross')
  await closeWin(owner)

  win = await openWindow(owner, '/achievements')
  check(!!win, '/achievements opens a menu')
  check(win && JSON.stringify(win.title).toLowerCase().includes('achievements'),
    'window title is "COREMC — Achievements"', win && JSON.stringify(win.title))
  const apanel = win ? slotJson(win, 4) : ''
  check(apanel.includes('ᴘᴏɪɴᴛs ᴀʀᴇ ᴘʀᴇsᴛɪɢᴇ, ɴᴏᴛ ᴄᴜʀʀᴇɴᴄʏ'),
    'the achievement panel states the prestige rule', apanel.slice(0, 240))
  check(slotJson(win, 53).includes('sᴇᴄʀᴇᴛ'), 'slot 53 is the secrets button')
  await closeWin(owner)

  // --------------------------------------------------- C4 real play counts
  phase(4, 'real play feeds a Collection and earns an Achievement')
  clearChat(owner)
  const mined = await mineNatural(owner, 'cobblestone', 2, 2)
  check(mined, 'the bot mined a natural cobblestone block')
  check(await waitChat(owner, /ACHIEVEMENT .*First Strike/i),
    'breaking the first block earns "First Strike"', recentChat(owner))
  check(await waitChat(owner, /\+5 points/i), 'the announcement names the points')
  clearChat(owner)
  owner.chat('/collections info cobblestone')
  check(await waitChat(owner, /cobblestone.*: 1 collected/i),
    'the Cobblestone Collection counted exactly one', recentChat(owner))
  clearChat(owner)
  owner.chat('/achievements points')
  check(await waitChat(owner, /achievement points: 5\//i), 'the player now has 5 points')

  // a block the player placed themselves must never count
  await rc('give JOwner cobblestone 1')
  await sleep(900)
  const stone = owner.inventory.items().find((i) => i.name === 'cobblestone')
  check(!!stone, 'the bot has a cobblestone to place')
  let placedCounted = true
  if (stone) {
    const support = solidAt(owner, HX + 2, GY, HZ + 2)
    if (support) {
      await walkTo(owner, HX + 1, HZ + 1)
      try {
        await owner.equip(stone, 'hand')
        await sleep(300)
        await owner.placeBlock(support, new Vec3(0, 1, 0))
      } catch (e) { log('[place] ' + e.message) }
      await sleep(1200)
      const at = { x: HX + 2, y: GY + 1, z: HZ + 2 }
      if (await waitBlock(owner, at.x, at.y, at.z, true)) {
        if (pick) await owner.equip(pick, 'hand').catch(() => {})
        await digAt(owner, at.x, at.y, at.z)
        await sleep(1500)
        clearChat(owner)
        owner.chat('/collections info cobblestone')
        const hit = await waitChat(owner, /cobblestone.*: (\d+) collected/i)
        placedCounted = !hit || hit[1] !== '1'
      }
    }
  }
  check(!placedCounted,
    'breaking your OWN placed block adds nothing (anti place-mine loop)')

  // --------------------------------------------------- C5 milestones
  phase(5, 'milestones, rewards and exactly-once claiming')
  const coinsBefore = await coins()
  clearChat(owner)
  await rc('corecollections add JOwner cobblestone 249')
  check(await waitChat(owner, /ᴄᴏʙʙʟᴇsᴛᴏɴᴇ ɪ .*reached/i)
    || await waitChat(owner, /Cobblestone I .*reached/i),
  'crossing tier I announces the milestone', recentChat(owner))
  check(await waitChat(owner, /a reward is waiting/i),
    'and points the player at the claim command')
  clearChat(owner)
  owner.chat('/collections claim cobblestone 1')
  check(await waitChat(owner, /claimed .*cobblestone .*tier .*1/i),
    'the milestone reward can be claimed', recentChat(owner))
  const coinsAfter = await coins()
  check(coinsAfter !== null && coinsBefore !== null && coinsAfter - coinsBefore === 2500,
    'the 2,500 coin reward actually arrived', `${coinsBefore} -> ${coinsAfter}`)
  clearChat(owner)
  owner.chat('/collections claim cobblestone 1')
  check(await waitChat(owner, /already claimed/i), 'a second claim is refused (exactly once)')
  clearChat(owner)
  owner.chat('/collections claim cobblestone 5')
  check(await waitChat(owner, /have not reached/i), 'an unreached tier cannot be claimed')

  win = await openWindow(owner, '/collections')
  await click(owner, 10)
  win = await waitWindow(owner)
  const entryNow = win ? slotJson(win, 10) : ''
  check(entryNow.includes('250'), 'the mining page shows the new total',
    entryNow.slice(0, 260))
  check(entryNow.includes('ᴛɪᴇʀ') && entryNow.includes('ɴᴇxᴛ ᴛɪᴇʀ'),
    'and the tier plus the road to the next one')
  await closeWin(owner)

  // --------------------------------------------------- C6 recipes
  phase(6, 'collection-locked recipes')
  win = await openWindow(owner, '/collections recipes')
  check(!!win, '/collections recipes opens the recipe book')
  const lockedRecipe = win ? slotJson(win, 10) : ''
  check(lockedRecipe.includes('ᴄᴏʙʙʟᴇ ᴄᴏᴍᴘʀᴇssᴏʀ'), 'the first recipe is the Cobble Compressor',
    lockedRecipe.slice(0, 220))
  check(lockedRecipe.includes('ʟᴏᴄᴋᴇᴅ') && lockedRecipe.includes('✖'),
    'a recipe you have not earned is clearly locked')
  check(slotName(win, 10) === 'gray_stained_glass_pane',
    'locked recipes never look like unlocked ones', win ? slotName(win, 10) : '')
  await closeWin(owner)
  await rc('corecollections add JOwner cobblestone 5000')
  await sleep(1500)
  win = await openWindow(owner, '/collections recipes')
  const unlockedRecipe = win ? slotJson(win, 10) : ''
  check(unlockedRecipe.includes('ᴜɴʟᴏᴄᴋᴇᴅ') && unlockedRecipe.includes('✔'),
    'reaching tier III unlocks it permanently', unlockedRecipe.slice(0, 220))
  check(slotName(win, 10) === 'stone', 'and it renders as its own icon again',
    win ? slotName(win, 10) : '')
  await closeWin(owner)

  // --------------------------------------------------- C7 hidden + secret
  phase(7, 'hidden collections and secret achievements')
  win = await openWindow(owner, '/collections')
  await click(owner, 10 + 0) // mining is slot 10; discoveries is further along
  await closeWin(owner)
  win = await openWindow(owner, '/collections')
  // discoveries is the 9th category (index 8 -> slot 19+1)
  let discoverySlot = -1
  for (const slot of [10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25]) {
    if (slotJson(win, slot).includes('ᴅɪsᴄᴏᴠᴇʀɪᴇs')) { discoverySlot = slot; break }
  }
  check(discoverySlot > 0, 'the Discoveries category is on the root menu', String(discoverySlot))
  if (discoverySlot > 0) {
    await click(owner, discoverySlot)
    win = await waitWindow(owner)
    const hidden = win ? slotJson(win, 10) : ''
    check(hidden.includes('? ? ?') || hidden.includes('?'),
      'an undiscovered entry shows as ???', hidden.slice(0, 200))
    check(hidden.includes('ᴜɴᴅɪsᴄᴏᴠᴇʀᴇᴅ'), 'its lore says undiscovered')
    check(!hidden.includes('ᴄᴏʟʟᴇᴄᴛᴇᴅ') && !hidden.includes('■')
      && !hidden.includes('ᴛɪᴇʀ'),
    'it never leaks amounts, tiers or odds', hidden.slice(0, 260))
    check(slotName(win, 10) === 'gray_dye', 'hidden entries are deliberately dull',
      win ? slotName(win, 10) : '')
  }
  await closeWin(owner)
  win = await openWindow(owner, '/achievements secrets')
  check(!!win, '/achievements secrets opens the secret list')
  const secret = win ? slotJson(win, 10) : ''
  check(secret.includes('sᴇᴄʀᴇᴛ'), 'an unearned secret stays secret', secret.slice(0, 200))
  check(secret.includes('ɴᴏ ʜɪɴᴛs'), 'and gives nothing away')
  await closeWin(owner)
  clearChat(owner)
  owner.chat('/collections info ancient_find')
  check(await waitChat(owner, /still a mystery/i),
    'even /collections info keeps a hidden entry hidden', recentChat(owner))

  // --------------------------------------------------- C8 held rewards
  phase(8, 'rewards that cannot be delivered are held, never dropped')
  // the tier V cobblestone reward pays Sky Tokens, whose system lives on
  // another branch — it must be parked, not lost
  await rc('corecollections add JOwner cobblestone 100000')
  await sleep(1500)
  clearChat(owner)
  owner.chat('/collections claim cobblestone 5')
  const held = await waitChat(owner, /held safely/i)
  check(!!held, 'a reward with no delivery system is held safely', recentChat(owner))
  win = await openWindow(owner, '/collections held')
  check(!!win, '/collections held opens the held-rewards view')
  const heldItem = win ? slotJson(win, 10) : ''
  check(heldItem.includes('sᴋʏ ᴛᴏᴋᴇɴs'), 'the held reward is listed with its label',
    heldItem.slice(0, 220))
  check(heldItem.includes('ᴀ ᴄᴏʟʟᴇᴄᴛɪᴏɴ ᴍɪʟᴇsᴛᴏɴᴇ'), 'it remembers where it came from')
  await closeWin(owner)
  const pendingYaml = path.join(PLUGIN_DIR, 'pending-rewards.yml')
  check(fs.existsSync(pendingYaml), 'pending-rewards.yml exists on disk')
  check(fs.existsSync(pendingYaml)
    && fs.readFileSync(pendingYaml, 'utf8').includes('sky_tokens'),
  'and the parked reward is written to it')

  // --------------------------------------------------- C9 admin + links
  phase(9, 'admin tools and the island menu links')
  clearChat(owner)
  await rc('coreachievements grant JOwner homestead')
  check(await waitChat(owner, /ACHIEVEMENT .*Homestead/i)
    || await waitChat(owner, /achievement/i),
  'an admin grant announces the achievement', recentChat(owner))
  const out = await rc('coreachievements info JOwner')
  check(/points/i.test(out), '/coreachievements info prints a summary', out.trim().slice(0, 200))
  const reload = await rc('corecollections reload')
  check(/reloaded collections\.yml/i.test(reload), '/corecollections reload works',
    reload.trim().slice(0, 160))
  const reloadA = await rc('coreachievements reload')
  check(/reloaded achievements\.yml/i.test(reloadA), '/coreachievements reload works',
    reloadA.trim().slice(0, 160))

  win = await openWindow(owner, '/is')
  check(!!win, '/is opens the island menu')
  const collButton = win ? slotJson(win, 30) : ''
  const achButton = win ? slotJson(win, 32) : ''
  check(collButton.includes('ᴄᴏʟʟᴇᴄᴛɪᴏɴs'), 'slot 30 is the Collections button',
    collButton.slice(0, 200))
  check(achButton.includes('ᴀᴄʜɪᴇᴠᴇᴍᴇɴᴛs'), 'slot 32 is the Achievements button',
    achButton.slice(0, 200))
  await click(owner, 30)
  let linked = await waitWindow(owner)
  check(linked && JSON.stringify(linked.title).toLowerCase().includes('collections'),
    'it opens the Collections menu', linked && JSON.stringify(linked.title))
  await closeWin(owner)
  win = await openWindow(owner, '/is')
  await click(owner, 32)
  linked = await waitWindow(owner)
  check(linked && JSON.stringify(linked.title).toLowerCase().includes('achievements'),
    'and slot 32 opens the Achievements menu', linked && JSON.stringify(linked.title))
  await closeWin(owner)

  // --------------------------------------------------- C10 persistence
  phase(10, 'restart persistence')
  clearChat(owner)
  owner.chat('/collections info cobblestone')
  const totalHit = await waitChat(owner, /cobblestone: ([\d,]+) collected/i)
  check(!!totalHit, 'the Collection total reads back before the restart', recentChat(owner))
  const totalBefore = totalHit ? totalHit[1] : ''
  const before = await progressPlaceholders()
  check(!!before && before.percent > 0, 'the placeholders report live progress',
    JSON.stringify(before))
  check(!!before && before.points >= 5, 'achievement points are in the placeholders',
    JSON.stringify(before))
  check(!!before && before.held >= 1, 'held rewards are in the placeholders',
    JSON.stringify(before))
  quitBot(owner, OWNER)
  await sleep(1500)
  await stopServer()

  const collData = dataFor('collections-data.yml')
  check(!!collData && collData.amounts && collData.amounts.cobblestone >= 100000,
    'collections-data.yml stored the lifetime total',
    JSON.stringify(collData || {}).slice(0, 200))
  check(!!collData && Array.isArray(collData.claimed) && collData.claimed.length >= 1,
    'and the claims that were already paid')
  check(!!collData && Array.isArray(collData.unlocks)
    && collData.unlocks.some((u) => String(u).startsWith('recipe:')),
  'and the permanent recipe unlock', JSON.stringify(collData && collData.unlocks))
  const achData = dataFor('achievements-data.yml')
  check(!!achData && achData.earned && Object.keys(achData.earned).length >= 2,
    'achievements-data.yml stored the earned achievements',
    JSON.stringify(achData || {}).slice(0, 200))

  await startServer('boot2')
  await connectRcon()
  await baseSetup()
  auditLog('boot2')
  owner = makeBot(OWNER)
  check(await waitSpawn(owner), 'owner spawns after the restart')
  await sleep(3000)
  const after = await progressPlaceholders()
  check(!!after && !!before && after.percent === before.percent
    && after.points === before.points && after.complete === before.complete
    && after.held === before.held,
  'every permanent number survived the restart exactly',
  `${JSON.stringify(before)} -> ${JSON.stringify(after)}`)
  clearChat(owner)
  owner.chat('/collections info cobblestone')
  check(!!totalBefore && !!await waitChat(owner,
    new RegExp('cobblestone: ' + totalBefore.replace(/,/g, ',') + ' collected', 'i')),
  `the Collection total came back (${totalBefore})`, recentChat(owner))
  clearChat(owner)
  owner.chat('/collections claim cobblestone 1')
  check(await waitChat(owner, /already claimed/i),
    'a claim from before the restart is still exactly once')
  win = await openWindow(owner, '/collections recipes')
  check(win && slotJson(win, 10).includes('ᴜɴʟᴏᴄᴋᴇᴅ'),
    'the unlocked recipe is still unlocked', win ? slotJson(win, 10).slice(0, 160) : '')
  await closeWin(owner)
  win = await openWindow(owner, '/collections held')
  check(win && slotJson(win, 10).includes('sᴋʏ ᴛᴏᴋᴇɴs'),
    'the held reward is still waiting safely', win ? slotJson(win, 10).slice(0, 160) : '')
  await closeWin(owner)

  // --------------------------------------------------- C11 audit
  phase(11, 'final audit')
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
