// Three real localhost clients; Soak2 reconnects once after the snapshot fixture kick.
const path = require('node:path');
const mineflayer = require(path.resolve(process.argv[2], 'mineflayer'));
const {Vec3} = require(path.resolve(process.argv[2], 'vec3'));
const port = Number(process.argv[3]);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid local port');
const bots = new Set();
let stopping = false;
let rejoined = false;
function join(index) {
  if (stopping) return;
  const bot = mineflayer.createBot({host: '127.0.0.1', port, username: `Soak${index}`,
    auth: 'offline', version: '1.21.11', hideErrors: true});
  bots.add(bot);
  let spawned = false;
  const client = bot._client;
  const write = client.write.bind(client);
  client.write = (name, data) => {
    write(name, data);
    if (['position', 'position_look', 'look', 'flying'].includes(name)) {
      setImmediate(() => { if (!client.ended) write('tick_end', {}); });
    }
  };
  const timeout = setTimeout(() => {
    if (!spawned) { console.error(`${bot.username} did not spawn`); stop(1); }
  }, 45000);
  bot.on('spawn', () => { spawned = true; bot.physicsEnabled = false; clearTimeout(timeout); console.log(`${bot.username} spawned`); });
  bot.on('messagestr', async message => {
    const use = /^BOUNDARY_USE (-?\d+) (-?\d+) (-?\d+)$/.exec(message);
    if (!use || index !== 2) return;
    try {
      let target;
      for (let attempt = 0; attempt < 100; attempt++) {
        target = bot.blockAt(new Vec3(...use.slice(1).map(Number)));
        if (target && target.name === 'lever') break;
        await new Promise(resolve => setTimeout(resolve, 100));
      }
      if (!target) throw new Error('Boundary lever chunk not received');
      await bot.activateBlock(target);
      console.log(`Boundary lever used: ${use.slice(1).join(',')}`);
    } catch (error) { console.error(error.message); stop(1); }
  });
  bot.on('error', error => console.error(`${bot.username}: ${error.message}`));
  bot.on('kicked', reason => console.log(`${bot.username} kicked: ${JSON.stringify(reason)}`));
  bot.on('end', () => {
    clearTimeout(timeout);
    bots.delete(bot);
    if (!stopping && index === 2 && spawned && !rejoined) {
      rejoined = true;
      setTimeout(() => join(2), 10000);
    } else if (!stopping && !spawned) stop(1);
  });
}
function stop(code = 0) {
  if (stopping) return;
  stopping = true;
  for (const bot of bots) bot.quit();
  setTimeout(() => process.exit(code), 500);
}
for (let i = 0; i < 3; i++) join(i);
process.on('SIGTERM', () => stop());
process.on('SIGINT', () => stop());
setTimeout(() => stop(1), 600000).unref();
