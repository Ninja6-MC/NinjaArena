// Eight actual offline protocol clients, restricted to the local harness server.
const path = require('node:path');
const mineflayer = require(path.resolve(process.argv[2], 'mineflayer'));
const port = Number(process.argv[3]);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid local port');
const bots = [];
for (let i = 0; i < 8; i++) {
  const bot = mineflayer.createBot({host: '127.0.0.1', port, username: `Soak${i}`,
    auth: 'offline', version: '1.21.11', hideErrors: true});
  bots.push(bot);
  let spawned = false;
  const client = bot._client;
  const write = client.write.bind(client);
  client.write = (name, data) => {
    write(name, data);
    if (['position', 'position_look', 'look', 'flying'].includes(name)) {
      setImmediate(() => { if (!client.ended) write('tick_end', {}); });
    }
  };
  bot.on('spawn', () => { spawned = true; console.log(`${bot.username} spawned`); });
  bot.on('kicked', reason => {
    console.error(`${bot.username || `Soak${i}`} kicked: ${JSON.stringify(reason)}`);
    if (!spawned) process.exit(1);
  });
  bot.on('error', error => console.error(`${bot.username}: ${error.message}`));
}
const stop = () => { for (const bot of bots) bot.quit(); setTimeout(() => process.exit(), 500); };
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
const deadlineSeconds = Number(process.argv[4]);
if (!Number.isInteger(deadlineSeconds) || deadlineSeconds < 120 || deadlineSeconds > 2160) {
  throw new Error("Invalid client deadline");
}
setTimeout(stop, deadlineSeconds * 1000).unref();
