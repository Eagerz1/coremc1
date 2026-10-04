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
//   P4b /companions: six earnable companions, unlock + summon + persistence
//   P4c /quests: three daily assignments render and persist
//   P5  /spawners: 30 regular spawners across two pages, 35 real
//       kills, unlock fanfare, direct buy + place; spawner-born kill pays
//       Core money/tokens WITHOUT counting wild progress; hostile GUI
//       interactions (shift/number-key/drop/double-click) cannot steal
//   P6  Omni-Tool panel -> enchants (15-grid) -> buy treasure-miner
//       (overlay: deterministic), mine 2 blocks -> 2 sky keys
//   P7  /crates: six crates render; open sky x2 (2nd is deterministically
//       the pity), no-key negative path
//   P8  outsider protection: guest dig denied, block intact
//   P9  /gens: 24 generators render; buy cobble gen, place, harvest
//   P10 void rescue back home
//   P10b chat cosmetics: /tags + /chatcolour GUIs, locked/selected states,
//       admin grant/revoke/check, the exact <RANK> <TAG> Player: Message
//       layout seen by a SECOND player, clean spacing with no tag/rank,
//       gradient + bold, '&' injection prevention, and no duplicate chat
//   P11 clean restart: everything persists (live + data-file asserts,
//       including dotted enchant ids and regular-spawner kill isolation)
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
  // must stay at night for P5 — natural spawning is still disabled on
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
  bot.__coreSidebar = false
  bot._client.on('scoreboard_display_objective', (packet) => {
    if (packet.position === 1) bot.__coreSidebar = packet.name === 'coremc'
  })
  const readWorld = (packet) => {
    const ws = packet?.worldState ?? packet
    if (ws && typeof ws.name === 'string') bot.__world = ws.name
  }
  bot._client.on('login', readWorld)
  bot._client.on('respawn', readWorld)
  bot.__raw = []
  bot.on('messagestr', (m) => { bot.__chat.push(m); if (bot.__chat.length > 600) bot.__chat.shift() })
  // Raw component JSON: lets the chat-cosmetics phase assert COLOURS
  // (messagestr is plain text and would hide formatting entirely).
  bot.on('message', (msg) => {
    try { bot.__raw.push(JSON.stringify(msg.json ?? msg)) } catch { /* noop */ }
    if (bot.__raw.length > 600) bot.__raw.shift()
  })
  // Signed player chat: vanilla clients display 'unsignedChatContent' when a
  // plugin rewrote the line. mineflayer renders the SIGNED content instead,
  // so grab the raw packet to see what real clients would be shown.
  bot._client.on('player_chat', (packet) => {
    try { bot.__raw.push(JSON.stringify(packet)) } catch { /* noop */ }
    if (bot.__raw.length > 600) bot.__raw.shift()
  })
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
function clearChat(bot) { bot.__chat.length = 0; if (bot.__raw) bot.__raw.length = 0 }
/** Server log with ANSI/section colour noise removed. */
function serverLogText() {
  const raw = fs.existsSync(SERVER_LOG) ? fs.readFileSync(SERVER_LOG, 'utf8') : ''
  // eslint-disable-next-line no-control-regex
  return raw.replace(/\u001b\[[0-9;]*m/g, '').replace(/\u00a7[0-9a-fk-or]/gi, '')
}
/**
 * Waits for a rendered chat line in the server log. The console is a real
 * chat viewer, so this is the authoritative view of what CoreMC rendered.
 */
async function waitServerLog(rx, timeoutMs = 20000) {
  const re = rx instanceof RegExp ? rx : new RegExp(rx, 'i')
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    const hit = serverLogText().match(re)
    if (hit) return hit
    await sleep(400)
  }
  return null
}
/** Occurrences of a rendered line in the server log (duplicate-chat guard). */
function serverLogCount(text) {
  return serverLogText().split('\n').filter((l) => l.includes(text)).length
}
/** Number of received lines containing {@code text} (duplicate-chat guard). */
function chatCount(bot, text) {
  return bot.__chat.filter((m) => m.includes(text)).length
}
/** Raw component JSON of the first received line containing {@code text}. */
function rawWith(bot, text) {
  return (bot.__raw || []).find((j) => j.includes(text)) || ''
}
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


// ---------------------------------------------------------- moderation journey
function playerInfoVisible(bot, name) {
  return !!(bot.players && bot.players[name])
}
function playerEntityVisible(bot, name) {
  return Object.values(bot.entities || {}).some((e) => e.username === name)
}
async function waitPlayerInfo(bot, name, wantVisible, timeoutMs = 15000) {
  return waitUntil(() => playerInfoVisible(bot, name) === wantVisible, timeoutMs, 250)
}
async function waitPlayerEntity(bot, name, wantVisible, timeoutMs = 15000) {
  return waitUntil(() => playerEntityVisible(bot, name) === wantVisible, timeoutMs, 250)
}
async function waitKickOrSpawn(bot, timeoutMs = 20000) {
  return new Promise((resolve) => {
    const t = setTimeout(() => resolve('timeout'), timeoutMs)
    bot.once('kicked', () => { clearTimeout(t); resolve('kicked') })
    bot.once('spawn', () => { clearTimeout(t); resolve('spawn') })
  })
}
async function reconnectGuest(label = 'guest reconnects') {
  expectedQuit.delete(GUEST)
  guest = makeBot(GUEST)
  const spawned = await waitSpawn(guest, 60000)
  check(spawned, label)
  if (spawned) {
    await waitUntil(() => guest.__world !== null, 30000)
    await rc(`execute in ${DIM} run tp ${GUEST} ${HX + 1.5} ${GY + 1} ${HZ + 0.5}`)
    await waitUntil(() => guest.entity && guest.entity.position.distanceTo(v3(HX + 1.5, GY + 1, HZ + 0.5)) < 8, 30000)
    await sleep(1000)
  }
  return guest
}
async function moderationJourney() {
  await rc(`op ${OWNER}`)
  await reconnectGuest('moderation guest joins')
  await rc(`op ${GUEST}`)
  await rc(`execute in ${DIM} run tp ${OWNER} ${HX + 0.5} ${GY + 1} ${HZ + 0.5}`)
  await rc(`execute in ${DIM} run tp ${GUEST} ${HX + 2.5} ${GY + 1} ${HZ + 0.5}`)
  await sleep(2000)

  clearChat(owner)
  owner.chat('/vanish')
  check(await waitChat(owner, /vanish enabled/i, 10000), '/vanish enables for staff')
  check(await waitPlayerInfo(guest, OWNER, true, 10000), 'staff viewer keeps vanished staff in tab')
  check(await waitPlayerEntity(guest, OWNER, true, 10000), 'staff viewer keeps vanished staff entity visible')

  await rc(`deop ${GUEST}`)
  clearChat(owner)
  owner.chat('/vanish')
  check(await waitChat(owner, /vanish disabled/i, 10000), '/vanish disables cleanly')
  clearChat(owner)
  owner.chat('/vanish')
  check(await waitChat(owner, /vanish enabled/i, 10000), '/vanish re-enables for nonstaff visibility check')
  check(await waitPlayerInfo(guest, OWNER, false, 10000), 'nonstaff viewer loses vanished staff from tab')
  check(await waitPlayerEntity(guest, OWNER, false, 10000), 'nonstaff viewer loses vanished staff entity')

  clearChat(owner)
  owner.chat('/spectate JGuest')
  check(await waitChat(owner, /now spectating/i, 10000), '/spectate <player> starts')
  clearChat(owner)
  owner.chat('/spectate')
  check(await waitChat(owner, /spectate ended/i, 10000), '/spectate exits and restores')
  check(await waitPlayerInfo(guest, OWNER, false, 10000), 'spectate exit restores prior vanished tab state')
  clearChat(owner)
  owner.chat('/vanish')
  check(await waitChat(owner, /vanish disabled/i, 10000), '/vanish disables after spectate restore')

  clearChat(owner)
  owner.chat('/cps JGuest')
  check(await waitChat(owner, /measuring arm-swing cps/i, 10000), '/cps starts privately')
  for (let i = 0; i < 30; i++) {
    try { guest.swingArm('right') } catch { /* noop */ }
    await sleep(150)
  }
  check(await waitChat(owner, /cps result.*not proof/i, 15000), '/cps reports current/average/peak without proof claim')

  clearChat(owner)
  owner.chat('/rotate JGuest 90')
  check(await waitChat(owner, /rotated .*90/i, 10000), '/rotate applies a normalized yaw change')

  clearChat(guest)
  clearChat(owner)
  owner.chat('/freeze JGuest Journey freeze')
  check(await waitChat(owner, /froze .*JGuest/i, 10000), '/freeze toggles on')
  check(await waitChat(guest, /you have been frozen/i, 10000), 'freeze target receives clear message')
  quitBot(guest, GUEST)
  await sleep(2000)
  clearChat(owner)
  owner.chat('/freeze JGuest Offline unfreeze')
  check(await waitChat(owner, /unfroze .*JGuest/i, 10000), 'staff can unfreeze an offline target')
  await reconnectGuest('guest rejoins after offline unfreeze')

  clearChat(owner)
  clearChat(guest)
  owner.chat('/mute JGuest 1s Short mute')
  check(await waitChat(owner, /muted .*JGuest.*1s/i, 10000), '/mute accepts second durations')
  guest.chat('muted_message_should_block')
  check(await waitChat(guest, /you are muted/i, 10000), 'mute blocks public chat')
  await sleep(1800)
  clearChat(owner)
  guest.chat('mute_expired_message')
  check(await waitChat(owner, /mute_expired_message/i, 10000), 'mute expiry allows chat again')

  clearChat(owner)
  owner.chat('/tiercorrect JGuest 1 set 2 journey setup')
  check(await waitChat(owner, /T1 counter to .*2/i, 10000), '/tiercorrect updates persistent counters')
  clearChat(owner)
  clearChat(guest)
  owner.chat('/t1 chat_spam JGuest Custom tier spam')
  check(await waitChat(owner, /T1 .*offense .*#3.*mute 5m/i, 10000), '/t1 escalates offense #3 to 5m mute with custom reason')
  check(await waitChat(guest, /custom tier spam/i, 10000), 'tier custom reason shown to player')
  clearChat(owner)
  owner.chat('/unmute JGuest tier test clear')
  check(await waitChat(owner, /revoked active mute/i, 10000), '/unmute revokes CoreMC mute')

  clearChat(owner)
  clearChat(guest)
  owner.chat('/t2 light_advertising JGuest')
  check(await waitChat(owner, /T2 .*offense .*#1.*warn/i, 10000), '/t2 first offense warns')
  check(await waitChat(guest, /Reason: .*Light Advertising/i, 10000), 'tier default human-readable reason shown')

  clearChat(owner)
  clearChat(guest)
  owner.chat('/t5 inappropriate_skin JGuest')
  check(await waitChat(owner, /warning\/change workflow/i, 10000), 'T5 skin rule requires acknowledgement before ban')
  check(await waitChat(guest, /warning.*inappropriate skin/i, 10000), 'T5 exception initially warns target')

  clearChat(owner)
  expectedQuit.add(GUEST)
  owner.chat('/t5 inappropriate_skin JGuest --ack Skin acknowledged')
  check(await waitChat(owner, /T5 .*offense .*#2.*ban permanent/i, 10000), 'T5 acknowledged action records permanent ban')
  await sleep(2500)
  clearChat(owner)
  owner.chat('/unban JGuest journey unban')
  check(await waitChat(owner, /revoked active ban/i, 10000), '/unban revokes CoreMC ban')
  await reconnectGuest('guest rejoins after T5 unban')

  clearChat(owner)
  expectedQuit.add(GUEST)
  owner.chat('/ban JGuest 5m Login enforcement test')
  check(await waitChat(owner, /banned .*JGuest.*5m/i, 10000), '/ban records and kicks')
  await sleep(2500)
  expectedQuit.add(GUEST)
  const denied = makeBot(GUEST)
  check(await waitKickOrSpawn(denied, 20000) === 'kicked', 'active CoreMC ban blocks login')
  try { denied.quit() } catch { /* noop */ }
  clearChat(owner)
  owner.chat('/unban JGuest login enforcement clear')
  check(await waitChat(owner, /revoked active ban/i, 10000), '/unban clears offline target')
  await reconnectGuest('guest rejoins after offline unban')

  clearChat(owner)
  expectedQuit.add(GUEST)
  owner.chat('/kick JGuest Journey kick')
  check(await waitChat(owner, /kicked .*JGuest/i, 10000), '/kick audits and removes an online player')
  await sleep(2500)
  await reconnectGuest('guest rejoins after kick')

  clearChat(owner)
  owner.chat('/history JGuest')
  check(await waitChat(owner, /MOD HISTORY/i, 10000), '/history displays CoreMC moderation records')
  check(await waitChat(owner, /T5|BAN|MUTE|KICK/i, 10000), '/history includes tier/direct punishment records')
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
  check(await waitUntil(() => owner.__coreSidebar, 5000), 'CoreMC sidebar appears on join')
  owner.chat('/hud')
  check(await waitChat(owner, /CoreMC sidebar hidden/i, 10000), '/hud hides the sidebar')
  check(await waitUntil(() => !owner.__coreSidebar, 5000), 'hidden sidebar is removed from player display')
  owner.chat('/hud')
  check(await waitChat(owner, /CoreMC sidebar enabled/i, 10000), '/hud restores the sidebar')
  check(await waitUntil(() => owner.__coreSidebar, 5000), 'sidebar display is restored')
  clearChat(owner)
  owner.chat('/event')
  check(await waitChat(owner, /CORE HOUR is live/i, 10000), '/event reports the active Core Hour')
  check(await waitChat(owner, /2x Island XP.*2x Slaying Money.*2x Omni-Tool XP/i, 10000),
    'event status lists all three configured rewards')
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
  check(win && slotJson(win, 20).includes('blocks'), 'blocks category at slot 20')
  if (win) {
    await click(owner, 20)
    await sleep(1200)
    const cat = owner.currentWindow
    check(cat && slotJson(cat, 10).includes('stone'), 'stone is first blocks entry')
    if (cat) {
      const before = await moneyOf(owner)
      clearChat(owner)
      await click(owner, 10)
      check(await waitChat(owner, /bought/i), 'buy confirms in chat')
      check(invHas(owner, 'stone'), 'stone delivered')
      const after = await moneyOf(owner)
      check(after === before - 8, 'buy debits exactly 8 money', `${before} -> ${after}`)
    }
    await closeWin(owner)
  }

  await rc(`minecraft:give ${OWNER} minecraft:bread 16`)
  await sleep(1000)
  check(invCount(owner, 'bread') >= 16, 'RCON bread grant arrives')
  win = await openWindow(owner, '/shop food')
  check(win && slotJson(win, 38).includes('bread'), 'bread renders in the first food page')
  if (win) {
    const before = await moneyOf(owner)
    clearChat(owner)
    await click(owner, 38, 1) // right-click = sell
    check(await waitChat(owner, /sold/i), 'sell confirms in chat')
    const after = await moneyOf(owner)
    check(after === before + 192, 'sell pays exactly 192 money (sell-boost 0)', `${before} -> ${after}`)
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
      check(after === before - 2500, 'border T1 costs exactly 2500 tokens', `${before} -> ${after}`)
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
    check(after === before - 5000, 'mining-boost T1 costs exactly 5000 tokens', `${before} -> ${after}`)
    await closeWin(owner)
  }

  // ------------------------------------------------ P4b companions
  phase('4b', '/companions: six earnable companions; unlock and summon Ore Sprite')
  win = await openWindow(owner, '/companions')
  check(win && win.inventoryStart === 54, '/companions opens 54-slot panel')
  const COMPANION_SLOTS = [10, 12, 14, 16, 29, 33]
  check(win && COMPANION_SLOTS.every((s) => slotType(win, s)), 'all six companions render')
  check(win && slotJson(win, 10).includes('ore sprite'), 'Ore Sprite leads the companion collection')
  if (win) {
    const before = await tokensOf(owner)
    clearChat(owner)
    await click(owner, 10)
    check(await waitChat(owner, /companion unlocked/i), 'companion unlock confirms')
    const after = await tokensOf(owner)
    check(after === before - 150, 'Ore Sprite costs exactly 150 Sky Tokens', `${before} -> ${after}`)
    check(slotJson(owner.currentWindow, 10).includes('summoned'), 'unlocked companion is immediately summoned')
    await closeWin(owner)
  }
  const follower = await waitUntil(() => Object.values(owner.entities).some((e) => e !== owner.entity
    && /armor_stand/i.test(String(e.name || ''))
    && e.position && e.position.distanceTo(owner.entity.position) < 4), 15000, 500)
  check(!!follower, 'summoned companion visibly follows the player')

  // ------------------------------------------------ P4c quests
  phase('4c', '/quests: three daily gameplay missions are assigned')
  win = await openWindow(owner, '/quests')
  check(win && win.inventoryStart === 54, '/quests opens 54-slot mission board')
  check(win && [20, 22, 24].every((s) => slotType(win, s)), 'three daily missions render')
  if (win) await closeWin(owner)

  // ------------------------------------------------ P5 spawners
  phase(5, '/spawners: zombie starter, 35 zombie kills unlock skeleton, buy, place, rewards')
  const LANES = [10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
    28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43]
  win = await openWindow(owner, '/spawners')
  check(win && win.inventoryStart === 54, '/spawners opens 54-slot panel')
  check(win && LANES.every((s) => slotType(win, s)), 'first 28 mob lanes render')
  if (win) {
    await click(owner, 50)
    await sleep(500)
    win = owner.currentWindow
    check(win && slotType(win, 10) && slotType(win, 11), 'second page renders final two mob lanes')
    check(win && slotJson(win, 49).includes('2') && slotJson(win, 49).includes('30'),
      'spawner pagination reports page 2 and 30 lanes')
    await click(owner, 48)
    await sleep(500)
    win = owner.currentWindow
  }
  const laneSlot = win ? findSlotByName(win, 'zombie', 10, 31) : -1
  check(laneSlot >= 0, 'zombie lane present', 'slot=' + laneSlot)
  check(win && laneSlot >= 0 && slotJson(win, laneSlot).includes('regular spawner'),
    'zombie entry is one regular spawner with no variants')
  check(win && laneSlot >= 0 && slotJson(win, laneSlot).includes('✔'),
    'starter zombie spawner is unlocked without kills')
  if (win) await closeWin(owner)

  const sword = owner.inventory.items().find((i) => i.name === 'iron_sword')
  if (sword) await owner.equip(sword, 'hand')
  await rc(`effect give ${OWNER} minecraft:regeneration infinite 1 true`)
  await rc(`effect give ${OWNER} minecraft:saturation infinite 1 true`)
  await rc(`effect give ${OWNER} minecraft:strength infinite 1 true`)
  clearChat(owner)
  let kills = 0
  let died = false
  for (let i = 0; i < 35; i++) {
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
  check(!died, 'owner survives the 35-kill progression grind')
  check(kills === 35, '35 zombies summoned and slain', `kills=${kills}`)
  check(await waitChat(owner, /SPAWNER UNLOCKED.*Skeleton/i, 20000), 'zombie kills unlock skeleton spawner')

  win = await openWindow(owner, '/spawners')
  const lane2 = win ? findSlotByName(win, 'zombie', 10, 31) : -1
  if (win && lane2 >= 0) {
    check(slotJson(win, lane2).includes('✔'), 'unlocked affordable spawner uses the green tick state')
    const before = await tokensOf(owner)
    clearChat(owner)
    await click(owner, lane2)
    check(await waitChat(owner, /purchased/i), 'direct spawner purchase confirms')
    const after = await tokensOf(owner)
    check(after === before - 5, 'regular zombie spawner costs exactly 5 tokens', `${before} -> ${after}`)
    check(invHas(owner, 'spawner', 'zombie spawner'), 'regular spawner item delivered')
    await closeWin(owner)
  } else {
    bad('zombie spawner remains visible after unlock')
  }

  // ---- hostile GUI interactions on a LOCKED spider entry
  win = await openWindow(owner, '/spawners')
  if (win) {
    const skel = findSlotByName(win, 'spider', 10, 31)
    check(skel >= 0, 'spider lane present for hostile-GUI test', 'slot=' + skel)
    if (skel >= 0) {
      const sub = owner.currentWindow
      check(sub && slotJson(sub, skel).includes('✖'), 'skeleton spawner is visibly locked')
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
          [skel, 0, 1], [skel, 0, 2], [skel, 1, 2], [skel, 2, 2],
          [skel, 0, 4], [skel, 0, 3], [skel, 0, 6], [skel, 1, 0],
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
        check(slotJson(sub, skel).includes('✖'), 'locked spawner entry cannot be taken')
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

  // place the regular spawner and verify a spawner-born kill pays rewards
  let t1cell = null
  for (let r = 1; r <= 3 && !t1cell; r++) {
    for (const [dx, dz] of [[r, 0], [-r, 0], [0, r], [0, -r]]) {
      if (solidAt(owner, HX + dx, GY, HZ + dz) && airAbove(owner, HX + dx, GY, HZ + dz)) {
        t1cell = { x: HX + dx, y: GY, z: HZ + dz }
        break
      }
    }
  }
  check(!!t1cell, 'found a free platform cell for the regular spawner')
  let t1Placed = false
  if (t1cell) {
    const item = owner.inventory.items().find((i) => i.name === 'spawner'
      && /zombie spawner/i.test(JSON.stringify(i)))
    if (item) {
      await owner.equip(item, 'hand')
      try {
        await owner.placeBlock(owner.blockAt(v3(t1cell.x, t1cell.y, t1cell.z)), v3(0, 1, 0))
        await sleep(800)
        const b = owner.blockAt(v3(t1cell.x, t1cell.y + 1, t1cell.z))
        t1Placed = !!b && b.name === 'spawner'
      } catch (e) { log('[spawner] place failed: ' + e.message) }
    }
    check(t1Placed, 'regular spawner places on the island')
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

  // Recover the regular spawner with the owner's Omni-Tool pickaxe.
  const omniPick = owner.inventory.items().find((i) => i.name === 'netherite_pickaxe')
  if (omniPick && t1cell) {
    await owner.equip(omniPick, 'hand')
    for (let attempt = 0; attempt < 3; attempt++) {
      await rc(`execute in ${DIM} run kill @e[type=zombie]`)
      let b = owner.blockAt(v3(t1cell.x, t1cell.y + 1, t1cell.z))
      if (!b || b.name === 'air') break
      const sx = t1cell.x - Math.sign(t1cell.x - HX)
      const sz = t1cell.z - Math.sign(t1cell.z - HZ)
      await walkTo(owner, sx + 0.5, sz + 0.5)
      const ready = await waitBlockReady(owner, t1cell.x, t1cell.y + 1, t1cell.z, true, 10000)
      if (!ready) continue
      try {
        b = owner.blockAt(v3(t1cell.x, t1cell.y + 1, t1cell.z))
        if (b && b.name === 'spawner') await Promise.race([owner.dig(b), sleep(15000)])
      } catch (e) { log('[spawner] dig failed: ' + e.message) }
      await sleep(600)
    }
  }
  await rc(`execute in ${DIM} run kill @e[type=zombie]`)
  await sleep(1000)
  // ------------------------------------------------ P6 enchants + keys
  phase(6, 'direct OmniTool enchant menu -> treasure-miner -> mine 2 -> 2 sky keys')
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
    check(!!panel, 'shift-right-click opens the OmniTool enchant menu directly')
    ewin = panel
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
  phase(9, '/gens: 24 generators render; buy cobble gen, place, harvest')
  win = await openWindow(owner, '/gens')
  check(win && win.inventoryStart === 54, '/gens opens 54-slot market')
  const GEN_SLOTS = [10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23,
    24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39]
  check(win && GEN_SLOTS.every((s) => slotType(win, s) !== null), 'all 24 generators render')
  check(win && slotJson(win, 10).includes('cobble'), 'cobble gen leads the market')
  if (win) {
    const before = await creditsOf(owner)
    clearChat(owner)
    await click(owner, 10)
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

  // ------------------------------------------------ P10b animated skins
  phase('10b', '/skins: menus, grant hooks, apply + hat overlay')
  // Everything below runs WITHOUT the resource pack: menu entries fall
  // back to their vanilla materials (netherite_pickaxe / carved_pumpkin),
  // which is exactly the decline-the-pack contract.
  win = await openWindow(owner, '/skins')
  check(win && win.inventoryStart === 54, '/skins opens a 54-slot menu')
  check(win && slotType(win, 0) === 'netherite_pickaxe', 'Tool Skins tab renders')
  check(win && slotType(win, 1) === 'carved_pumpkin', 'Hats tab renders')
  check(win && slotType(win, 9) === 'name_tag'
    && slotType(win, 10) !== null && slotType(win, 14) !== null, 'collection filters render')
  check(win && slotType(win, 19) === 'iron_pickaxe', 'role filter row renders')
  check(win && slotType(win, 28) === 'netherite_pickaxe'
    && slotJson(win, 28).includes('emberforge'), 'grid leads with the Emberforge Miner skin (vanilla fallback)')
  check(win && slotType(win, 47) === 'item_frame', 'preview slot starts empty')
  // locked path: preview works, apply refuses
  await click(owner, 28)
  let grid = owner.currentWindow
  check(grid && slotType(grid, 47) === 'netherite_pickaxe', 'selecting a locked skin still previews it')
  clearChat(owner)
  await click(owner, 49)
  check(await waitChat(owner, /do not own/i), 'apply refuses a locked skin')
  // paging: 30 tool skins over 3 pages
  await click(owner, 46)
  grid = owner.currentWindow
  check(grid && slotJson(grid, 28).includes('astral'), 'next page reaches the Astral collection')
  await click(owner, 45)
  // grant hook from console (the same hook crates/events/store call)
  await rc(`skins grant ${OWNER} emberforge_miner`)
  await sleep(1500)
  clearChat(owner)
  owner.chat('/skins list')
  check(await waitChat(owner, /emberforge: ✔miner/i, 15000), 'grant hook: miner skin shows owned')
  check(await waitChat(owner, /hats: ✘ember_crown/i), 'hats start locked (nothing auto-granted)')
  // apply + ownership persistence across the GUI
  win = await openWindow(owner, '/skins')
  check(win && slotJson(win, 28).includes('emberforge'), 'skins menu reopens after grants')
  clearChat(owner)
  await click(owner, 28)
  await click(owner, 49)
  check(await waitChat(owner, /skin applied/i), 'apply equips the owned miner skin')
  clearChat(owner)
  await click(owner, 51)
  check(await waitChat(owner, /skin removed|back to the default/i), 'reset returns to the default look')
  clearChat(owner)
  await click(owner, 28)
  await click(owner, 49)
  check(await waitChat(owner, /skin applied/i), 're-apply for the restart check')
  // hats section: locked wear refuses -> grant -> wear -> overlay entity
  await closeWin(owner)
  win = await openWindow(owner, '/skins')
  await click(owner, 1)
  await sleep(800)
  let hatsWin = owner.currentWindow
  check(hatsWin && hatsWin.inventoryStart === 54
    && slotType(hatsWin, 20) === 'carved_pumpkin'
    && slotJson(hatsWin, 20).includes('ember'), 'hats tab opens with the Ember Crown entry')
  if (hatsWin) {
    clearChat(owner)
    await click(owner, 20)
    await click(owner, 49)
    check(await waitChat(owner, /do not own/i), 'wearing a locked hat refuses cleanly')
  }
  await rc(`skins grant ${OWNER} ember_crown`) // first grant
  await sleep(1000)
  // idempotent repeat: ownership lands on the io thread, so the proof is
  // the /skins list state below, not the (async) console reply text
  await rc(`skins grant ${OWNER} ember_crown`)
  await sleep(1000)
  clearChat(owner)
  owner.chat('/skins list')
  check(await waitChat(owner, /hats: ✔ember_crown/i, 15000), 'grant hook: hat shows owned')
  win = await openWindow(owner, '/skins')
  await click(owner, 1)
  await sleep(800)
  hatsWin = owner.currentWindow
  if (hatsWin) {
    clearChat(owner)
    await click(owner, 20)
    await click(owner, 49)
    check(await waitChat(owner, /now wearing/i), 'wear puts on the Ember Crown')
    const overlay = await waitUntil(() => {
      return Object.values(owner.entities).some((e) => e !== owner.entity
        && /display/i.test(String(e.name || ''))
        && e.position && e.position.distanceTo(owner.entity.position) < 4)
    }, 20000, 500)
    check(!!overlay, 'hat overlay entity rides the player (helmet slot untouched)')
    clearChat(owner)
    await click(owner, 51)
    check(await waitChat(owner, /hat removed/i), 'remove takes the hat off')
    const gone = await waitUntil(() => {
      return !Object.values(owner.entities).some((e) => e !== owner.entity
        && /display/i.test(String(e.name || ''))
        && e.position && e.position.distanceTo(owner.entity.position) < 4)
    }, 20000, 500)
    check(!!gone, 'overlay entity is cleaned up on remove')
    // wear it again and keep it on for the restart persistence check
    clearChat(owner)
    await click(owner, 20)
    await click(owner, 49)
    check(await waitChat(owner, /now wearing/i), 're-wear keeps the hat for the restart check')
  }
  await closeWin(owner)
  // ------------------------------------------------ P10b chat cosmetics
  phase('10b', 'chat cosmetics: /tags, /chatcolour, <RANK> <TAG> Player: Message')
  guest = makeBot(GUEST)
  check(await waitSpawn(guest), 'guest rejoins for the chat phase')
  await waitUntil(() => guest.__world !== null, 30000)
  await sleep(1500)

  // --- /tags GUI: locked state before any grant
  win = await openWindow(owner, '/tags')
  check(win && win.inventoryStart === 54, '/tags opens a 54-slot panel')
  check(win && slotJson(win, 10).includes('grinder'), 'first tag (grinder) renders at slot 10')
  check(win && slotJson(win, 10).includes('locked'), 'unowned tag shows the LOCKED state')
  check(win && slotJson(win, 49).includes('clear tag'), 'clear-selection button renders')
  const tagSlots = [10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33]
  check(win && tagSlots.every((s) => slotType(win, s) !== null), 'all 20 tags render on one page')
  if (win) await closeWin(owner)

  clearChat(owner)
  owner.chat('/tags select grinder')
  check(await waitChat(owner, /not unlocked/i, 15000), 'locked tag cannot be selected')

  // --- staff grant (console) then select
  clearChat(owner)
  const grantOut = await rc('tags grant JOwner grinder')
  check(/granted tag/i.test(String(grantOut)), 'console /tags grant confirms', String(grantOut).slice(0, 120))
  check(await waitChat(owner, /tag unlocked/i, 15000), 'player is told the tag unlocked')
  clearChat(owner)
  owner.chat('/tags select grinder')
  check(await waitChat(owner, /equipped/i, 15000), 'granted tag equips')

  // --- the layout a SECOND player sees.
  //
  // mineflayer renders the SIGNED content of player chat and ignores the
  // plugin-rendered 'unsignedChatContent', so the rendered line is asserted
  // on the server console (a real chat viewer) and on the raw packet that
  // vanilla clients actually display.
  clearChat(guest)
  owner.chat('hello from the grind')
  check(!!await waitServerLog(/\[GRINDER\] JOwner: hello from the grind/, 20000),
    'rendered line is <TAG> Player: Message')
  check(!!await waitChat(guest, /hello from the grind/, 15000), 'second player receives the message')
  check(chatCount(guest, 'hello from the grind') === 1, 'message is delivered exactly once (no double chat)',
    `count=${chatCount(guest, 'hello from the grind')}`)
  check(serverLogCount('hello from the grind') === 1, 'server logs the chat line exactly once',
    `count=${serverLogCount('hello from the grind')}`)
  const taggedRaw = rawWith(guest, 'hello from the grind')
  check(/GRINDER/.test(taggedRaw), 'clients receive the tag in the rendered component',
    taggedRaw.slice(0, 220))

  // --- rank prefix first: op the owner so the configured rank matches
  await rc('op JOwner')
  await rc('coremc reload')
  await sleep(1500)
  clearChat(guest)
  owner.chat('ranked line')
  check(!!await waitServerLog(/\[OWNER\] \[GRINDER\] JOwner: ranked line/, 20000),
    'exact <RANK> <TAG> Player: Message ordering')

  // --- /chatcolour GUI + gradient + bold
  win = await openWindow(owner, '/chatcolour')
  check(win && win.inventoryStart === 54, '/chatcolour opens a 54-slot panel')
  check(win && slotJson(win, 10).includes('white'), 'solid colours render (white at slot 10)')
  check(win && [10, 11, 12, 13, 14, 15, 16, 17].every((s) => slotType(win, s) !== null),
    'all eight solid colours render')
  check(win && [29, 30, 31, 32, 33].every((s) => slotType(win, s) !== null),
    'all five gradients render')
  check(win && slotJson(win, 29).includes('sun'), 'sunset gradient renders first')
  check(win && slotJson(win, 22).includes('preview'), 'preview item renders')
  check(win && slotJson(win, 48).includes('bold'), 'bold toggle renders')
  check(win && slotJson(win, 50).includes('reset'), 'reset button renders')
  if (win) await closeWin(owner)

  clearChat(owner)
  owner.chat('/chatcolour set sunset')
  check(await waitChat(owner, /selected/i, 15000), 'gradient style selects')
  clearChat(guest)
  const unicodeLine = 'gradient unicode \u2713 test ok'
  owner.chat(unicodeLine)
  check(!!await waitServerLog(/JOwner: gradient unicode \u2713 test ok/, 20000),
    'gradient message keeps punctuation and Unicode intact')
  const gradientRaw = rawWith(guest, 'gradient unicode')
  check(/#[0-9a-f]{6}/i.test(gradientRaw), 'gradient renders real hex colours to clients',
    gradientRaw.slice(0, 260))
  check(!gradientRaw.includes('minimessage') && !/<\/?[a-z_]+>/.test(gradientRaw),
    'no MiniMessage markup leaks into chat')

  clearChat(owner)
  owner.chat('/chatcolour bold')
  check(await waitChat(owner, /bold is now/i, 15000), 'bold toggles')
  clearChat(guest)
  owner.chat('bolded gradient')
  check(!!await waitServerLog(/JOwner: bolded gradient/, 20000), 'bolded message is rendered')
  check(/bold/i.test(rawWith(guest, 'bolded gradient')), 'bold reaches the client component',
    rawWith(guest, 'bolded gradient').slice(0, 220))
  clearChat(owner)
  owner.chat('/chatcolour reset')
  check(await waitChat(owner, /reset/i, 15000), 'chat style resets')

  // --- '&' injection is stripped for players without coremc.chat.format
  clearChat(owner)
  guest.chat('&cred &kobf attempt')
  check(!!await waitServerLog(/JGuest: red obf attempt/, 20000),
    "players cannot inject '&' formatting codes (codes stripped from the body)")
  const injectedRaw = rawWith(owner, 'obf attempt')
  check(!/"obfuscated":\s*(true|1|"1")/.test(injectedRaw), 'players cannot inject obfuscation',
    injectedRaw.slice(0, 220))
  check(!/"color":\s*"red"/.test(injectedRaw), 'players cannot inject colours', injectedRaw.slice(0, 220))

  // --- clearing the tag leaves no double space
  clearChat(owner)
  owner.chat('/tags clear')
  check(await waitChat(owner, /cleared/i, 15000), '/tags clear confirms')
  clearChat(guest)
  owner.chat('plain line here')
  const plain = await waitServerLog(/\[OWNER\] JOwner: plain line here/, 20000)
  check(!!plain, 'no tag renders cleanly with no leftover space')
  check(!!plain && !/ {2}/.test(String(plain[0])), 'no double space in the rendered line', String(plain))

  // --- staff check / revoke
  const checkOut = await rc('tags check JOwner')
  check(/grinder/i.test(String(checkOut)), 'console /tags check lists owned tags', String(checkOut).slice(0, 160))
  const revokeOut = await rc('tags revoke JOwner grinder')
  check(/revoked|nothing/i.test(String(revokeOut)), 'console /tags revoke answers', String(revokeOut).slice(0, 160))

  // --- re-grant the cosmetics that phase 11 asserts persist
  await rc('tags grant JOwner grinder')
  await rc('chatcolour grant JOwner sunset')
  await sleep(500)
  clearChat(owner)
  owner.chat('/tags select grinder')
  await waitChat(owner, /equipped/i, 15000)
  clearChat(owner)
  owner.chat('/chatcolour set sunset')
  await waitChat(owner, /selected/i, 15000)

  await rc('deop JOwner')
  await rc('coremc reload')
  quitBot(guest, GUEST)
  await sleep(1500)

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
  win = await openWindow(owner, '/companions')
  check(win && slotJson(win, 10).includes('summoned'), 'companion ownership and summon state persist after restart')
  if (win) await closeWin(owner)
  const hatBack = await waitUntil(() => {
    return Object.values(owner.entities).some((e) => e !== owner.entity
      && /display/i.test(String(e.name || ''))
      && e.position && e.position.distanceTo(owner.entity.position) < 4)
  }, 30000, 500)
  check(!!hatBack, 'hat overlay re-applies after the restart rejoin')
  const m2 = await moneyOf(owner)
  const c2 = await creditsOf(owner)
  const t2 = await tokensOf(owner)
  check(m2 === snapMoney && c2 === snapCredits && t2 === snapTokens,
    'all three balances persist exactly', `m ${snapMoney}->${m2}, c ${snapCredits}->${c2}, t ${snapTokens}->${t2}`)
  clearChat(owner)
  owner.chat('/is info')
  check(await waitChat(owner, /JOwner/i, 15000), '/is info shows the owner')

  // cosmetics survive the restart and still render
  win = await openWindow(owner, '/tags')
  check(win && slotJson(win, 10).includes('selected'), 'tag selection persists across restart')
  if (win) await closeWin(owner)
  clearChat(owner)
  owner.chat('after restart')
  check(!!await waitServerLog(/\[GRINDER\] JOwner: after restart/, 20000),
    'tag still renders in chat after restart')

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
    check(zk === 35, 'profile: 35 zombie progression kills; spawner kills are tracked separately',
      `zombie=${zk}`)
    const spawnerKills = (profile.stats && profile.stats['spawner-mobs-killed']) || 0
    check(spawnerKills >= 1, 'profile: spawner-mobs-killed stat recorded', String(spawnerKills))
    const pity = (profile.stats && profile.stats['crate-pity:sky']) || 0
    check(pity === 0, 'profile: sky pity counter reset by payout', `pity=${pity}`)
    const ownedTags = profile['owned-tags'] || []
    check(ownedTags.includes('grinder'), 'profile: tag ownership stored by stable id',
      JSON.stringify(ownedTags))
    check(profile['equipped-tag'] === 'grinder', 'profile: equipped tag stored by stable id',
      String(profile['equipped-tag']))
    check(profile['chat-color'] === 'sunset', 'profile: chat style stored by stable id',
      String(profile['chat-color']))
    check((profile['owned-chat-styles'] || []).includes('sunset'),
      'profile: chat style ownership persisted', JSON.stringify(profile['owned-chat-styles']))
    const profileRaw = fs.readFileSync(path.join(PLUGIN_DIR, 'profiles', `${uuid}.yml`), 'utf8')
    check(!profileRaw.includes('\u00a7') && !profileRaw.includes('#ff5555'),
      'profile: no rendered colour output is ever persisted')
    check(profile['schema-version'] === 8, 'profile: schema migrated to v8',
      String(profile['schema-version']))
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
    const ownedSkins = profile['owned-skins'] || []
    check(ownedSkins.includes('emberforge_miner') && ownedSkins.includes('ember_crown'),
      'profile: skin ownership persists (tool skin + hat)', JSON.stringify(ownedSkins))
    const equippedSkin = profile['equipped-tool-skins'] && profile['equipped-tool-skins'].miner
    check(equippedSkin === 'emberforge_miner', 'profile: miner tool skin stays equipped',
      String(equippedSkin))
    check(profile['equipped-hat'] === 'ember_crown', 'profile: equipped hat persists',
      String(profile['equipped-hat']))
    check(profile.companions && profile.companions['ore-sprite'],
      'profile: companion ownership and progression persist')
    check(profile['equipped-companion'] === 'ore-sprite',
      'profile: equipped companion persists by stable id', String(profile['equipped-companion']))
    check(Array.isArray(profile['daily-quests']) && profile['daily-quests'].length === 3,
      'profile: three daily mission assignments persist', JSON.stringify(profile['daily-quests']))
    check(typeof profile['quest-day'] === 'string' && profile['quest-day'].length === 10,
      'profile: daily mission reset key persists', String(profile['quest-day']))
    check(!ownedSkins.includes('riftbound_universal'), 'profile: no phantom skins granted')
  }

  // ------------------------------------------------ P12 audit
  phase(12, 'full-session log audit')
  auditLog('session')

  // ------------------------------------------------ P13 moderation
  phase(13, 'staff moderation: vanish/spectate/cps/freeze/mute/ban/tier/history')
  await moderationJourney()
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
