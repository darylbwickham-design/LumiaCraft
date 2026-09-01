const assert = require('node:assert/strict');
const net = require('node:net');
const { WebSocketServer } = require('ws');
const manifest = require('../manifest.json');
const LumiaCraftPlugin = require('../main.js');

const port = 38932;
let chatPort = 0;
const received = [];
const alerts = [];
let chatClient = null;
const chatServer = new WebSocketServer({ host: '127.0.0.1', port: 0 });
chatServer.on('connection', (socket) => { chatClient = socket; });
const server = net.createServer((socket) => {
  let buffer = '';
  socket.on('data', (chunk) => {
    buffer += chunk.toString('utf8');
    let newline;
    while ((newline = buffer.indexOf('\n')) >= 0) {
      const request = JSON.parse(buffer.slice(0, newline));
      buffer = buffer.slice(newline + 1);
      if (request.type === 'hello') {
        socket.write(`${JSON.stringify({ id: request.id, ok: true, type: 'hello', protocol: 1, modVersion: '0.2.6', minecraftVersion: '1.21.1', players: 1, eventsPublished: 2, eventsSupported: true })}\n`);
        socket.write(`${JSON.stringify({
          type: 'event',
          event: 'effect_applied',
          sequence: 1,
          timestamp: Date.now(),
          data: { player: 'TestPlayer', health: 17, maxHealth: 20, effect: 'minecraft:speed', effectName: 'Speed', amplifier: 1, level: 2, durationSeconds: 30 }
        })}\n`);
        socket.write(`${JSON.stringify({
          type: 'event',
          event: 'player_damage',
          sequence: 2,
          timestamp: Date.now(),
          data: { player: 'TestPlayer', previousHealth: 20.000001, health: 17.299999, maxHealth: 20.000001, absorption: 0.000001, amount: 2.700001, source: 'minecraft:fall' }
        })}\n`);
      } else if (request.type === 'ping') {
        socket.write(`${JSON.stringify({ id: request.id, ok: true, type: 'pong', players: 1, eventsPublished: 2 })}\n`);
      } else if (request.type === 'execute') {
        received.push(request.command);
        socket.write(`${JSON.stringify({ id: request.id, ok: true, type: 'result', command: request.command, result: 1 })}\n`);
      }
    }
  });
});

async function waitFor(predicate, timeoutMs = 3000) {
  const started = Date.now();
  while (!predicate()) {
    if (Date.now() - started > timeoutMs) throw new Error('Timed out waiting for smoke-test state');
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
}

async function main() {
  for (const alert of manifest.config.alerts) {
    assert.equal(
      alert.key.startsWith(`${manifest.id}-`),
      false,
      `Plugin alert key must stay local because Lumia adds the ${manifest.id}- namespace: ${alert.key}`
    );
  }

  if (!chatServer.address()) await new Promise((resolve) => chatServer.once('listening', resolve));
  chatPort = chatServer.address().port;
  await new Promise((resolve) => server.listen(port, '127.0.0.1', resolve));
  // An old saved false value must not be able to turn gameplay alerts into
  // Lumia's log-only/noProcess path.
  const settings = {
    host: '127.0.0.1', port, token: '', defaultTarget: '@p', maxActionsPerMinute: 30,
    allowRawCommands: false, dryRun: false, effectEvents: true, healthDecimalPlaces: 1,
    showEventsInEventList: false, relayStreamChat: true, lumiaApiToken: 'test-token',
    lumiaApiHost: '127.0.0.1', lumiaApiPort: chatPort, streamChatTarget: '@a',
    streamChatMaxLength: 200, streamChatMessagesPerMinute: 120
  };
  const variables = new Map();
  let connectionState = false;
  const lumia = {
    getSettings: () => settings,
    getConnectionState: () => connectionState,
    updateConnection: async (state) => { connectionState = state; },
    setVariable: async (key, value) => { variables.set(key, value); },
    triggerAlert: async (alert) => { alerts.push(alert); },
    log: async () => true
  };
  const plugin = new LumiaCraftPlugin(manifest, { plugin: manifest, lumia });
  try {
    await plugin.onload();
    await waitFor(() => connectionState);
    const result = await plugin.actions({ actions: [{
      type: 'summon_mob',
      value: { entity: 'minecraft:zombie', count: 2, target: '@p', cooldown: 0 }
    }] });
    assert.equal(result.newlyPassedVariables.lumiacraft_commands_run, 2);
    assert.deepEqual(received, [
      'execute at @p run summon minecraft:zombie ~ ~1 ~',
      'execute at @p run summon minecraft:zombie ~ ~1 ~'
    ]);
    await waitFor(() => variables.get('stream_chat_connected') === true && chatClient);
    const fallbackMessage = JSON.stringify({
      type: 'chat', origin: 'twitch', data: { id: 'chat-1', username: 'FallbackUser', message: 'hello\nworld' }
    });
    chatClient.send(fallbackMessage);
    chatClient.send(fallbackMessage);
    chatClient.send(JSON.stringify({
      type: 'chat', origin: 'twitch', data: { username: 'NoIdUser', message: 'same envelope four times' }
    }));
    chatClient.send(JSON.stringify({
      type: 'chat', origin: 'twitch', data: { username: 'NoIdUser', message: 'same envelope four times' }
    }));
    chatClient.send(JSON.stringify({
      type: 'chat', origin: 'twitch', data: { username: 'NoIdUser', message: 'same envelope four times' }
    }));
    chatClient.send(JSON.stringify({
      type: 'chat', origin: 'twitch', data: { username: 'NoIdUser', message: 'same envelope four times' }
    }));
    chatClient.send(JSON.stringify({
      type: 'chat', origin: 'youtube', data: {
        id: 'chat-2', username: 'account_name', displayname: 'Display Name', message: 'second message'
      }
    }));
    await waitFor(() => received.length === 5);
    assert.equal(received[2], 'tellraw @a {"text":"FallbackUser: hello world"}');
    assert.equal(received[3], 'tellraw @a {"text":"NoIdUser: same envelope four times"}');
    assert.equal(received[4], 'tellraw @a {"text":"Display Name: second message"}');
    assert.equal(variables.get('stream_chat_messages_relayed'), 3);
    assert.equal(variables.get('stream_chat_last_sender'), 'Display Name');
    assert.equal(variables.get('stream_chat_last_platform'), 'youtube');
    assert.equal(variables.get('minecraft_version'), '1.21.1');
    assert.equal(variables.get('connected'), true);
    await waitFor(() => alerts.length === 2);
    const effectAlert = alerts.find((alert) => alert.alert === 'effect_applied');
    const damageAlert = alerts.find((alert) => alert.alert === 'player_damage');
    assert.equal(Object.hasOwn(effectAlert, 'showInEventList'), false);
    assert.equal(Object.hasOwn(damageAlert, 'showInEventList'), false);
    assert.equal(Object.hasOwn(damageAlert, 'dynamic'), false);
    assert.equal(damageAlert.extraSettings.player, 'TestPlayer');
    assert.equal(Object.hasOwn(damageAlert.extraSettings, 'username'), false);
    assert.equal(Object.hasOwn(damageAlert.extraSettings, 'displayname'), false);
    assert.equal(Object.hasOwn(damageAlert.extraSettings, 'userId'), false);
    assert.equal(effectAlert.dynamic.value, 'minecraft:speed');
    assert.deepEqual(
      manifest.config.alerts.find((alert) => alert.key === 'effect_applied').variationConditions,
      [{
        type: 'EQUAL_SELECTION',
        description: 'Potion effect registry ID is exactly (for example minecraft:regeneration or a modded effect ID).',
        dynamicOptions: true,
        selections: []
      }]
    );
    assert.equal(effectAlert.extraSettings.health, 17);
    assert.equal(variables.get('last_effect'), 'minecraft:speed');
    assert.equal(damageAlert.extraSettings.health, 17.3);
    assert.equal(damageAlert.extraSettings.maxHealth, 20);
    assert.equal(damageAlert.extraSettings.amount, 2.7);
    assert.equal(damageAlert.extraSettings.healthPercent, 86.5);
    assert.equal(variables.get('player_health'), 17.3);
    assert.equal(variables.get('player_health_percent'), 86.5);
    assert.equal(variables.get('bridge_version'), '0.2.6');
    assert.equal(variables.get('bridge_events_supported'), true);
    assert.equal(variables.get('bridge_events_received'), 2);
    process.stdout.write('LumiaCraft protocol smoke test passed\n');
  } finally {
    await plugin.onunload();
    await new Promise((resolve) => server.close(resolve));
    for (const client of chatServer.clients) client.terminate();
    await new Promise((resolve) => chatServer.close(resolve));
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
  server.close();
  for (const client of chatServer.clients) client.terminate();
  chatServer.close();
});
