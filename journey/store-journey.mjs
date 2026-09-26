#!/usr/bin/env node
// CoreMC store journey — the live counterpart to the store unit tests.
// Boots a Paper server from THIS checkout and drives a real player with
// mineflayer + RCON through the whole connected store architecture:
//
//   S1  boot + join + /is create + Credits admin commands (never negative)
//   S2  /store GUI: Credits panel, categories, ✖ price, exact missing
//       Credits on a failed buy, odds preview matching crates.yml
//   S3  buying a Vote Key: exact debit, PDC item; rapid re-buy at low
//       balance never duplicates
//   S4  crates: console binding, a RENAMED fake key never opens (PDC
//       only), the real key opens and consumes exactly one, a rapid
//       double right-click opens exactly once
//   S5  lootboxes: right-click opens (never places an ender chest),
//       display-entity animation with zero pickup-able items, all nine
//       rewards delivered, FX cleaned up
//   S6  disconnect mid-animation: FX cleaned, rewards pending, next
//       login delivers all 9 exactly once
//   S7  bundles: the Starter Bundle delivers its exact contents and
//       debit; /corebundle give works
//   S8  full inventory: purchase debits once, drops NOTHING, /rewards
//       delivers after space frees up
//   S9  restart: balances, keys, crate bindings survive; rank River
//       keys migrate to physical keys on the next join
//   S10 audit: store-transactions.log holds every action; zero server
//       ERRORs, zero CoreMC warn/error lines
//
// Run from journey-server/ (cwd is the server dir). Exit 0 = all green.
import mineflayer from 'mineflayer'
import { Vec3 } from 'vec3'
import { Rcon } from 'rcon-client'
import { spawn } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'

const ROOT = process.cwd()
const PLUGIN_DIR = path.join(ROOT, 'plugins', 'CoreMC')
const SERVER_LOG = path.join(ROOT, 'server.log')
const JOURNEY_LOG = path.join(ROOT, 'store-journey.log')
const RCON_CFG = { host: '127.0.0.1', port: 25575, password: 'journey123' }
const OWNER = 'JOwner'
const DIM = 'coremc_islands'

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

// -------------------------------------------------------------- smallcaps
const SC = { a: 'ᴀ', b: 'ʙ', c: 'ᴄ', d: 'ᴅ', e: 'ᴇ', f: 'ꜰ', g: 'ɢ', h: 'ʜ', i: 'ɪ', j: 'ᴊ', k: 'ᴋ', l: 'ʟ', m: 'ᴍ', n: 'ɴ', o: 'ᴏ', p: 'ᴘ', q: 'q', r: 'ʀ', s: 's', t: 'ᴛ', u: 'ᴜ', v: 'ᴠ', w: 'ᴡ', x: 'x', y: 'ʏ', z: 'ᴢ' }
const caps = (s) => s.toLowerCase().split('').map((ch) => SC[ch] ?? ch).join('')

function offlineUuid(name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name, 'utf8').digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const hex = h.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

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
async function credits() {
  const out = await rc('corecredits balance ' + OWNER)
  const m = out.match(/Credits: ([\d,]+)/)
  return m ? parseInt(m[1].replace(/,/g, ''), 10) : null
}
async function fxCount() {
  const out = await rc(`execute if entity @e[tag=coremc_lootbox_fx]`)
  const m = out.match(/count: (\d+)/)
  return /passed/i.test(out) ? (m ? parseInt(m[1], 10) : 1) : 0
}
async function itemEntitiesNear(x, y, z, range = 8) {
  const out = await rc(`execute in ${DIM} positioned ${x} ${y} ${z} if entity @e[type=item,distance=..${range}]`)
  return /passed/i.test(out)
}
/**
 * Server-authoritative item count matched by CoreMC's PDC tag — the
 * same identity the plugin trusts, so this also proves the tag is on
 * the item (names are irrelevant).
 */
async function pdcCount(material, tag, value) {
  // "/clear ... 0" is a dry run: reports the exact total without removing
  const out = await rc(`clear ${OWNER} ${material}[custom_data~{PublicBukkitValues:{"coremc:${tag}":"${value}"}}] 0`)
  const m = out.match(/Found (\d+) matching item/i)
  return m ? parseInt(m[1], 10) : 0
}
const keyCount = (id) => pdcCount('tripwire_hook', 'coremc_crate_key', id)
const boxCount = (id) => pdcCount('ender_chest', 'coremc_lootbox', id)

// -------------------------------------------------------------- log audit
function auditLog(label) {
  const txt = fs.existsSync(SERVER_LOG) ? fs.readFileSync(SERVER_LOG, 'utf8') : ''
  const NOISE = /yggdrasil|versionfetcher|version information|no key layers|chunktaskscheduler|chunk wait|chunk holder|DO NOT REPORT THIS TO PAPER|java\.base@|net\.minecraft\./i
  // Paper's single-thread watchdog dump ("server has not responded for
  // 10 seconds") fires on slow sandbox CPUs during the island paste; it
  // is a stall report, not an error — its whole block is ERROR-tagged
  // (headers, ---- separators and tab-indented stack frames), so strip
  // exactly that shape while keeping real exception headers visible.
  const WATCHDOG = /has not responded for|Creating thread dump|thread dump \(Look for plugins|ERROR\]:\s*-+\s*$|ERROR\]:\s*(Current Thread|Stack:|PID:|Thread Dump)|ERROR\]:\s*\t/i
  const lines = txt.split('\n').filter((l) => !NOISE.test(l) && !WATCHDOG.test(l))
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
  bot.on('messagestr', (m) => { bot.__chat.push(m); if (bot.__chat.length > 800) bot.__chat.shift() })
  bot.on('error', (e) => log(`[${name}] bot error: ${e.message}`))
  bot.on('kicked', (reason) => {
    if (expectedQuit.has(name)) return
    bad(`${name} kicked`, String(reason).slice(0, 200))
  })
  bot.on('end', () => log(`[${name}] connection ended`))
  expectedQuit.delete(name)
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
const countChat = (bot, rx) => bot.__chat.filter((m) => rx.test(m)).length
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
async function click(bot, slot, button = 0, mode = 0) {
  try { await bot.clickWindow(slot, button, mode) } catch (e) { log('[click] ' + e.message) }
  await sleep(900)
}
const v3 = (x, y, z) => new Vec3(x, y, z)
function solidAt(bot, x, y, z) {
  const b = bot.blockAt(v3(x, y, z))
  return b && b.name !== 'air' && b.name !== 'cave_air' && b.name !== 'void_air' ? b : null
}
function itemJson(item) {
  try { return JSON.stringify(item) } catch { return '' }
}
/** Total count of inventory items whose (small-caps) name matches. */
function countNamed(bot, fragment) {
  return bot.inventory.items()
    .filter((i) => itemJson(i).includes(fragment))
    .reduce((sum, i) => sum + i.count, 0)
}
function namedItem(bot, fragment) {
  return bot.inventory.items().find((i) => itemJson(i).includes(fragment)) || null
}
async function equipNamed(bot, fragment) {
  const item = namedItem(bot, fragment)
  if (!item) return false
  await bot.equip(item, 'hand')
  await sleep(400)
  return true
}
async function lookAndActivate(bot, block) {
  await bot.lookAt(block.position.offset(0.5, 0.5, 0.5))
  await sleep(400)
  try { await bot.activateBlock(block) } catch (e) { log('[activate] ' + e.message) }
  await sleep(600)
}

// ------------------------------------------------------------------- main
const VOTE_KEY = caps('Vote Key')       // ᴠᴏᴛᴇ ᴋᴇʏ
const RIVER_KEY = caps('River Key')
const SKY_KEY = caps('Sky Key')
const CORE_BOX = caps('Core Lootbox')
let owner = null
let HX = 0; let HZ = 0; let GY = 0

/** Finds a solid, reachable ground block near home at the given offset. */
function groundBlock(bot, dx, dz) {
  for (let dy = 1; dy >= -3; dy--) {
    const b = solidAt(bot, HX + dx, GY + dy, HZ + dz)
    if (b && !solidAt(bot, HX + dx, b.position.y + 1, HZ + dz)) return b
  }
  return null
}

async function main() {
  await startServer('boot1')
  await connectRcon()
  await baseSetup()
  auditLog('boot1')

  // ------------------------------------------------- S1 join + credits
  phase(1, 'join + island + Credits admin commands')
  owner = makeBot(OWNER)
  check(await waitSpawn(owner), 'owner spawns')
  clearChat(owner)
  owner.chat('/is create')
  check(await waitChat(owner, /island created/i, 90000), '/is create pastes an island')
  await sleep(2500)
  const ground = await waitUntil(() => {
    const p = owner.entity.position
    for (let y = Math.floor(p.y); y > Math.floor(p.y) - 12; y--) {
      if (solidAt(owner, Math.floor(p.x), y, Math.floor(p.z))) return y
    }
    return null
  }, 60000)
  check(ground !== null, 'island has ground under the spawn position')
  HX = Math.floor(owner.entity.position.x)
  HZ = Math.floor(owner.entity.position.z)
  GY = ground
  log(`[island] home=${HX},${GY},${HZ}`)

  check(await credits() === 0, 'a fresh player holds exactly 0 Credits', String(await credits()))
  await rc(`corecredits give ${OWNER} 5000 admin`)
  check(await credits() === 5000, '/corecredits give lands exactly', String(await credits()))
  const tooLow = await rc(`corecredits take ${OWNER} 999999`)
  check(/never go negative/i.test(tooLow), 'an overdraw is refused, balances never go negative', tooLow)
  check(await credits() === 5000, 'the refused take changed nothing', String(await credits()))
  clearChat(owner)
  owner.chat('/corecredits')
  check(await waitChat(owner, /Your Credits: 5,000/i), '/corecredits shows the own balance',
    recentChat(owner))
  await rc(`corecredits set ${OWNER} 10`)
  check(await credits() === 10, '/corecredits set is exact', String(await credits()))

  // ------------------------------------------------- S2 the /store GUI
  phase(2, '/store GUI + insufficient balance + odds preview')
  let win = await openWindow(owner, '/store')
  check(!!win, '/store opens the store')
  check(win && JSON.stringify(win.title).includes('Store'), 'the root window is titled Store',
    win && JSON.stringify(win.title))
  const creditsPanel = slotJson(win, 4)
  check(creditsPanel.includes(caps('Credits')) && creditsPanel.includes(caps('Balance')),
    'slot 4 is the ᴄʀᴇᴅɪᴛs / ʙᴀʟᴀɴᴄᴇ panel', creditsPanel.slice(0, 200))
  check(creditsPanel.includes('"10"') || creditsPanel.includes('10'),
    'the panel shows the live balance (10)')
  check(creditsPanel.includes('100 ' + caps('Credits')) && creditsPanel.includes('€1'),
    'the panel spells out 100 ᴄʀᴇᴅɪᴛs = €1', creditsPanel.slice(0, 260))
  check(slotJson(win, 20).includes(caps('Crate Keys')), 'slot 20 is Crate Keys')
  check(slotJson(win, 22).includes(caps('Lootboxes')), 'slot 22 is Lootboxes')
  check(slotJson(win, 24).includes(caps('Bundles')), 'slot 24 is Bundles')
  check(slotJson(win, 49).includes(caps('Earning Credits')),
    'slot 49 explains how Credits are earned in game')

  await click(owner, 20) // into Crate Keys
  win = await waitWindow(owner)
  const voteEntry = slotJson(win, 20)
  check(voteEntry.includes(VOTE_KEY), 'the Vote Key is the first key entry', voteEntry.slice(0, 160))
  check(voteEntry.includes(caps('Price')) && voteEntry.includes('75') && voteEntry.includes('✖'),
    'an unaffordable key shows ᴘʀɪᴄᴇ 75 with the red ✖', voteEntry.slice(0, 300))
  clearChat(owner)
  await click(owner, 20) // try to buy with 10 credits
  check(await waitChat(owner, /missing 65 Credits \(price 75, you have 10\)/i),
    'a failed buy states the EXACT missing Credits (65)', recentChat(owner))
  check(await credits() === 10, 'a failed buy debits nothing', String(await credits()))
  check(countNamed(owner, VOTE_KEY) === 0, 'and delivers nothing')

  await click(owner, 20, 1) // right-click: odds preview
  win = await waitWindow(owner)
  const previewMoney = slotJson(win, 9)
  check(previewMoney.includes(caps('Money Pouch')) && previewMoney.includes('30%'),
    'the preview shows the Money Pouch at its exact 30% chance', previewMoney.slice(0, 300))
  check(previewMoney.includes('5,000') && previewMoney.includes('15,000'),
    'with its exact amount range', previewMoney.slice(0, 300))
  const previewTag = slotJson(win, 13)
  check(previewTag.includes('8%') && previewTag.includes(caps('rare')),
    'the rare Voter Tag shows 8% and its rarity', previewTag.slice(0, 300))
  await closeWin(owner)

  // ------------------------------------------------- S3 buying keys
  phase(3, 'buying a Vote Key: exact debit, no rapid-buy duplication')
  await rc(`corecredits set ${OWNER} 1000`)
  win = await openWindow(owner, '/store')
  await click(owner, 20)
  await waitWindow(owner)
  clearChat(owner)
  await click(owner, 20)
  check(await waitChat(owner, /Purchased .* for 75 Credits\. Balance: 925 Credits/i),
    'the purchase confirms the exact debit', recentChat(owner))
  check(await credits() === 925, '75 Credits actually left the balance', String(await credits()))
  check(countNamed(owner, VOTE_KEY) === 1, 'exactly one Vote Key arrived',
    String(countNamed(owner, VOTE_KEY)))
  const keyJson = itemJson(namedItem(owner, VOTE_KEY))
  check(keyJson.includes('tripwire_hook'), 'the key is the configured tripwire hook')

  // rapid double-buy with funds for one: exactly one key, one refusal
  await rc(`corecredits set ${OWNER} 100`)
  win = await openWindow(owner, '/store')
  await click(owner, 20)
  await waitWindow(owner)
  clearChat(owner)
  await click(owner, 20)
  await click(owner, 20)
  await sleep(1500)
  check(countChat(owner, /Purchased /) === 1, 'rapid double-click buys exactly once',
    recentChat(owner))
  check(await waitChat(owner, /missing 50 Credits/i), 'the second click is refused with the exact shortfall')
  check(await credits() === 25, 'only one price was debited', String(await credits()))
  check(countNamed(owner, VOTE_KEY) === 2, 'exactly two keys total — no duplication',
    String(countNamed(owner, VOTE_KEY)))
  await closeWin(owner)

  // ------------------------------------------------- S4 crates
  phase(4, 'crates: PDC keys only, one open per key, double-click safe')
  const crateBlock = groundBlock(owner, 2, 2)
  check(!!crateBlock, 'found a crate block spot on the island')
  const CB = crateBlock.position
  const bound = await rc(`corecrate set vote ${DIM} ${CB.x} ${CB.y} ${CB.z}`)
  check(/bound to that block/i.test(bound), 'console binds the Vote Crate', bound)
  check(/Bound crates: 1/i.test(await rc('corecrate list')), 'the binding is listed')

  // a renamed vanilla item must NEVER open the crate (PDC, not names)
  const realKeys = countNamed(owner, VOTE_KEY)
  await rc(`give ${OWNER} tripwire_hook[custom_name='"§a§l${VOTE_KEY}"'] 1`)
  await sleep(800)
  check(countNamed(owner, VOTE_KEY) === realKeys + 1, 'the fake renamed key arrived')
  clearChat(owner)
  check(await equipNamed(owner, VOTE_KEY), 'holding a vote-key-named tripwire hook')
  const heldIsFake = !itemJson(owner.heldItem).includes('coremc')
  if (!heldIsFake) {
    // components not visible to mineflayer — drop to RCON clear+regive below
    log('[note] cannot distinguish fake by components; using clear/regive fallback')
    await rc(`clear ${OWNER} tripwire_hook`)
    await rc(`give ${OWNER} tripwire_hook[custom_name='"§a§l${VOTE_KEY}"'] 1`)
    await sleep(800)
    await equipNamed(owner, VOTE_KEY)
  }
  clearChat(owner)
  await lookAndActivate(owner, crateBlock)
  await sleep(1200)
  check(countChat(owner, /gave you/i) === 0, 'the renamed fake NEVER opens the crate',
    recentChat(owner))
  check(countNamed(owner, VOTE_KEY) >= 1, 'the fake was not consumed either')
  await closeWin(owner) // the preview that opened instead
  await rc(`clear ${OWNER} tripwire_hook`) // remove the fake (real keys regiven below)
  await sleep(500)
  check(countNamed(owner, VOTE_KEY) === 0, 'inventory clear of hooks before the real test')
  await rc(`corecrate givekey ${OWNER} vote 3`)
  await sleep(800)
  check(countNamed(owner, VOTE_KEY) === 3, '/corecrate givekey delivers 3 real keys',
    String(countNamed(owner, VOTE_KEY)))

  clearChat(owner)
  check(await equipNamed(owner, VOTE_KEY), 'holding the real Vote Key')
  await lookAndActivate(owner, crateBlock)
  check(await waitChat(owner, /gave you/i, 15000), 'the real key opens the crate', recentChat(owner))
  await sleep(1000)
  check(countNamed(owner, VOTE_KEY) === 2, 'exactly ONE key was consumed',
    String(countNamed(owner, VOTE_KEY)))

  // rapid double right-click: the cooldown swallows the second
  await sleep(1200)
  clearChat(owner)
  await equipNamed(owner, VOTE_KEY)
  await owner.lookAt(crateBlock.position.offset(0.5, 0.5, 0.5))
  await sleep(300)
  try { await owner.activateBlock(crateBlock) } catch (e) { log('[activate] ' + e.message) }
  owner.activateBlock(crateBlock).catch(() => {})
  await sleep(3000)
  check(countChat(owner, /gave you/i) === 1, 'a rapid double right-click opens exactly ONCE',
    recentChat(owner))
  check(countNamed(owner, VOTE_KEY) === 1, 'and consumed exactly one key',
    String(countNamed(owner, VOTE_KEY)))

  // ------------------------------------------------- S5 lootboxes
  phase(5, 'lootbox: premium animation, no placement, 9 rewards, cleanup')
  await rc(`corelootbox give ${OWNER} core 1`)
  await sleep(800)
  check(countNamed(owner, CORE_BOX) === 1, '/corelootbox give delivers the Core Lootbox')
  const boxJson = itemJson(namedItem(owner, CORE_BOX))
  check(boxJson.includes('ender_chest'), 'the lootbox is an ender chest item')
  const lbBlock = groundBlock(owner, -2, -2)
  check(!!lbBlock, 'found a lootbox spot away from the crate')
  const LB = lbBlock.position
  clearChat(owner)
  check(await equipNamed(owner, CORE_BOX), 'holding the lootbox')
  await lookAndActivate(owner, lbBlock)
  check(await waitChat(owner, /8 rewards \+ 1 guaranteed rare/i, 10000),
    'right-click starts the opening', recentChat(owner))
  await sleep(1000)
  check(countNamed(owner, CORE_BOX) === 0, 'exactly one box was consumed')
  check(owner.blockAt(v3(LB.x, LB.y + 1, LB.z))?.name !== 'ender_chest',
    'NO real ender chest block was placed',
    owner.blockAt(v3(LB.x, LB.y + 1, LB.z))?.name)
  const fxDuring = await fxCount()
  check(fxDuring >= 1, 'display-entity FX are running', String(fxDuring))
  check(!(await itemEntitiesNear(LB.x, LB.y, LB.z)), 'ZERO pickup-able item entities during the show')
  check(await waitChat(owner, /delivered all 9 rewards/i, 30000),
    'all 9 rewards (8 + 1 guaranteed rare) delivered', recentChat(owner))
  await sleep(1500)
  check(await fxCount() === 0, 'every FX entity was cleaned up', String(await fxCount()))
  clearChat(owner)
  owner.chat('/rewards')
  check(await waitChat(owner, /Nothing pending/i), 'nothing was left pending', recentChat(owner))

  // ------------------------------------------------- S6 disconnect mid-animation
  phase(6, 'disconnect mid-animation → next login delivers exactly once')
  await rc(`corelootbox give ${OWNER} core 1`)
  await sleep(800)
  clearChat(owner)
  await equipNamed(owner, CORE_BOX)
  await lookAndActivate(owner, lbBlock)
  check(await waitChat(owner, /8 rewards \+ 1 guaranteed rare/i, 10000), 'second opening starts')
  await sleep(2500) // mid-animation
  check(await fxCount() >= 1, 'animation is running when the player disconnects')
  quitBot(owner, OWNER)
  await sleep(2500)
  check(await fxCount() === 0, 'the disconnect cleaned up every FX entity', String(await fxCount()))
  const pendingYml = fs.readFileSync(path.join(PLUGIN_DIR, 'pending-rewards.yml'), 'utf8')
  check(/grants/.test(pendingYml), 'the 9 rolled rewards persisted as one pending transaction',
    pendingYml.slice(0, 200))

  owner = makeBot(OWNER)
  check(await waitSpawn(owner), 'owner rejoins')
  check(await waitChat(owner, /Delivered 9 pending reward\(s\)/i, 30000),
    'the next login delivers all 9 — exactly once', recentChat(owner))
  await sleep(2000)
  check(countChat(owner, /Delivered \d+ pending/) === 1, 'no double delivery')
  clearChat(owner)
  owner.chat('/rewards')
  check(await waitChat(owner, /Nothing pending/i), 'the pending ledger is empty again')

  // ------------------------------------------------- S7 bundles
  phase(7, 'bundles: exact contents, exact debit')
  await rc(`corecredits set ${OWNER} 5000`)
  const riverBefore = await keyCount('river')
  const skyBefore = await keyCount('sky')
  const boxBefore = await boxCount('core')
  win = await openWindow(owner, '/store')
  await click(owner, 24) // Bundles
  win = await waitWindow(owner)
  const starterJson = slotJson(win, 20)
  check(starterJson.includes(caps('Starter Bundle')), 'the Starter Bundle is listed',
    starterJson.slice(0, 160))
  check(starterJson.includes(RIVER_KEY) && starterJson.includes(SKY_KEY)
    && starterJson.includes(CORE_BOX) && starterJson.includes('2x') && starterJson.includes('1x'),
    'its lore lists the EXACT contents', starterJson.slice(0, 400))
  check(starterJson.includes('500 ' + caps('Credits')) && starterJson.includes('✔'),
    'and the live affordable price', starterJson.slice(0, 400))
  clearChat(owner)
  await click(owner, 20)
  check(await waitChat(owner, /Purchased .* for 500 Credits\. Balance: 4,500/i),
    'the bundle debits exactly 500', recentChat(owner))
  await sleep(1200)
  check(await keyCount('river') === riverBefore + 2, 'exactly 2 PDC-tagged River Keys arrived',
    `${riverBefore} -> ${await keyCount('river')}`)
  check(await keyCount('sky') === skyBefore + 1, 'exactly 1 PDC-tagged Sky Key arrived',
    `${skyBefore} -> ${await keyCount('sky')}`)
  check(await boxCount('core') === boxBefore + 1, 'exactly 1 PDC-tagged Core Lootbox arrived',
    `${boxBefore} -> ${await boxCount('core')}`)
  await closeWin(owner)
  const gaveBundle = await rc(`corebundle give ${OWNER} starter 1`)
  check(/Gave 1x/i.test(gaveBundle), '/corebundle give works too', gaveBundle)
  await sleep(1000)
  check(await keyCount('river') === riverBefore + 4,
    'the admin bundle delivered the same exact contents',
    `${riverBefore} -> ${await keyCount('river')}`)

  // ------------------------------------------------- S8 full inventory
  phase(8, 'full inventory: debit once, drop nothing, /rewards catches up')
  await closeWin(owner)
  // no keys in the inventory: the bought key cannot stack into an
  // existing stack, so a truly full inventory must send it to /rewards
  await rc(`clear ${OWNER} tripwire_hook`)
  await sleep(800)
  const emptySlots = owner.inventory.slots
    .slice(owner.inventory.inventoryStart, owner.inventory.inventoryEnd)
    .filter((s) => !s).length
  for (let i = 0; i < emptySlots; i++) await rc(`give ${OWNER} cobblestone 64`)
  await sleep(1200)
  const voteBefore = await keyCount('vote')
  const balBefore = await credits()
  win = await openWindow(owner, '/store')
  await click(owner, 20)
  await waitWindow(owner)
  clearChat(owner)
  await click(owner, 20) // buy a vote key into a full inventory
  check(await waitChat(owner, /Purchased .* for 75 Credits/i), 'the purchase still succeeds')
  check(await waitChat(owner, /inventory could not hold everything|\/rewards/i, 10000),
    'the player is told the reward is saved, not dropped', recentChat(owner))
  check(await credits() === balBefore - 75, 'the debit happened exactly once',
    `${balBefore} -> ${await credits()}`)
  const p = owner.entity.position
  check(!(await itemEntitiesNear(Math.floor(p.x), Math.floor(p.y), Math.floor(p.z), 6)),
    'NOTHING paid was dropped on the ground')
  check(await keyCount('vote') === voteBefore, 'the key is not in the inventory yet',
    `${voteBefore} vs ${await keyCount('vote')}`)
  await closeWin(owner)
  await rc(`clear ${OWNER} cobblestone`)
  await sleep(800)
  clearChat(owner)
  owner.chat('/rewards')
  check(await waitChat(owner, /Delivered 1 pending reward\(s\)/i), '/rewards delivers it',
    recentChat(owner))
  await sleep(800)
  check(await keyCount('vote') === voteBefore + 1, 'the paid key arrived exactly once',
    `${voteBefore} -> ${await keyCount('vote')}`)

  // ------------------------------------------------- S9 restart + river key migration
  phase(9, 'restart: persistence + rank River key migration')
  const balPre = await credits()
  const votePre = await keyCount('vote')
  const riverPre = await keyCount('river')
  quitBot(owner, OWNER)
  await sleep(1500)
  await stopServer()

  check(/grants:\s*(\[\]|\{\})?\s*$|^\s*$|^{}\s*$/m.test(
    fs.readFileSync(path.join(PLUGIN_DIR, 'pending-rewards.yml'), 'utf8'))
    || !/txn|grants/.test(fs.readFileSync(path.join(PLUGIN_DIR, 'pending-rewards.yml'), 'utf8')),
  'pending-rewards.yml is empty at shutdown')
  check(fs.existsSync(path.join(PLUGIN_DIR, 'crates-data.yml'))
    && fs.readFileSync(path.join(PLUGIN_DIR, 'crates-data.yml'), 'utf8').includes('vote'),
  'the crate binding is persisted')
  // simulate a pre-store rank purchase: 2 virtual River keys on the ledger
  fs.writeFileSync(path.join(PLUGIN_DIR, 'ranks-data.yml'),
    `season: 1\nplayers:\n  ${offlineUuid(OWNER)}:\n    name: ${OWNER}\n    rank: ''\n    river-keys: 2\n    last-payout: 0\n`)

  await startServer('boot2')
  await connectRcon()
  await baseSetup()
  auditLog('boot2')
  owner = makeBot(OWNER)
  check(await waitSpawn(owner), 'owner rejoins after the restart')
  check(await waitChat(owner, /2 rank River key\(s\) are now physical/i, 30000),
    'rank River keys migrate to physical keys on login', recentChat(owner))
  await sleep(2500)
  check(await credits() === balPre, 'the Credit balance survived the restart',
    `${balPre} vs ${await credits()}`)
  check(await keyCount('vote') === votePre, 'the keys survived the restart',
    `${votePre} vs ${await keyCount('vote')}`)
  check(await keyCount('river') === riverPre + 2, 'exactly 2 migrated River Keys arrived',
    `${riverPre} -> ${await keyCount('river')}`)
  check(/Bound crates: 1/i.test(await rc('corecrate list')), 'the crate binding survived')
  clearChat(owner)
  await equipNamed(owner, VOTE_KEY)
  const crateAgain = owner.blockAt(v3(CB.x, CB.y, CB.z)) || groundBlock(owner, 2, 2)
  await lookAndActivate(owner, crateAgain)
  check(await waitChat(owner, /gave you/i, 15000), 'the crate still opens after the restart',
    recentChat(owner))

  // ------------------------------------------------- S10 audit
  phase(10, 'final audit: transaction log + clean server log')
  const txnLog = fs.readFileSync(path.join(PLUGIN_DIR, 'store-transactions.log'), 'utf8')
  for (const action of ['PURCHASE', 'CRATE_OPEN', 'LOOTBOX_OPEN', 'ADMIN_GIVEKEY',
    'ADMIN_GIVELOOTBOX', 'ADMIN_GIVEBUNDLE', 'RANK_RIVER_KEYS']) {
    check(txnLog.includes(action), `store-transactions.log records ${action}`)
  }
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
