#!/usr/bin/env node
// CoreMC Black Market journey — the live counterpart to the market
// unit tests. Boots a Paper server from THIS checkout and drives real
// players with mineflayer + RCON through the whole feature:
//
//   M1  boot: market closed board, /bm alias, /ah untouched
//   M2  the scheduled opening: 1 warning, 1 open broadcast, no spam
//   M3  the offers board: 6 offers, the spec's exact lore, ✖ locks
//   M4  unaffordable purchase fails loudly and debits nothing
//   M5  two clients contend for the final stock — exactly one wins
//   M6  per-player limits hold across rapid clicks
//   M7  full inventory: debit once, nothing dropped, /rewards catches up
//   M8  restart mid-window: same rotation, same stock, same limits
//   M9  Dark Auction: invalid/valid bids, anti-snipe extension,
//       leader goes broke + disconnects → fallback winner settles
//       exactly once, offline delivery on rejoin, next lot, session end
//   M10 admin stop mid-lot: cancelled, nobody charged; audit trail;
//       final log audit
//
// Run from journey-server/ (cwd is the server dir). Exit 0 = all green.
import mineflayer from 'mineflayer'
import { Rcon } from 'rcon-client'
import { spawn } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'

const ROOT = process.cwd()
const PLUGIN_DIR = path.join(ROOT, 'plugins', 'CoreMC')
const SERVER_LOG = path.join(ROOT, 'server.log')
const JOURNEY_LOG = path.join(ROOT, 'market-journey.log')
const RCON_CFG = { host: '127.0.0.1', port: 25575, password: 'journey123' }
const OWNER = 'JOwner'
const BUYER = 'JBuyer'
const GHOST = 'JGhost'

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
// mirrors CoreMC's SmallCaps table exactly (note f=ғ U+0493, q=ǫ U+01EB)
const SC = { a: 'ᴀ', b: 'ʙ', c: 'ᴄ', d: 'ᴅ', e: 'ᴇ', f: '\u0493', g: 'ɢ', h: 'ʜ', i: 'ɪ', j: 'ᴊ', k: 'ᴋ', l: 'ʟ', m: 'ᴍ', n: 'ɴ', o: 'ᴏ', p: 'ᴘ', q: '\u01EB', r: 'ʀ', s: 's', t: 'ᴛ', u: 'ᴜ', v: 'ᴠ', w: 'ᴡ', x: 'x', y: 'ʏ', z: 'ᴢ' }
const caps = (s) => s.toLowerCase().split('').map((ch) => SC[ch] ?? ch).join('')

function offlineUuid(name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name, 'utf8').digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const hex = h.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

// ---------------------------------------------------- journey market config
// Written BEFORE first boot: fixed prices (no bands) and exact pool
// counts make every rotation deterministic; the anchor offset is
// computed so the first scheduled opening lands ~3 minutes from now.
function writeMarketConfig() {
  const nowMinutes = Math.floor(Date.now() / 60000)
  const offset = (nowMinutes + 4) % 1440
  const yaml = `schedule:
  open-every-minutes: 1440
  open-for-minutes: 30
  anchor-offset-minutes: ${offset}
warnings:
  opening-minutes: 1
  closing-minutes: [5, 1]
selection:
  common-min: 3
  common-max: 3
  rare: 2
  epic: 1
  legendary-chance: 0
  cosmetic-chance: 0
  seasonal-chance: 0
history-limit: 100
offers:
  bait:
    pool: common
    reward: {type: item, id: special_bait, material: TROPICAL_FISH, display: "&bSpecial Bait", amount: 1}
    description: ["Irresistible to river fish."]
    price: {money: 50000}
    stock: 8
    per-player: 2
  resin:
    pool: common
    reward: {type: item, id: crafting_resin, material: HONEYCOMB, display: "&eCrafting Resin", amount: 1}
    description: ["A sticky crafting ingredient."]
    price: {money: 40000}
    stock: 10
    per-player: 3
  whale-bait:
    pool: common
    reward: {type: item, id: whale_bait, material: COD, display: "&9Whale Bait", amount: 1}
    description: ["For the truly wealthy."]
    price: {money: 100000000}
    stock: 5
    per-player: 1
  contested:
    pool: rare
    reward: {type: item, id: core_fragment, material: ECHO_SHARD, display: "&5Core Fragment", amount: 1}
    description: ["Only one in stock."]
    price: {money: 750000}
    stock: 1
    per-player: 1
  gated:
    pool: rare
    reward: {type: item, id: companion_material_rare, material: GOLDEN_CARROT, display: "&dCompanion Evolution Material", amount: 1}
    description: ["Requires a collection."]
    price: {money: 100000}
    stock: 5
    per-player: 1
    requirements: ["collection:mystic"]
  gen-core:
    pool: epic
    reward: {type: item, id: generator_core, material: LODESTONE, display: "&6Generator Core", amount: 1}
    description: ["A rare industrial", "progression material."]
    price: {money: 750000}
    stock: 5
    per-player: 1
auction:
  times: []
  lots-per-session-min: 2
  lots-per-session-max: 2
  lot-duration-seconds: 20
  break-seconds: 8
  anti-snipe:
    threshold-seconds: 10
    extension-seconds: 10
    max-extension-seconds: 20
  bid-buttons: ["add:10000", "add:50000", "percent:10"]
  lots:
    core-lot:
      rarity: epic
      reward: {type: item, id: generator_core, material: LODESTONE, display: "&6Generator Core", amount: 1}
      description: ["A rare industrial", "progression material."]
      starting-bid: 250000
      min-increment: 25000
    tag-lot:
      rarity: cosmetic
      reward: {type: item, id: tag_shadow, material: NAME_TAG, display: "&8Shadow Tag", amount: 1}
      description: ["A cosmetic chat tag."]
      starting-bid: 250000
      min-increment: 25000
`
  fs.mkdirSync(PLUGIN_DIR, { recursive: true })
  fs.writeFileSync(path.join(PLUGIN_DIR, 'black-market.yml'), yaml)
  fs.writeFileSync(path.join(PLUGIN_DIR, 'balances.yml'),
    `${offlineUuid(OWNER)}: 5000000\n${offlineUuid(BUYER)}: 2000000\n${offlineUuid(GHOST)}: 300000\n`)
  log(`[setup] black-market.yml written, first opening at offset minute ${offset}`)
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
  try { rcon.end() } catch { /* closed */ }
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
  await rc('difficulty peaceful')
}
async function coins(name) {
  const out = await rc('papicheck ' + name)
  const m = out.match(/PAPIRESULT \w+ [^|]*\|[^|]*\|[^|]*\|[^|]*\|[^|]*\|([\d,]+)\|/)
  return m ? parseInt(m[1].replace(/,/g, ''), 10) : null
}
/** PDC-tagged reward-material count via a /clear dry run. */
async function materialCount(name, material, id) {
  const out = await rc(`clear ${name} ${material}[custom_data~{PublicBukkitValues:{"coremc:coremc_store_material":"${id}"}}] 0`)
  const m = out.match(/Found (\d+) matching item/i)
  return m ? parseInt(m[1], 10) : 0
}
const dataYaml = () =>
  fs.existsSync(path.join(PLUGIN_DIR, 'black-market-data.yml'))
    ? fs.readFileSync(path.join(PLUGIN_DIR, 'black-market-data.yml'), 'utf8') : ''

// -------------------------------------------------------------- log audit
function auditLog(label) {
  const txt = fs.existsSync(SERVER_LOG) ? fs.readFileSync(SERVER_LOG, 'utf8') : ''
  const NOISE = /yggdrasil|versionfetcher|version information|no key layers|chunktaskscheduler|chunk wait|chunk holder|DO NOT REPORT THIS TO PAPER|java\.base@|net\.minecraft\./i
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
  bot.__bossBars = 0
  bot.on('messagestr', (m) => { bot.__chat.push(m); if (bot.__chat.length > 900) bot.__chat.shift() })
  bot.on('bossBarCreated', () => { bot.__bossBars++ })
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
  try { bot.quit() } catch { /* gone */ }
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
/** Finds the top-inventory slot whose item JSON contains a fragment. */
function findSlot(win, fragment) {
  for (let slot = 0; slot < 54; slot++) {
    if (slotJson(win, slot).includes(fragment)) return slot
  }
  return -1
}
async function click(bot, slot, button = 0) {
  try { await bot.clickWindow(slot, button, 0) } catch (e) { log('[click] ' + e.message) }
  await sleep(900)
}

// ------------------------------------------------------------------- main
let owner = null
let buyer = null
let ghost = null

async function main() {
  writeMarketConfig()
  await startServer('boot1')
  await connectRcon()
  await baseSetup()
  auditLog('boot1')

  // ------------------------------------------------- M1 closed board
  phase(1, 'closed board, /bm alias, /ah untouched')
  owner = makeBot(OWNER)
  check(await waitSpawn(owner), 'owner spawns')
  clearChat(owner)
  owner.chat('/ah')
  check(await waitChat(owner, /unknown( or incomplete)? command/i, 10000),
    '/ah is NOT a CoreMC command — the auction house is untouched', recentChat(owner))
  let win = await openWindow(owner, '/blackmarket')
  check(!!win, '/blackmarket opens')
  check(findSlot(win, caps('Market Closed')) >= 0, 'the closed board says so',
    slotJson(win, 20).slice(0, 160))
  check(findSlot(win, caps('Next opening')) >= 0, 'and shows the next opening time')
  check(findSlot(win, caps('The Black Market')) >= 0, 'and explains what this is')
  check(findSlot(win, caps('Recent Sales')) >= 0, 'and has the recent-sales board')
  await closeWin(owner)
  win = await openWindow(owner, '/bm')
  check(!!win && findSlot(win, caps('Market Closed')) >= 0, 'the /bm alias works')
  await closeWin(owner)
  const status = await rc('coremarket status')
  check(/closed, next opening in/i.test(status), '/coremarket status agrees', status)

  // ------------------------------------------------- M2 scheduled opening
  phase(2, 'the scheduled opening (wall clock, deduplicated announcements)')
  clearChat(owner)
  check(await waitChat(owner, /doors open in 1 minute/i, 240000),
    'the 1-minute opening warning arrives', recentChat(owner))
  check(await waitChat(owner, /doors are OPEN/i, 120000), 'the opening broadcast arrives')
  await sleep(3000)
  check(countChat(owner, /doors open in 1 minute/i) === 1, 'exactly ONE warning — no spam')
  check(countChat(owner, /doors are OPEN/i) === 1, 'exactly ONE opening broadcast')
  check(/rotation\s*:?\s*\n?\s*id: rot-/.test(dataYaml()) || dataYaml().includes('rot-'),
    'the rotation persisted with its grid id', dataYaml().slice(0, 120))

  // ------------------------------------------------- M3 the offers board
  phase(3, 'the offers board: 6 offers, spec lore, locks')
  win = await openWindow(owner, '/blackmarket')
  check(!!win, 'the open board renders')
  check(findSlot(win, caps('Market Open')) >= 0, 'the header says OPEN with the closing time')
  let offerSlots = 0
  for (let slot = 18; slot < 36; slot++) {
    if (slotJson(win, slot).includes(caps('Stock'))) offerSlots++
  }
  check(offerSlots === 6, 'exactly the configured 6 offers are on the board (5-7 rule)',
    String(offerSlots))
  const genSlot = findSlot(win, caps('Generator Core'))
  check(genSlot >= 0, 'the Generator Core offer is present')
  const genJson = slotJson(win, genSlot)
  check(genJson.includes(caps('Stock')) && genJson.includes('5/5'), 'lore: sᴛᴏᴄᴋ: 5/5',
    genJson.slice(0, 400))
  check(genJson.includes(caps('Limit')) && genJson.includes('1 ' + caps('per player')),
    'lore: ʟɪᴍɪᴛ: 1 ᴘᴇʀ ᴘʟᴀʏᴇʀ')
  check(genJson.includes(caps('Price')) && genJson.includes('$750,000')
    && genJson.includes('✔'), 'lore: ᴘʀɪᴄᴇ: $750,000 with the green ✔')
  check(genJson.includes(caps('Click to purchase')), 'lore: the click call to action')
  const whaleJson = slotJson(win, findSlot(win, caps('Whale Bait')))
  check(whaleJson.includes('✖'), 'an unaffordable offer wears the red ✖',
    whaleJson.slice(0, 300))
  const gatedJson = slotJson(win, findSlot(win, caps('Companion Evolution')))
  check(gatedJson.includes('✖') && gatedJson.includes(caps('Locked')),
    'a requirement-gated offer fails closed: red ✖ + ʟᴏᴄᴋᴇᴅ', gatedJson.slice(0, 400))
  check(gatedJson.includes(caps('Collection')), 'and names the missing collection')

  // ------------------------------------------------- M4 unaffordable
  phase(4, 'unaffordable purchase: loud failure, zero debit')
  const before = await coins(OWNER)
  clearChat(owner)
  await click(owner, findSlot(win, caps('Whale Bait')))
  check(await waitChat(owner, /cannot afford/i), 'the failure states itself', recentChat(owner))
  check(await coins(OWNER) === before, 'and debits nothing', `${before} -> ${await coins(OWNER)}`)
  const gatedBefore = await coins(OWNER)
  clearChat(owner)
  win = await waitWindow(owner)
  await click(owner, findSlot(win, caps('Companion Evolution')))
  check(await waitChat(owner, /requirements/i), 'a locked offer refuses with its reason')
  check(await coins(OWNER) === gatedBefore, 'and also debits nothing')

  // ------------------------------------------------- M5 last-stock contention
  phase(5, 'two clients contend for the final unit')
  buyer = makeBot(BUYER)
  check(await waitSpawn(buyer), 'the rival buyer joins')
  await sleep(2000)
  win = await openWindow(owner, '/blackmarket')
  const winB = await openWindow(buyer, '/blackmarket')
  const slotA = findSlot(win, caps('Core Fragment'))
  const slotB = findSlot(winB, caps('Core Fragment'))
  check(slotA >= 0 && slotB >= 0, 'both see the 1-stock Core Fragment')
  clearChat(owner)
  clearChat(buyer)
  // fire both clicks as close together as the protocol allows
  const clickA = owner.clickWindow(slotA, 0, 0).catch(() => {})
  const clickB = buyer.clickWindow(slotB, 0, 0).catch(() => {})
  await Promise.all([clickA, clickB])
  await sleep(2500)
  const aBought = countChat(owner, /Bought .*Core Fragment/i)
  const bBought = countChat(buyer, /Bought .*Core Fragment/i)
  check(aBought + bBought === 1, 'EXACTLY one of them bought the last unit',
    `owner=${aBought} buyer=${bBought} :: ${recentChat(owner)} :: ${recentChat(buyer)}`)
  check(countChat(owner, /Sold out/i) + countChat(buyer, /Sold out/i) === 1,
    'the other was told it sold out')
  check(await materialCount(OWNER, 'echo_shard', 'core_fragment')
    + await materialCount(BUYER, 'echo_shard', 'core_fragment') === 1,
  'exactly one Core Fragment item exists between them')
  check((await coins(OWNER)) + (await coins(BUYER)) === 5000000 + 2000000 - 750000,
    'exactly one price left the two balances combined')
  check(/stock-left: 0/.test(dataYaml()), 'the persisted stock hit 0',
    dataYaml().slice(0, 300))
  await closeWin(owner)
  await closeWin(buyer)
  quitBot(buyer, BUYER)

  // ------------------------------------------------- M6 per-player limit
  phase(6, 'per-player limits under rapid clicks')
  win = await openWindow(owner, '/blackmarket')
  const baitSlot = findSlot(win, caps('Special Bait'))
  clearChat(owner)
  await click(owner, baitSlot)
  win = await waitWindow(owner)
  await click(owner, findSlot(win, caps('Special Bait')))
  win = await waitWindow(owner)
  await click(owner, findSlot(win, caps('Special Bait')))
  await sleep(1500)
  check(countChat(owner, /Bought .*Special Bait/i) === 2, 'the limit of 2 allows exactly 2',
    recentChat(owner))
  check(countChat(owner, /already bought your limit of 2/i) >= 1,
    'the third click is refused with the limit')
  check(await materialCount(OWNER, 'tropical_fish', 'special_bait') === 2,
    'exactly two bait items delivered')
  await closeWin(owner)

  // ------------------------------------------------- M7 full inventory
  phase(7, 'full inventory: debit once, drop nothing, /rewards catches up')
  const emptySlots = owner.inventory.slots
    .slice(owner.inventory.inventoryStart, owner.inventory.inventoryEnd)
    .filter((s) => !s).length
  for (let i = 0; i < emptySlots; i++) await rc(`give ${OWNER} cobblestone 64`)
  await sleep(1200)
  const fullBefore = await coins(OWNER)
  win = await openWindow(owner, '/blackmarket')
  clearChat(owner)
  await click(owner, findSlot(win, caps('Crafting Resin')))
  check(await waitChat(owner, /Bought .*Crafting Resin/i), 'the purchase still succeeds')
  check(await waitChat(owner, /could not hold everything|\/rewards/i, 10000),
    'the reward is saved, never dropped', recentChat(owner))
  check(await coins(OWNER) === fullBefore - 40000, 'debited exactly once')
  check(await materialCount(OWNER, 'honeycomb', 'crafting_resin') === 0,
    'the resin is NOT in the full inventory')
  await closeWin(owner)
  await rc(`clear ${OWNER} cobblestone`)
  await sleep(800)
  clearChat(owner)
  owner.chat('/rewards')
  check(await waitChat(owner, /Delivered 1 pending reward/i), '/rewards delivers it')
  check(await materialCount(OWNER, 'honeycomb', 'crafting_resin') === 1,
    'the resin arrived exactly once')

  // ------------------------------------------------- M8 restart mid-window
  phase(8, 'restart mid-window: rotation, stock and limits survive')
  const rotBefore = (dataYaml().match(/id: (rot-\d+)/) || [])[1]
  check(!!rotBefore, 'the rotation id is persisted', dataYaml().slice(0, 120))
  const balPre = await coins(OWNER)
  quitBot(owner, OWNER)
  await sleep(1500)
  await stopServer()
  await startServer('boot2')
  await connectRcon()
  await baseSetup()
  auditLog('boot2')
  owner = makeBot(OWNER)
  check(await waitSpawn(owner), 'owner rejoins after the restart')
  await sleep(3000)
  const rotAfter = (dataYaml().match(/id: (rot-\d+)/) || [])[1]
  check(rotAfter === rotBefore, 'the SAME rotation survived — no duplicate roll',
    `${rotBefore} vs ${rotAfter}`)
  check(/stock-left: 0/.test(dataYaml()), 'the sold-out stock stayed sold out')
  check(await coins(OWNER) === balPre, 'the balance survived')
  win = await openWindow(owner, '/blackmarket')
  check(findSlot(win, caps('Market Open')) >= 0, 'the market is still open mid-window')
  const fragJson = slotJson(win, findSlot(win, caps('Core Fragment')))
  check(fragJson.includes('0/1') && fragJson.includes(caps('Sold out')),
    'the board still shows 0/1 sold out', fragJson.slice(0, 300))
  clearChat(owner)
  await click(owner, findSlot(win, caps('Special Bait')))
  check(await waitChat(owner, /already bought your limit/i),
    'per-player limits survived the restart', recentChat(owner))
  await closeWin(owner)

  // ------------------------------------------------- M9 dark auction
  phase(9, 'Dark Auction: bids, anti-snipe, broke+offline winner fallback')
  ghost = makeBot(GHOST)
  check(await waitSpawn(ghost), 'the third bidder joins')
  await sleep(2000)
  clearChat(owner)
  clearChat(ghost)
  const started = await rc('coremarket auction start')
  check(/session started/i.test(started), 'admin starts the session', started)
  check(await waitChat(owner, /A session begins/i, 15000), 'the session announces itself')
  const lotHit = await waitChat(owner, /Now up: .*starting at .*\$250,000.*20s/i, 15000)
  check(!!lotHit, 'lot 1 is called with item, rarity, bid and timer', recentChat(owner))
  const lotStart = Date.now()
  await sleep(1500)
  check(owner.__bossBars > 0 || ghost.__bossBars > 0,
    'a boss bar countdown appeared', `owner=${owner.__bossBars} ghost=${ghost.__bossBars}`)

  // invalid then valid bids
  clearChat(owner)
  owner.chat('/blackmarket bid 100')
  check(await waitChat(owner, /Too low .*\$250,000/i), 'a low bid names the next valid amount')
  owner.chat('/blackmarket bid 999m')
  check(await waitChat(owner, /cannot cover/i), 'an unbacked bid is refused')
  owner.chat('/blackmarket bid 250k')
  check(await waitChat(owner, /Bid placed: \$250,000/i), 'a valid bid lands (250k shorthand)')
  check(await coins(OWNER) === balPre, 'bidding charges NOTHING')

  // the auction GUI shows the live lot
  win = await openWindow(owner, '/darkauction')
  const lotJson2 = slotJson(win, 13)
  check(lotJson2.includes(caps('Current bid')) && lotJson2.includes('$250,000'),
    'the auction GUI shows the current bid', lotJson2.slice(0, 300))
  check(lotJson2.includes(caps('Next valid bid')) && lotJson2.includes('$275,000'),
    'and the next valid bid')
  check(findSlot(win, '+$10,000') >= 0 && findSlot(win, '+10%') >= 0,
    'the configured bid buttons render')
  await closeWin(owner)

  // anti-snipe: the ghost bids with ~4s left → +10s extension
  const ghostBefore = await coins(GHOST)
  await sleep(Math.max(0, lotStart + 15500 - Date.now()))
  clearChat(ghost)
  ghost.chat('/blackmarket bid 275000')
  check(await waitChat(ghost, /Bid placed: \$275,000/i), 'the snipe bid lands', recentChat(ghost))
  // the runner-up disconnects immediately — the settlement must
  // handle an OFFLINE fallback winner
  quitBot(owner, OWNER)
  // the leader goes broke: spends at the market below their own bid
  // (300k - 50k = 250k < the 275k bid)
  const gwin = await openWindow(ghost, '/blackmarket')
  await click(ghost, findSlot(gwin, caps('Special Bait')))
  await closeWin(ghost)
  check(await coins(GHOST) === ghostBefore - 50000,
    'the leader spent below their own bid', `${ghostBefore} -> ${await coins(GHOST)}`)
  // nominal end would be ~lotStart+20s: prove we are STILL live after it
  await sleep(Math.max(0, lotStart + 23000 - Date.now()))
  check(countChat(ghost, /sold to/i) === 0,
    'anti-snipe extended the lot past its nominal end', recentChat(ghost))
  check(await waitChat(ghost, /sold to JOwner .*\$250,000/i, 30000),
    'settlement fell back to the OFFLINE runner-up at THEIR bid', recentChat(ghost))
  await sleep(1500)
  check(await coins(GHOST) === ghostBefore - 50000,
    'the broke leader was never charged for the lot')
  check(countChat(ghost, /sold to/i) === 1, 'settled exactly once')

  // the offline winner pays and receives on rejoin
  owner = makeBot(OWNER)
  check(await waitSpawn(owner), 'the winner rejoins')
  check(await waitChat(owner, /Delivered 1 pending reward/i, 30000),
    'the won lot delivers on login', recentChat(owner))
  check(await coins(OWNER) === balPre - 250000, 'the winner paid exactly the winning bid',
    `${balPre} -> ${await coins(OWNER)}`)
  check(await materialCount(OWNER, 'lodestone', 'generator_core') >= 1,
    'the Generator Core is real and PDC-tagged')

  // lot 2 runs and, unbid, cancels cleanly; then the session ends
  clearChat(ghost)
  check(await waitChat(ghost, /Now up:/i, 30000), 'the next lot follows after the break')
  check(await waitChat(ghost, /found no valid buyer/i, 40000),
    'an unbid lot cancels with nobody charged')
  check(await waitChat(ghost, /session is over/i, 20000), 'the session ends cleanly')

  // ------------------------------------------------- M10 admin stop + audit
  phase(10, 'admin stop mid-lot, audit trail, final checks')
  clearChat(ghost)
  const restarted = await rc('coremarket auction start')
  check(/session started/i.test(restarted), 'a second session can start (no overlap leak)')
  check(await waitChat(ghost, /Now up:/i, 20000), 'its first lot opens')
  const ghostFinal = await coins(GHOST)
  ghost.chat('/blackmarket bid 250000')
  await waitChat(ghost, /Bid placed/i, 10000)
  const stopped = await rc('coremarket auction stop')
  check(/session stopped/i.test(stopped), 'admin stops the session mid-lot', stopped)
  await sleep(1500)
  check(await coins(GHOST) === ghostFinal, 'the cancelled lot charged NOBODY')
  check(/no session/i.test(await rc('coremarket status')), 'status shows no session')
  clearChat(owner)
  owner.chat('/ah')
  check(await waitChat(owner, /unknown( or incomplete)? command/i, 10000),
    '/ah is still untouched at the end')

  const txnLog = fs.readFileSync(path.join(PLUGIN_DIR, 'store-transactions.log'), 'utf8')
  for (const action of ['MARKET_BUY', 'AUCTION_WIN', 'AUCTION_PASS', 'AUCTION_CANCELLED',
    'ADMIN_MARKET']) {
    check(txnLog.includes(action), `the audit log records ${action}`)
  }
  check((txnLog.match(/MARKET_BUY/g) || []).length === 5,
    '5 purchases audited: contested + 2 bait + resin + ghost bait — exactly as executed',
    String((txnLog.match(/MARKET_BUY/g) || []).length))
  check(dataYaml().includes('sales:') && dataYaml().includes('lots:'),
    'sale and lot history persisted for support/balancing')

  quitBot(owner, OWNER)
  quitBot(ghost, GHOST)
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
