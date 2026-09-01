const { Plugin } = require('@lumiastream/plugin');
const net = require('node:net');
const crypto = require('node:crypto');
const WebSocket = require('ws');

const PROTOCOL = 1;
const MAX_RECONNECT_ATTEMPTS = 5;
const REQUEST_TIMEOUT_MS = 8000;
const KEEPALIVE_MS = 15000;
const DEFAULT_LUMIA_API_PORT = 39231;
const IDENTIFIER = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/i;
const USERNAME = /^[a-z0-9_]{1,16}$/i;
const SIMPLE_TARGET = /^@(a|p|r)$/;
const HEALTH_FIELDS = ['health', 'previousHealth', 'maxHealth', 'absorption', 'previousAbsorption', 'effectiveHealth', 'amount', 'originalAmount', 'blockedAmount'];
const EVENT_ALERTS = {
  player_damage: { group: 'health' },
  player_heal: { group: 'health' },
  player_death: { group: 'health' },
  player_respawn: { group: 'health' },
  effect_applied: { group: 'effect', variationValue: (data) => data.effect },
  effect_removed: { group: 'effect', variationValue: (data) => data.effect },
  effect_expired: { group: 'effect', variationValue: (data) => data.effect },
  player_join: { group: 'lifecycle' },
  player_leave: { group: 'lifecycle' },
  dimension_change: { group: 'lifecycle' },
  advancement: { group: 'progression' },
  item_crafted: { group: 'item' },
  item_smelted: { group: 'item' }
};

function clamp(value, fallback, min, max) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.max(min, Math.min(max, Math.round(parsed))) : fallback;
}

function roundMetric(value, decimalPlaces) {
  const number = Number(value);
  if (!Number.isFinite(number)) return undefined;
  const factor = 10 ** decimalPlaces;
  return Math.round((number + Number.EPSILON) * factor) / factor;
}

function normalizeHealthData(data, decimalPlaces) {
  const normalized = { ...data };
  for (const field of HEALTH_FIELDS) {
    const rounded = roundMetric(normalized[field], decimalPlaces);
    if (rounded !== undefined) normalized[field] = rounded;
  }

  if (Number.isFinite(normalized.maxHealth)) normalized.maxHealth = Math.max(0, normalized.maxHealth);
  if (Number.isFinite(normalized.health)) {
    normalized.health = Math.max(0, normalized.health);
    if (Number.isFinite(normalized.maxHealth) && normalized.maxHealth > 0) {
      normalized.health = Math.min(normalized.health, normalized.maxHealth);
    }
  }
  for (const field of ['absorption', 'previousAbsorption', 'amount', 'originalAmount', 'blockedAmount']) {
    if (Number.isFinite(normalized[field])) normalized[field] = Math.max(0, normalized[field]);
  }
  if (Number.isFinite(normalized.health) && Number.isFinite(normalized.absorption)) {
    normalized.effectiveHealth = roundMetric(normalized.health + normalized.absorption, decimalPlaces);
  }
  if (Number.isFinite(normalized.health) && Number.isFinite(normalized.maxHealth) && normalized.maxHealth > 0) {
    normalized.healthPercent = roundMetric(Math.max(0, Math.min(100, normalized.health / normalized.maxHealth * 100)), 1);
  }
  return normalized;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function jsonText(value) {
  return JSON.stringify({ text: String(value ?? '').slice(0, 240) });
}

function cleanChatText(value, maxLength) {
  const oneLine = String(value ?? '').replace(/[\u0000-\u001f\u007f]+/g, ' ').replace(/\s+/g, ' ').trim();
  return Array.from(oneLine).slice(0, maxLength).join('');
}

class LumiaCraftPlugin extends Plugin {
  constructor(manifest, context) {
    super(manifest, context);
    this.socket = null;
    this.buffer = '';
    this.pending = new Map();
    this.connected = false;
    this.stopping = false;
    this.generation = 0;
    this.reconnectTimer = null;
    this.keepaliveTimer = null;
    this.reconnectAttempt = 0;
    this.actionTimes = [];
    this.cooldowns = new Map();
    this.eventsReceived = 0;
    this.eventsPublished = 0;
    this.bridgeVersion = '';
    this.eventsSupported = false;
    this.chatSocket = null;
    this.chatReconnectTimer = null;
    this.chatReconnectAttempt = 0;
    this.chatGeneration = 0;
    this.chatConnected = false;
    this.chatTimes = [];
    this.chatRelayQueue = Promise.resolve();
    this.chatMessagesRelayed = 0;
    this.seenChatIds = new Map();
    this.seenChatFingerprints = new Map();
  }

  async onload() {
    this.stopping = false;
    await this._setConnected(false);
    await this.lumia.setVariable('last_result', 'Waiting for Minecraft');
    await this.lumia.setVariable('bridge_events_received', 0);
    await this._setChatConnected(false);
    await this.lumia.setVariable('stream_chat_messages_relayed', 0);
    void this._connectWithRetry();
    this._connectLumiaChat(true);
  }

  async onunload() {
    this.stopping = true;
    this.generation += 1;
    this._clearTimers();
    this._destroySocket('Plugin unloaded');
    this.chatGeneration += 1;
    this._clearChatTimer();
    this._disconnectLumiaChat();
    await this._setConnected(false);
    await this._setChatConnected(false);
  }

  async onsettingsupdate(settings, previousSettings) {
    const keys = ['host', 'port', 'token'];
    if (keys.some((key) => settings?.[key] !== previousSettings?.[key])) {
      this.generation += 1;
      this._clearTimers();
      this._destroySocket('Connection settings changed');
      this.reconnectAttempt = 0;
      void this._connectWithRetry();
    }
    const chatKeys = ['relayStreamChat', 'lumiaApiToken', 'lumiaApiHost', 'lumiaApiPort'];
    if (chatKeys.some((key) => settings?.[key] !== previousSettings?.[key])) {
      this._connectLumiaChat(true, settings);
    }
  }

  async actions(config) {
    let lastResult = 'No LumiaCraft action was selected';
    let commandsRun = 0;

    for (const action of config.actions || []) {
      try {
        if (action.type === 'reconnect') {
          await this._manualReconnect();
          lastResult = 'Reconnect started';
          continue;
        }
        if (action.type === 'bridge_status') {
          if (!this.connected) throw new Error('Minecraft is not connected');
          const status = await this._request({ type: 'ping' }, 5000);
          this.eventsPublished = Number(status.eventsPublished || 0);
          await this.lumia.setVariable('bridge_events_published', this.eventsPublished);
          lastResult = `Bridge ${this.bridgeVersion || 'unknown'}: ${this.eventsPublished} published, ${this.eventsReceived} received${this.eventsSupported ? '' : ' — update the matching bridge for event support'}`;
          continue;
        }

        const params = action.value || {};
        const cooldown = clamp(params.cooldown, 0, 0, 3600);
        const cooldownKey = `${action.type}:${String(params.preset || params.entity || params.item || params.effect || params.command || '')}`;
        this._enforceRateLimit();
        this._enforceCooldown(cooldownKey, cooldown);

        let commands = this._commandsFor(action.type, params);
        let delayMs = 0;
        if (action.type === 'random_command') {
          this._requireRawCommands();
          const choices = this._commandLines(params.commands);
          if (!choices.length) throw new Error('Add at least one command');
          commands = [choices[crypto.randomInt(choices.length)]];
        } else if (action.type === 'command_sequence') {
          this._requireRawCommands();
          commands = this._commandLines(params.commands).slice(0, 25);
          delayMs = clamp(params.delayMs, 250, 0, 10000);
        } else if (action.type === 'run_command') {
          this._requireRawCommands();
          commands = [this._cleanCommand(params.command)];
        }

        if (!commands.length) throw new Error('This action produced no commands');
        this._recordAction();
        const results = await this._runCommands(commands, delayMs);
        commandsRun += results.length;
        lastResult = results.at(-1)?.command || 'Action completed';
        this.cooldowns.set(cooldownKey, Date.now());
      } catch (error) {
        lastResult = error instanceof Error ? error.message : String(error);
        await this._log(`Action ${action.type} failed: ${lastResult}`, 'error');
      }
    }

    await this.lumia.setVariable('last_result', lastResult);
    return {
      newlyPassedVariables: {
        lumiacraft_result: lastResult,
        lumiacraft_commands_run: commandsRun,
        lumiacraft_events_published: this.eventsPublished,
        lumiacraft_events_received: this.eventsReceived
      }
    };
  }

  _commandsFor(type, params) {
    const target = this._target(params.target);
    switch (type) {
      case 'summon_mob': {
        const entity = this._identifier(params.entity, 'mob');
        const count = clamp(params.count, 1, 1, 50);
        return Array.from({ length: count }, () => `execute at ${target} run summon ${entity} ~ ~1 ~`);
      }
      case 'give_item': {
        const item = this._identifier(params.item, 'item');
        const count = clamp(params.count, 1, 1, 6400);
        return [`give ${target} ${item} ${count}`];
      }
      case 'apply_effect': {
        const effect = this._identifier(params.effect, 'effect');
        const seconds = clamp(params.seconds, 10, 1, 3600);
        const amplifier = clamp(params.amplifier, 0, 0, 9);
        return [`effect give ${target} ${effect} ${seconds} ${amplifier} true`];
      }
      case 'damage_player':
        return [`damage ${target} ${clamp(params.amount, 4, 1, 1000)}`];
      case 'world_control': {
        const states = {
          weather_thunder: 'weather thunder',
          weather_rain: 'weather rain',
          weather_clear: 'weather clear',
          time_day: 'time set day',
          time_night: 'time set night',
          time_midnight: 'time set midnight'
        };
        const command = states[String(params.state || '')];
        if (!command) throw new Error('Choose a valid world state');
        return [command];
      }
      case 'show_message': {
        const placement = String(params.placement || 'title');
        const payload = jsonText(params.message || 'A viewer changed the game!');
        if (placement === 'chat') return [`tellraw ${target} ${payload}`];
        if (!['title', 'subtitle', 'actionbar'].includes(placement)) throw new Error('Choose a valid message placement');
        return [`title ${target} ${placement} ${payload}`];
      }
      case 'run_preset':
        return this._preset(String(params.preset || ''), target);
      case 'random_command':
      case 'command_sequence':
      case 'run_command':
        return [];
      default:
        throw new Error(`Unknown action: ${type}`);
    }
  }

  _preset(name, target) {
    const presets = {
      lightning: [`execute at ${target} run summon minecraft:lightning_bolt ~ ~ ~`],
      creeper: [`execute at ${target} run summon minecraft:creeper ~2 ~ ~`],
      chicken_rain: Array.from({ length: 12 }, () => `execute at ${target} run summon minecraft:chicken ~ ~8 ~`),
      launch: [`effect give ${target} minecraft:levitation 4 9 true`],
      darkness: [`effect give ${target} minecraft:darkness 12 0 true`],
      heal: [`effect give ${target} minecraft:instant_health 1 1 true`],
      diamonds: [`give ${target} minecraft:diamond 3`],
      clear_inventory: [`clear ${target}`]
    };
    if (!presets[name]) throw new Error('Choose a valid chaos preset');
    return presets[name];
  }

  _target(value) {
    const candidate = String(value || this.settings.defaultTarget || '@p').trim();
    if (!USERNAME.test(candidate) && !SIMPLE_TARGET.test(candidate)) {
      throw new Error('Player must be a Minecraft username, @p, @a, or @r');
    }
    return candidate;
  }

  _identifier(value, label) {
    const candidate = String(value || '').trim();
    if (!IDENTIFIER.test(candidate)) throw new Error(`Invalid ${label} ID; use namespace:name`);
    return candidate;
  }

  _commandLines(value) {
    return String(value || '').split(/\r?\n/).map((line) => line.trim()).filter(Boolean).map((line) => this._cleanCommand(line));
  }

  _cleanCommand(value) {
    let command = String(value || '').trim();
    if (command.startsWith('/')) command = command.slice(1).trim();
    if (!command || command.length > 512 || /[\r\n]/.test(command)) throw new Error('Command is empty, too long, or contains a line break');
    return command;
  }

  _requireRawCommands() {
    if (this.settings.allowRawCommands !== true) throw new Error('Enable custom command actions in LumiaCraft settings first');
  }

  _enforceRateLimit() {
    const now = Date.now();
    const limit = clamp(this.settings.maxActionsPerMinute, 30, 1, 300);
    this.actionTimes = this.actionTimes.filter((time) => now - time < 60000);
    if (this.actionTimes.length >= limit) throw new Error(`Safety limit reached (${limit} actions per minute)`);
  }

  _recordAction() {
    this.actionTimes.push(Date.now());
  }

  _enforceCooldown(key, seconds) {
    if (seconds <= 0) return;
    const remaining = (this.cooldowns.get(key) || 0) + seconds * 1000 - Date.now();
    if (remaining > 0) throw new Error(`Action is cooling down (${Math.ceil(remaining / 1000)}s remaining)`);
  }

  async _runCommands(commands, delayMs) {
    if (this.settings.dryRun === true) {
      await this._log(`Dry run: ${commands.join(' | ')}`, 'info');
      return commands.map((command) => ({ ok: true, command, result: 0 }));
    }
    if (!this.connected) throw new Error('Minecraft is not connected. Start the world/server, then run Reconnect to Minecraft');
    const results = [];
    for (let index = 0; index < commands.length; index += 1) {
      results.push(await this._request({ type: 'execute', command: commands[index] }));
      if (delayMs > 0 && index < commands.length - 1) await sleep(delayMs);
    }
    return results;
  }

  async _manualReconnect() {
    this.generation += 1;
    this._clearTimers();
    this._destroySocket('Manual reconnect');
    this.reconnectAttempt = 0;
    await this._setConnected(false);
    void this._connectWithRetry();
    this._connectLumiaChat(true);
  }

  _lumiaChatUrl(settings = this.settings) {
    if (settings?.relayStreamChat !== true) return '';
    const token = String(settings?.lumiaApiToken || '').trim();
    if (!token) return '';
    const host = String(settings?.lumiaApiHost || '127.0.0.1').trim() || '127.0.0.1';
    const port = clamp(settings?.lumiaApiPort, DEFAULT_LUMIA_API_PORT, 1, 65535);
    return `ws://${host}:${port}/api?token=${encodeURIComponent(token)}&name=${encodeURIComponent('LumiaCraft Chat Relay')}`;
  }

  _connectLumiaChat(resetAttempts, settings = this.settings) {
    if (resetAttempts) this.chatReconnectAttempt = 0;
    const generation = ++this.chatGeneration;
    this._clearChatTimer();
    this._disconnectLumiaChat();

    if (settings?.relayStreamChat !== true) {
      void this._setChatConnected(false);
      return;
    }
    const url = this._lumiaChatUrl(settings);
    if (!url) {
      void this._setChatConnected(false);
      void this._log('Stream chat relay is enabled, but the Lumia Developers API token is empty.', 'warn');
      return;
    }

    let socket;
    try {
      socket = new WebSocket(url);
    } catch (error) {
      this._scheduleChatReconnect(generation, error);
      return;
    }
    this.chatSocket = socket;
    socket.on('open', () => {
      if (generation !== this.chatGeneration) return;
      this.chatReconnectAttempt = 0;
      void this._setChatConnected(true);
      void this._log('Connected to Lumia unified chat for in-game relay.', 'info');
    });
    socket.on('message', (buffer) => {
      if (generation !== this.chatGeneration) return;
      this._handleLumiaChatMessage(buffer);
    });
    socket.on('error', () => {});
    socket.on('close', () => {
      if (generation !== this.chatGeneration || this.stopping) return;
      this.chatSocket = null;
      void this._setChatConnected(false);
      this._scheduleChatReconnect(generation);
    });
  }

  _disconnectLumiaChat() {
    const socket = this.chatSocket;
    this.chatSocket = null;
    if (!socket) return;
    try {
      socket.removeAllListeners();
      socket.close();
    } catch (_) {
      // Already closed.
    }
  }

  _scheduleChatReconnect(generation, originalError) {
    if (this.stopping || generation !== this.chatGeneration || this.settings?.relayStreamChat !== true) return;
    void this._setChatConnected(false);
    if (this.chatReconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
      const detail = originalError instanceof Error ? `: ${originalError.message}` : '';
      void this._log(`Lumia chat reconnect limit reached${detail}. Use Reconnect to Minecraft to try again.`, 'warn');
      return;
    }
    const delay = Math.min(16000, 1000 * (2 ** this.chatReconnectAttempt));
    this.chatReconnectAttempt += 1;
    this.chatReconnectTimer = setTimeout(() => {
      this.chatReconnectTimer = null;
      if (generation === this.chatGeneration) this._connectLumiaChat(false);
    }, delay);
  }

  _handleLumiaChatMessage(buffer) {
    let envelope;
    try {
      envelope = JSON.parse(buffer.toString());
    } catch (_) {
      return;
    }
    if (!envelope || typeof envelope !== 'object' || envelope.event === 'socketapi:subscribed') return;
    const type = String(envelope.type || '').toLowerCase();
    if (type !== 'chat' && !type.startsWith('chat/')) return;

    const chat = this._normalizeLumiaChat(envelope);
    if (!chat.message) return;
    if (chat.id ? this._alreadySawChat(chat.id) : this._alreadySawChatFingerprint(chat)) return;
    this.chatRelayQueue = this.chatRelayQueue
      .then(() => this._relayLumiaChat(chat))
      .catch((error) => this._log(`Stream chat relay failed: ${error instanceof Error ? error.message : String(error)}`, 'error'));
  }

  _normalizeLumiaChat(envelope) {
    const data = envelope.data && typeof envelope.data === 'object' ? envelope.data : {};
    const extra = data.extraSettings && typeof data.extraSettings === 'object' ? data.extraSettings : {};
    const info = data.info && typeof data.info === 'object' ? data.info : {};
    const username = cleanChatText(extra.username || info.username || data.username || '', 64);
    const displayname = cleanChatText(extra.displayname || info.displayname || data.displayname || username, 64);
    const maxLength = clamp(this.settings?.streamChatMaxLength, 200, 20, 500);
    return {
      id: String(data.id || info.id || extra.id || ''),
      username,
      displayname: displayname || username || 'Viewer',
      message: cleanChatText(data.message || info.message || extra.message || '', maxLength),
      origin: cleanChatText(envelope.origin || data.origin || 'chat', 32)
    };
  }

  _alreadySawChat(id) {
    const now = Date.now();
    for (const [knownId, seenAt] of this.seenChatIds) {
      if (now - seenAt > 300000) this.seenChatIds.delete(knownId);
    }
    if (this.seenChatIds.has(id)) return true;
    this.seenChatIds.set(id, now);
    return false;
  }

  _alreadySawChatFingerprint(chat) {
    const now = Date.now();
    // Lumia's unified feed can publish the same Twitch message through several
    // no-ID event envelopes. Keep this deliberately short so a viewer can
    // still send the same text again a moment later.
    for (const [known, seenAt] of this.seenChatFingerprints) {
      if (now - seenAt > 8000) this.seenChatFingerprints.delete(known);
    }
    const fingerprint = `${chat.origin}\u0000${chat.username}\u0000${chat.message}`;
    if (this.seenChatFingerprints.has(fingerprint)) return true;
    this.seenChatFingerprints.set(fingerprint, now);
    return false;
  }

  async _relayLumiaChat(chat) {
    if (this.settings?.relayStreamChat !== true || !this.connected) return;
    const now = Date.now();
    const limit = clamp(this.settings?.streamChatMessagesPerMinute, 120, 1, 600);
    this.chatTimes = this.chatTimes.filter((time) => now - time < 60000);
    if (this.chatTimes.length >= limit) return;

    const target = this._chatTarget(this.settings?.streamChatTarget);
    const rendered = `${chat.displayname || chat.username || 'Viewer'}: ${chat.message}`;
    const command = `tellraw ${target} ${JSON.stringify({ text: rendered })}`;
    if (this.settings?.dryRun === true) {
      await this._log(`Dry run chat: ${rendered}`, 'info');
    } else {
      await this._request({ type: 'execute', command });
    }
    this.chatTimes.push(now);
    this.chatMessagesRelayed += 1;
    await this.lumia.setVariable('stream_chat_messages_relayed', this.chatMessagesRelayed);
    await this.lumia.setVariable('stream_chat_last_sender', chat.displayname || chat.username || 'Viewer');
    await this.lumia.setVariable('stream_chat_last_message', chat.message);
    await this.lumia.setVariable('stream_chat_last_platform', chat.origin);
  }

  _chatTarget(value) {
    const candidate = String(value || '@a').trim();
    if (!USERNAME.test(candidate) && !SIMPLE_TARGET.test(candidate)) {
      throw new Error('Chat recipients must be a Minecraft username, @p, @a, or @r');
    }
    return candidate;
  }

  async _connectWithRetry() {
    if (this.stopping || this.connected) return;
    const generation = ++this.generation;
    while (!this.stopping && !this.connected && this.reconnectAttempt < MAX_RECONNECT_ATTEMPTS && generation === this.generation) {
      try {
        await this._connectOnce(generation);
        this.reconnectAttempt = 0;
        return;
      } catch (error) {
        this._destroySocket('Connection attempt failed');
        await this._setConnected(false);
        this.reconnectAttempt += 1;
        const message = error instanceof Error ? error.message : String(error);
        await this.lumia.setVariable('last_result', `Connection failed: ${message}`);
        if (this.reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
          if (this.settings.backgroundReconnect !== false && !this.stopping) {
            await this._log('Minecraft is unavailable. LumiaCraft will look again in 30 seconds.', 'warn');
            this.reconnectTimer = setTimeout(() => {
              this.reconnectTimer = null;
              this.reconnectAttempt = 0;
              void this._connectWithRetry();
            }, 30000);
          } else {
            await this._log('Minecraft is unavailable. Start it and use Reconnect to Minecraft.', 'warn');
          }
          return;
        }
        await sleep(Math.min(16000, 1000 * (2 ** (this.reconnectAttempt - 1))));
      }
    }
  }

  async _connectOnce(generation) {
    const host = String(this.settings.host || '127.0.0.1').trim();
    const port = clamp(this.settings.port, 38931, 1024, 65535);
    const socket = net.createConnection({ host, port });
    this.socket = socket;
    this.buffer = '';

    socket.on('data', (chunk) => this._onData(chunk));
    socket.on('error', () => {});
    socket.on('close', () => {
      if (this.socket !== socket) return;
      const wasConnected = this.connected;
      this._destroySocket('Minecraft disconnected');
      void this._setConnected(false).then(() => {
        if (!this.stopping && wasConnected) {
          this.reconnectAttempt = 0;
          void this._connectWithRetry();
        }
      });
    });

    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Connection timed out')), 5000);
      socket.once('connect', () => { clearTimeout(timeout); resolve(); });
      socket.once('error', (error) => { clearTimeout(timeout); reject(error); });
    });
    if (generation !== this.generation) throw new Error('Connection was replaced');

    const hello = await this._request({
      type: 'hello',
      token: String(this.settings.token || ''),
      client: 'lumiacraft',
      protocol: PROTOCOL
    });
    if (Number(hello.protocol) !== PROTOCOL) throw new Error('Bridge protocol version mismatch');
    await this.lumia.setVariable('minecraft_version', String(hello.minecraftVersion || 'unknown'));
    this.bridgeVersion = String(hello.modVersion || 'unknown');
    this.eventsPublished = Number(hello.eventsPublished || 0);
    this.eventsSupported = hello.eventsSupported === true;
    await this.lumia.setVariable('bridge_version', this.bridgeVersion);
    await this.lumia.setVariable('bridge_events_published', this.eventsPublished);
    await this.lumia.setVariable('bridge_events_supported', this.eventsSupported);
    await this.lumia.setVariable('players_online', Number(hello.players || 0));
    const compatibility = this.eventsSupported ? '' : ' — update the matching bridge for Minecraft event alerts';
    await this.lumia.setVariable('last_result', `Connected to Minecraft ${hello.minecraftVersion || ''} (bridge ${this.bridgeVersion})${compatibility}`.trim());
    if (!this.eventsSupported) await this._log('The connected bridge does not support gameplay events. Install the matching LumiaBridge 0.2.6 JAR.', 'warn');
    await this._setConnected(true);
    this.keepaliveTimer = setInterval(() => void this._heartbeat(), KEEPALIVE_MS);
  }

  async _heartbeat() {
    try {
      const response = await this._request({ type: 'ping' }, 5000);
      await this.lumia.setVariable('players_online', Number(response.players || 0));
      this.eventsPublished = Number(response.eventsPublished || 0);
      await this.lumia.setVariable('bridge_events_published', this.eventsPublished);
    } catch (error) {
      this._destroySocket('Heartbeat failed');
      await this._setConnected(false);
      if (!this.stopping) {
        this.reconnectAttempt = 0;
        void this._connectWithRetry();
      }
    }
  }

  _request(payload, timeoutMs = REQUEST_TIMEOUT_MS) {
    if (!this.socket || this.socket.destroyed || !this.socket.writable) return Promise.reject(new Error('Minecraft socket is not available'));
    const id = crypto.randomUUID();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error('Minecraft did not respond in time'));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      this.socket.write(`${JSON.stringify({ ...payload, id })}\n`, 'utf8', (error) => {
        if (!error) return;
        clearTimeout(timer);
        this.pending.delete(id);
        reject(error);
      });
    });
  }

  _onData(chunk) {
    this.buffer += chunk.toString('utf8');
    if (this.buffer.length > 65536) {
      this._destroySocket('Incoming message was too large');
      return;
    }
    let newline;
    while ((newline = this.buffer.indexOf('\n')) >= 0) {
      const line = this.buffer.slice(0, newline).trim();
      this.buffer = this.buffer.slice(newline + 1);
      if (!line) continue;
      let message;
      try { message = JSON.parse(line); } catch { continue; }
      if (message.type === 'event') {
        void this._handleMinecraftEvent(message).catch((error) => {
          void this._log(`Minecraft event failed: ${error instanceof Error ? error.message : String(error)}`, 'error');
        });
        continue;
      }
      const entry = this.pending.get(message.id);
      if (!entry) continue;
      clearTimeout(entry.timer);
      this.pending.delete(message.id);
      if (message.ok === false) entry.reject(new Error(String(message.error || 'Minecraft rejected the action')));
      else entry.resolve(message);
    }
  }

  async _handleMinecraftEvent(message) {
    const eventType = String(message.event || '');
    const spec = EVENT_ALERTS[eventType];
    if (!spec) return;
    const data = message.data && typeof message.data === 'object' ? message.data : {};
    const watchedPlayer = String(this.settings.watchedPlayer || '').trim().toLowerCase();
    if (watchedPlayer && String(data.player || '').toLowerCase() !== watchedPlayer) return;

    const enabled = {
      health: this.settings.healthEvents !== false,
      effect: this.settings.effectEvents !== false,
      lifecycle: this.settings.lifecycleEvents !== false,
      progression: this.settings.progressionEvents !== false,
      item: this.settings.itemEvents === true
    };
    if (!enabled[spec.group]) return;

    const decimalPlaces = clamp(this.settings.healthDecimalPlaces, 1, 0, 2);
    const alertData = normalizeHealthData(data, decimalPlaces);

    this.eventsReceived += 1;
    await this.lumia.setVariable('bridge_events_received', this.eventsReceived);
    await this.lumia.setVariable('last_event', eventType);
    await this.lumia.setVariable('last_event_player', String(data.player || ''));
    if (Number.isFinite(Number(alertData.health))) await this.lumia.setVariable('player_health', Number(alertData.health));
    if (Number.isFinite(Number(alertData.maxHealth))) await this.lumia.setVariable('player_max_health', Number(alertData.maxHealth));
    if (Number.isFinite(Number(alertData.healthPercent))) await this.lumia.setVariable('player_health_percent', Number(alertData.healthPercent));
    if (typeof alertData.effect === 'string') await this.lumia.setVariable('last_effect', alertData.effect);

    const extraSettings = {
      ...alertData,
      event_type: eventType,
      event_sequence: Number(message.sequence || 0),
      event_timestamp: Number(message.timestamp || Date.now())
    };

    // LumiaCraft is a utility plugin, not a streaming-platform event source.
    // Use the normal alert dispatch path so the configured Lumia alert actions
    // are queued. Event List recording is intentionally not requested here.
    const alert = {
      alert: eventType,
      extraSettings
    };

    // Only alerts with declared variationConditions receive a dynamic value.
    // Ordinary damage/heal/lifecycle events stay on the base alert and use
    // extraSettings for all template/action variables.
    if (spec.variationValue) {
      alert.dynamic = { value: spec.variationValue(alertData) ?? '' };
    }

    const accepted = await this.lumia.triggerAlert(alert);
    if (accepted === false) {
      throw new Error(`Lumia rejected alert ${eventType}`);
    }
  }

  _destroySocket(reason) {
    const socket = this.socket;
    this.socket = null;
    this.buffer = '';
    if (socket && !socket.destroyed) socket.destroy();
    for (const entry of this.pending.values()) {
      clearTimeout(entry.timer);
      entry.reject(new Error(reason));
    }
    this.pending.clear();
  }

  _clearTimers() {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    if (this.keepaliveTimer) clearInterval(this.keepaliveTimer);
    this.reconnectTimer = null;
    this.keepaliveTimer = null;
  }

  _clearChatTimer() {
    if (this.chatReconnectTimer) clearTimeout(this.chatReconnectTimer);
    this.chatReconnectTimer = null;
  }

  async _setConnected(state) {
    if (this.connected === state && this.lumia.getConnectionState?.() === state) return;
    this.connected = state;
    await this.lumia.setVariable('connected', state);
    await this.lumia.updateConnection(state);
  }

  async _setChatConnected(state) {
    this.chatConnected = state;
    await this.lumia.setVariable('stream_chat_connected', state);
  }

  async _log(message, level = 'info') {
    await this.lumia.log({ message: `[LumiaCraft] ${message}`, level });
  }
}

module.exports = LumiaCraftPlugin;
