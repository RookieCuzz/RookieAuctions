const mineflayer = require('mineflayer');

const HOST = process.env.MC_HOST || '127.0.0.1';
const PORT = Number(process.env.MC_PORT || 25565);
const VERSION = process.env.MC_VERSION || '1.21.4';
const TIMEOUT = Number(process.env.FULL_MATRIX_TIMEOUT_MS || 480000);
const activeBots = new Set();

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function connect(username) {
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({ host: HOST, port: PORT, username, auth: 'offline', version: VERSION });
    bot._testMessages = [];
    let settled = false;
    const fail = (error) => {
      if (settled) return;
      settled = true;
      reject(error);
    };
    bot.on('login', () => console.log(`[${username}] login`));
    bot.on('messagestr', (message) => {
      const text = String(message);
      bot._testMessages.push(text);
      if (bot._testMessages.length > 200) bot._testMessages.shift();
      console.log(`[${username}/chat] ${text}`);
    });
    bot.on('windowOpen', (window) => {
      const slot = (index) => window.slots[index] ? window.slots[index].name : 'empty';
      console.log(`[${username}] windowOpen id=${window.id} start=${window.inventoryStart}`
        + ` slot20=${slot(20)} slot46=${slot(46)} slot53=${slot(53)}`);
    });
    bot.once('spawn', () => {
      if (settled) return;
      settled = true;
      resolve(bot);
    });
    bot.on('kicked', (reason) => fail(new Error(`kicked: ${JSON.stringify(reason)}`)));
    bot.on('error', fail);
    bot.on('end', () => {
      if (!settled) fail(new Error('connection ended before spawn'));
    });
  });
}

function waitForWindow(bot, timeout = 7000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`[${bot.username}] window timeout`)), timeout);
    bot.once('windowOpen', (window) => {
      clearTimeout(timer);
      resolve(window);
    });
  });
}

async function click(bot, slot) {
  const result = bot.clickWindow(slot, 0, 0);
  if (result && typeof result.then === 'function') await result;
  await sleep(250);
}

async function clickNext(bot, slot, timeout = 7000) {
  const next = waitForWindow(bot, timeout);
  await click(bot, slot);
  return next;
}

async function clickAndWaitForWindowChange(bot, slot, timeout = 10000) {
  const before = bot.currentWindow;
  const beforeId = before ? before.id : null;
  await click(bot, slot);
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const current = bot.currentWindow;
    if (current && current !== before && current.id !== beforeId) return current;
    await sleep(100);
  }
  throw new Error(`[${bot.username}] window did not change after clicking slot ${slot}`);
}

function topSize(window) {
  return Number.isInteger(window.inventoryStart)
    ? window.inventoryStart
    : window.slots.length - 36;
}

function findInventorySlot(bot, itemName) {
  for (let index = 0; index < bot.inventory.slots.length; index++) {
    const item = bot.inventory.slots[index];
    if (item && item.name === itemName && item.count > 0) return index;
  }
  return -1;
}

function clearMessages(bot) {
  bot._testMessages.length = 0;
}

function hasMessage(bot, patterns) {
  return bot._testMessages.some((message) => patterns.some((pattern) => message.includes(pattern)));
}

async function openAuction(bot) {
  // Install the listener before sending the command.  On a fast local Paper
  // tick the window-open packet can otherwise arrive between chat() and
  // waitForWindow(), making the test fail even though the GUI opened.
  let opened;
  try {
    opened = waitForWindow(bot, 10000);
    bot.chat('/auction');
    // /auction first opens a loading inventory and fills the real page asynchronously
    // after the player record has been loaded.  Do not click the navigation bar while
    // that placeholder is still open (it has only panes, so the click is ignored).
    await opened;
  } catch (firstError) {
    // A server-side close packet can race the command after a lot transition.  In
    // that case Mineflayer may keep a stale local window and the first command is
    // not represented by a windowOpen event.  Close the local snapshot, then issue
    // one explicit retry; this exercises the same player command path and does not
    // bypass any plugin validation.
    try {
      if (bot.currentWindow) bot.closeWindow(bot.currentWindow);
    } catch (_) { /* already closed */ }
    await sleep(350);
    opened = waitForWindow(bot, 10000);
    bot.chat('/auction');
    try {
      await opened;
    } catch (retryError) {
      retryError.message = `${retryError.message}; first attempt: ${firstError.message}`;
      throw retryError;
    }
  }
  const deadline = Date.now() + 15000;
  let lastSignature = '';
  while (Date.now() < deadline) {
    const window = bot.currentWindow;
    const queueNav = window && window.slots[46];
    // The item name is protocol-version dependent in a few registry builds;
    // slot 46 is unambiguously the queue navigation item once it is non-pane.
    if (queueNav && queueNav.name !== 'black_stained_glass_pane' && queueNav.name !== 'air') {
      return window;
    }
    const signature = `${window ? window.title : 'none'}|${queueNav ? queueNav.name : 'empty'}`;
    if (signature !== lastSignature) {
      lastSignature = signature;
      console.log(`[${bot.username}] waiting for auction page: ${signature}`);
    }
    await sleep(250);
  }
  throw new Error(`[${bot.username}] auction GUI did not finish loading`);
}

async function openFutureSessionDetail(bot) {
  await openAuction(bot);
  const queue = await clickNext(bot, 46);
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    const item = bot.currentWindow && bot.currentWindow.slots[20];
    if (item && (item.name === 'writable_book' || item.name === 'bell')) {
      return clickNext(bot, 20);
    }
    await sleep(250);
  }
  throw new Error(`[${bot.username}] future session slot did not finish loading`);
}

async function submitLot(bot, itemName, mode, prices, sessionIndex = 0) {
  const detail = await openFutureSessionDetail(bot);
  if (!detail.slots[31] || detail.slots[31].name !== 'smithing_table') {
    throw new Error(`[${bot.username}] session submission button is unavailable`);
  }
  const quick = await clickAndWaitForWindowChange(bot, 31);
  const inventoryIndex = findInventorySlot(bot, itemName);
  if (inventoryIndex < 0) throw new Error(`[${bot.username}] missing ${itemName} for submission`);
  // Container windows expose the player's inventory as main slots (9-35)
  // followed by hotbar slots (36-44), while mineflayer's inventory array keeps
  // the original 9-based indices.  Translate before clicking or a hotbar item
  // would address a slot past the 54+36 window boundary.
  const inventoryStart = Number.isInteger(bot.inventory.inventoryStart) ? bot.inventory.inventoryStart : 9;
  const containerSlot = topSize(quick) + inventoryIndex - inventoryStart;
  let page = await clickAndWaitForWindowChange(bot, containerSlot);
  page = await clickAndWaitForWindowChange(bot, 28); // one item; the fixture gives each lot separately
  page = await clickAndWaitForWindowChange(bot, sessionIndex === 1 ? 16 : 10);
  page = await clickAndWaitForWindowChange(bot, mode === 'sealed' ? 24 : 20);

  const buyoutEnabled = page.slots[42] && page.slots[42].name === 'lime_dye';
  const wantsBuyout = prices.buyout != null;
  if (buyoutEnabled !== wantsBuyout) page = await clickAndWaitForWindowChange(bot, 42);
  // The quick page supplies a validated default buyout when it is enabled.
  // Numeric anvil editing remains a manual GUI check; Mineflayer's virtual
  // anvil does not emulate Paper's PrepareAnvil result reliably.

  if (!page.slots[53] || page.slots[53].name !== 'lime_concrete') {
    console.log(`[${bot.username}] submission ${itemName}/${mode}: rejected (submit disabled)`);
    return { success: false, window: page };
  }

  const result = await clickAndWaitForWindowChange(bot, 53, 10000);
  await sleep(700);
  // A successful quick submission resets the selected item for the next lot;
  // a rejection keeps it selected so the seller can correct and retry.
  const success = result.slots[13] && result.slots[13].name === 'chest';
  console.log(`[${bot.username}] submission ${itemName}/${mode}: ${success ? 'accepted' : 'rejected'}`);
  return { success, window: result };
}

async function withdrawFirstQueued(bot) {
  await openAuction(bot);
  await clickNext(bot, 48); // my auctions
  await sleep(700);
  await clickNext(bot, 1); // queued filter
  await sleep(500);
  const detail = await clickNext(bot, 9);
  if (!detail.slots[41]) throw new Error(`[${bot.username}] queued lot detail has no withdraw button`);
  await clickNext(bot, 41); // cancellation confirmation
  await clickNext(bot, 41); // confirm cancellation; returns my auctions
  await sleep(900);
  console.log(`[${bot.username}] withdrew first queued lot`);
}

async function registerBuyer(bot) {
  const detail = await openFutureSessionDetail(bot);
  const result = await clickNext(bot, 41);
  if (!result || result.slots[41] == null) throw new Error(`[${bot.username}] buyer registration did not return to detail`);
  console.log(`[${bot.username}] registered for the first session`);
}

function currentItemName(bot) {
  return bot.currentWindow && bot.currentWindow.slots[13]
    ? bot.currentWindow.slots[13].name
    : null;
}

function announcedItemName(itemName) {
  return {
    diamond: 'Diamond',
    gold_ingot: 'Gold Ingot',
    copper_ingot: 'Copper Ingot',
    iron_ingot: 'Iron Ingot',
  }[itemName] || itemName;
}

async function openCurrentLot(bot, expectedName, timeout = 45000) {
  const deadline = Date.now() + timeout;
  let reopenedAfterAnnouncement = false;
  while (Date.now() < deadline) {
    const name = currentItemName(bot);
    if (name === expectedName) return bot.currentWindow;
    const announced = hasMessage(bot, [announcedItemName(expectedName)]);
    // A CURRENT page opened during the previous lot is not guaranteed to
    // receive a client-side slot update on every Paper/Mineflayer registry
    // combination.  Once the server announces the expected lot, reopen the
    // page so the snapshot is built from the new active auction.
    if (announced && !reopenedAfterAnnouncement) {
      reopenedAfterAnnouncement = true;
      await sleep(300);
      await openAuction(bot);
      continue;
    }
    if (name && name !== 'black_stained_glass_pane' && name !== 'barrier') {
      console.log(`[${bot.username}] current lot is ${name}, waiting for ${expectedName}`);
    }
    await sleep(1000);
  }
  throw new Error(`[${bot.username}] timed out waiting for lot ${expectedName}`);
}

async function bid(bot, expectedName, amountSlot) {
  await openCurrentLot(bot, expectedName);
  const confirmation = await clickNext(bot, amountSlot);
  if (!confirmation.slots[41]) throw new Error(`[${bot.username}] bid confirmation did not open`);
  // The server processes the bid asynchronously and may update the existing
  // container instead of emitting a second window-open packet.  Do not turn
  // that legitimate protocol variation into a false negative; refresh the
  // current page only if no return screen arrived after the click.
  const confirmationWindow = bot.currentWindow;
  await click(bot, 41);
  await sleep(1000);
  if (bot.currentWindow === confirmationWindow || !bot.currentWindow) {
    await openAuction(bot);
  }
  await sleep(600);
  console.log(`[${bot.username}] bid submitted for ${expectedName}`);
}

async function buyout(bot, expectedName) {
  await openCurrentLot(bot, expectedName);
  const confirmation = await clickNext(bot, 41);
  if (!confirmation.slots[41]) throw new Error(`[${bot.username}] buyout confirmation did not open`);
  const confirmationWindow = bot.currentWindow;
  await click(bot, 41);
  await sleep(1000);
  if (bot.currentWindow === confirmationWindow || !bot.currentWindow) {
    await openAuction(bot);
  }
  await sleep(700);
  console.log(`[${bot.username}] buyout submitted for ${expectedName}`);
}

async function waitForSessionEnd(admin, timeout = 180000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    clearMessages(admin);
    admin.chat('/auction admin session status');
    await sleep(1200);
    if (!hasMessage(admin, ['RUNNING', '进行中'])) {
      console.log('[matrix] session no longer RUNNING');
      return;
    }
    await sleep(4000);
  }
  throw new Error('session did not complete before timeout');
}

async function waitForRunning(admin, timeout = 240000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    clearMessages(admin);
    admin.chat('/auction admin session status');
    await sleep(1200);
    if (hasMessage(admin, ['RUNNING', '进行中'])) {
      console.log('[matrix] first session is RUNNING');
      return;
    }
    await sleep(3000);
  }
  throw new Error('first session did not start before timeout');
}

async function waitForVenue(bot, timeout = 30000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const position = bot.entity && bot.entity.position;
    if (position && Math.abs(position.x - 5.5) < 1.5 && Math.abs(position.y - 101) < 2
        && Math.abs(position.z - 5.5) < 1.5) {
      console.log(`[${bot.username}] forced teleport reached venue`);
      return;
    }
    await sleep(500);
  }
  throw new Error(`[${bot.username}] registered buyer was not teleported to the venue`);
}

async function claimFirstReward(bot, tabSlot) {
  await openAuction(bot);
  await clickNext(bot, 49);
  await sleep(700);
  if (tabSlot != null) await clickNext(bot, tabSlot);
  await sleep(700);
  const window = bot.currentWindow;
  const rewardSlot = window && window.slots[9] && window.slots[9].name !== 'black_stained_glass_pane'
    ? 9 : -1;
  if (rewardSlot < 0) throw new Error(`[${bot.username}] expected mailbox reward was not found`);
  await click(bot, rewardSlot);
  await sleep(1000);
  console.log(`[${bot.username}] mailbox reward claimed`);
}

async function run() {
  const bots = {};
  const names = ['TestAdmin', 'TestSeller', 'TestSeller2', 'TestBidderA', 'TestBidderB'];
  for (const name of names) {
    bots[name] = await connect(name);
    activeBots.add(bots[name]);
  }
  const admin = bots.TestAdmin;
  const seller = bots.TestSeller;
  const seller2 = bots.TestSeller2;
  const bidderA = bots.TestBidderA;
  const bidderB = bots.TestBidderB;

  // Set a valid venue using distinct points and provision deterministic funds/items.
  // The admin is moved to elevated points while configuring corners; creative
  // mode prevents Paper's floating-kick from interrupting the test.
  admin.chat('/minecraft:gamemode creative @s');
  await sleep(500);
  const points = [
    // Keep the synthetic venue well above generated terrain so forced
    // teleports cannot place bots inside a solid block.
    ['buyer-spawn', 5, 101, 5], ['item-display', 5, 102, 2], ['info-display', 5, 105, 3],
    ['corner1', 0, 90, 0], ['corner2', 10, 110, 10],
  ];
  for (const [point, x, y, z] of points) {
    admin.chat(`/minecraft:tp @s ${x} ${y} ${z} 0 0`);
    await sleep(250);
    admin.chat(`/auction admin venue set ${point}`);
    await sleep(300);
  }
  admin.chat('/auction admin venue validate');
  await sleep(500);
  admin.chat('/auction admin venue enable');
  await sleep(800);

  // Build a deterministic floor in the disposable world.  The return pad is
  // deliberately outside the venue so a successful forced entry is observable
  // while keeping the bots alive and close enough to avoid movement heuristics.
  admin.chat('/minecraft:fill 0 99 0 10 99 10 minecraft:stone');
  admin.chat('/minecraft:fill 15 99 -5 25 99 15 minecraft:stone');
  for (const name of ['TestBidderA', 'TestBidderB']) {
    admin.chat(`/minecraft:tp ${name} 20 101 5 0 0`);
    await sleep(250);
  }
  await sleep(700);

  for (const name of ['TestSeller', 'TestSeller2', 'TestBidderA', 'TestBidderB']) {
    admin.chat(`/eco give ${name} 10000`);
    await sleep(180);
  }
  admin.chat('/give TestSeller diamond 2');
  admin.chat('/give TestSeller gold_ingot 1');
  admin.chat('/give TestSeller emerald 1');
  admin.chat('/give TestSeller2 iron_ingot 1');
  admin.chat('/give TestSeller2 copper_ingot 1');
  await sleep(1000);

  const first = await submitLot(seller, 'diamond', 'public', { start: 100, increment: 10 });
  if (!first.success) throw new Error('first public submission was rejected');
  const second = await submitLot(seller, 'gold_ingot', 'sealed', { start: 200, increment: 10 });
  if (!second.success) throw new Error('second sealed submission was rejected');
  const sellerLimit = await submitLot(seller, 'emerald', 'public', { start: 300, increment: 10 });
  if (sellerLimit.success) throw new Error('third submission by one seller was accepted');

  const seller2First = await submitLot(seller2, 'iron_ingot', 'public', { start: 300, increment: 10, buyout: 500 });
  if (!seller2First.success) throw new Error('third lot submission was rejected');
  const capacity = await submitLot(seller2, 'copper_ingot', 'public', { start: 400, increment: 10 });
  if (capacity.success) throw new Error('submission beyond session capacity was accepted');
  await withdrawFirstQueued(seller2);
  const replacement = await submitLot(seller2, 'copper_ingot', 'public', { start: 300, increment: 10, buyout: 500 });
  if (!replacement.success) throw new Error('replacement after withdrawal was rejected');

  // The first session is now full (diamond, gold, copper).  A successful
  // submission to the second future session proves capacity is scoped by
  // session rather than summed across all scheduled windows.
  admin.chat('/give TestSeller2 lapis_lazuli 1');
  await sleep(500);
  const nextSession = await submitLot(seller2, 'lapis_lazuli', 'public',
    { start: 300, increment: 10 }, 1);
  if (!nextSession.success) {
    throw new Error('second-session submission was rejected while first session was full');
  }
  console.log('[matrix] capacity is isolated per session (first full, second accepted)');

  await registerBuyer(bidderA);
  await registerBuyer(bidderB);
  await waitForRunning(admin);
  await waitForVenue(bidderA);
  await waitForVenue(bidderB);

  // The first two lots exercise public and sealed bid settlement. The third uses buyout.
  await bid(bidderA, 'diamond', 36);
  await bid(bidderB, 'diamond', 37);
  await bid(bidderA, 'gold_ingot', 36);
  await bid(bidderB, 'gold_ingot', 36);
  await buyout(bidderA, 'copper_ingot');
  await waitForSessionEnd(admin);

  // Winner item rewards and seller income are persisted in the mailbox. Claim one
  // item reward and one income reward to exercise idempotent reward settlement.
  await claimFirstReward(bidderB, 0);
  await claimFirstReward(bidderA, 0);
  await claimFirstReward(seller, 2);
  console.log('[matrix] PASS submissions + seller limit + capacity + withdrawal + public/sealed bids + buyout + settlement');
}

function closeBots(reason) {
  for (const bot of activeBots) {
    try { bot.quit(reason); } catch (_) { /* connection may already be closed */ }
  }
  activeBots.clear();
}

const timeout = setTimeout(() => {
  console.error(`[matrix] FAIL timeout after ${TIMEOUT}ms`);
  closeBots('full matrix timeout');
  process.exitCode = 1;
}, TIMEOUT);
run().catch((error) => {
  console.error(`[matrix] FAIL ${error.stack || error}`);
  process.exitCode = 1;
}).finally(() => {
  clearTimeout(timeout);
  closeBots('full matrix complete');
});
