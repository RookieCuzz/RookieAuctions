const mineflayer = require('mineflayer');

const host = process.env.MC_HOST || '127.0.0.1';
const port = Number(process.env.MC_PORT || 25565);
const username = process.env.BOT_USERNAME || 'TestAdmin';
const version = process.env.MC_VERSION || '1.21.4';
const timeoutMs = Number(process.env.SMOKE_TIMEOUT_MS || 30000);

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function run() {
  return new Promise((resolve, reject) => {
    let finished = false;
    let sawWindow = false;
    let sawSessionMessage = false;
    const bot = mineflayer.createBot({ host, port, username, auth: 'offline', version });
    const timer = setTimeout(() => finish(new Error(`smoke timeout after ${timeoutMs}ms`)), timeoutMs);

    function finish(error) {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      if (error) reject(error);
      else resolve();
      if (bot && bot.player) bot.quit('smoke complete');
    }

    bot.on('login', () => console.log(`[mineflayer] login ${username} -> ${host}:${port}`));
    bot.on('spawn', async () => {
      try {
        await sleep(750);
        bot.chat('/auction admin session status');
        await sleep(650);
        const venuePoints = [
          ['buyer-spawn', 5, 71, 5],
          ['item-display', 5, 72, 2],
          ['info-display', 5, 75, 3],
          ['corner1', 0, 70, 0],
          ['corner2', 10, 80, 10],
        ];
        for (const [point, x, y, z] of venuePoints) {
          bot.chat(`/minecraft:tp @s ${x} ${y} ${z} 0 0`);
          await sleep(350);
          bot.chat(`/auction admin venue set ${point}`);
          await sleep(350);
        }
        bot.chat('/auction admin venue validate');
        await sleep(500);
        bot.chat('/auction admin venue preview');
        await sleep(500);
        bot.chat('/auction admin venue enable');
        await sleep(500);
        bot.chat('/auction admin session status');
        await sleep(700);
        bot.chat('/auction');
        await sleep(1200);
        if (!sawSessionMessage) throw new Error('did not observe session status output');
        if (!sawWindow) throw new Error('auction GUI did not open');
        console.log('[mineflayer] PASS session-status + venue commands + auction GUI');
        finish();
      } catch (error) {
        finish(error);
      }
    });
    bot.on('messagestr', (message) => {
      const text = String(message);
      console.log(`[chat] ${text}`);
      if (text.includes('场次') || text.includes('session') || text.includes('拍卖')) sawSessionMessage = true;
    });
    bot.on('windowOpen', (window) => {
      sawWindow = true;
      console.log(`[mineflayer] window opened title=${window.title || 'unknown'} slots=${window.slots.length}`);
      bot.closeWindow(window);
    });
    bot.on('kicked', (reason) => finish(new Error(`kicked: ${JSON.stringify(reason)}`)));
    bot.on('error', (error) => finish(error));
    bot.on('end', () => {
      if (!finished) finish(new Error('connection ended before smoke completed'));
    });
  });
}

run().catch((error) => {
  console.error(`[mineflayer] FAIL ${error.stack || error}`);
  process.exitCode = 1;
});
