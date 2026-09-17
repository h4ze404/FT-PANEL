// Minecraft Manager — локальный сервер управления запущенными Minecraft
// Запуск: node server.js  (или start.bat)
// Порт: 8123 (http://localhost:8123)

const http = require('http');
const { execFile, spawn } = require('child_process');
const crypto = require('crypto');
const os = require('os');
const fs = require('fs');
const path = require('path');

const PORT = 8123;
const POLL_MS = 700;            // период опроса процессов
const HISTORY_MAX = 300;        // точек истории (15 минут)
const MOD_PORTS = [8765, 8766, 8767, 8768, 8769, 8770, 8771, 8772, 8773, 8774];
const CORES = os.cpus().length;
const UI_SETTINGS_FILE = path.join(__dirname, 'ui-settings.json');
let uiSettings = {};
try { uiSettings = JSON.parse(fs.readFileSync(UI_SETTINGS_FILE, 'utf8')); } catch (e) {}
function loadUiSettings() { return uiSettings; }

const TLAUNCHER_DIR = path.join(process.env.APPDATA, '.tlauncher');
const LOG_DIR = path.join(TLAUNCHER_DIR, 'logs');

// ---------- Состояние ----------
let instances = new Map();      // pid -> { pid, name, version, ram, cpu, upMs }
let prevTimes = new Map();      // pid -> { t: cpuTimeMs, ts: Date.now() }
let history = [];               // [{ ts, totalCpu, totalRam, count }]
let events = [];                // [{ ts, text, type }]
let modInstances = new Map();   // port -> info (игры с нашим модом)
let polling = false;
let launchTemplate = null;      // { javaw, flags[], natives, cp, gameDir, assetsDir, assetIndex, version, width, height }

const eventsFile = path.join(__dirname, 'events.json');
const nicknamesFile = path.join(__dirname, 'nicknames.json');
const coordsFile = path.join(__dirname, 'coords.json');

function readJson(file, fallback) {
    try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch (e) { return fallback; }
}
function writeJson(file, data) {
    try { fs.writeFileSync(file, JSON.stringify(data, null, 2), 'utf8'); } catch (e) { /* не критично */ }
}

// ---------- Логика ----------
function logEvent(text, type) {
    const ev = { ts: Date.now(), text, type: type || 'info' };
    events.unshift(ev);
    if (events.length > 200) events.length = 200;
    writeJson(eventsFile, events.slice(0, 50));
}

function parseProcesses(jsonText) {
    const now = Date.now();
    let list = [];
    try {
        const parsed = JSON.parse(jsonText);
        list = Array.isArray(parsed) ? parsed : [parsed];
    } catch (e) {
        return;
    }

    const found = new Set();

    for (const p of list) {
        const pid = Number(p.ProcessId);
        if (!pid || !p.CommandLine) continue;
        const cmd = String(p.CommandLine);
        if (!/minecraft|tlauncher|fabric/i.test(cmd)) continue;

        found.add(pid);

        let name = 'Minecraft';
        let version = '';
        let m = cmd.match(/--username[= ]\s*([^\s"]+)/);
        if (m) name = m[1];
        m = cmd.match(/--version[= ]\s*([^\s"]+)/);
        if (m) version = m[1];
        if (!version) {
            m = cmd.match(/(\d+\.\d+(?:\.\d+)?(?:-rc\d+)?)\s*[-"]/);
            if (m) version = m[1];
        }
        if (/tlauncher/i.test(cmd)) name += ' (TLauncher)';

        const cpuTimeMs = Number(p.KernelModeTime || 0) / 100 + Number(p.UserModeTime || 0) / 100;
        const prev = prevTimes.get(pid);
        let cpuPct = 0;
        if (prev) {
            const dt = now - prev.ts;
            if (dt > 500) {
                cpuPct = Math.max(0, Math.min(100, ((cpuTimeMs - prev.t) / dt / CORES) * 100));
            }
        }
        prevTimes.set(pid, { t: cpuTimeMs, ts: now });

        const ram = Number(p.WorkingSetSize || 0);
        const upMs = Number(p.UpMs || 0);

        if (!instances.has(pid)) {
            logEvent(`Запущен Minecraft: ${name} ${version ? '(' + version + ')' : ''} [PID ${pid}]`, 'start');
        }

        instances.set(pid, {
            pid, name, version,
            ram,
            ramMb: Math.round(ram / 1024 / 1024),
            cpu: Math.round(cpuPct * 10) / 10,
            upMs
        });
    }

    for (const pid of [...instances.keys()]) {
        if (!found.has(pid)) {
            const inst = instances.get(pid);
            logEvent(`Остановлен: ${inst.name} [PID ${pid}]`, 'stop');
            instances.delete(pid);
            prevTimes.delete(pid);
        }
    }
    for (const pid of [...prevTimes.keys()]) {
        if (!found.has(pid) && !instances.has(pid)) prevTimes.delete(pid);
    }

    let totalRam = 0, totalCpu = 0;
    for (const i of instances.values()) { totalRam += i.ramMb; totalCpu += i.cpu; }
    history.push({
        ts: now,
        totalCpu: Math.round(totalCpu * 10) / 10,
        totalRam,
        count: instances.size
    });
    if (history.length > HISTORY_MAX) history.shift();
}

function poll() {
    if (polling) return;
    polling = true;
    const script = [
        "Get-CimInstance Win32_Process -Filter \"Name='javaw.exe' OR Name='java.exe'\" |",
        "Where-Object { $_.CommandLine -match 'minecraft|tlauncher|fabric' } |",
        "Select-Object ProcessId,",
        "@{n='UpMs';e={[long]((New-TimeSpan $_.CreationDate (Get-Date)).TotalMilliseconds)}},",
        "@{n='KernelModeTime';e={[long]$_.KernelModeTime}},",
        "@{n='UserModeTime';e={[long]$_.UserModeTime}},",
        "@{n='WorkingSetSize';e={[long]$_.WorkingSetSize}},",
        "CommandLine | ConvertTo-Json -Compress -Depth 2"
    ].join(' ');

    execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script],
        { windowsHide: true, timeout: 15000, maxBuffer: 4 * 1024 * 1024 },
        (err, stdout) => {
            if (!err && stdout.trim()) parseProcesses(stdout);
            polling = false;
        });

    // Опрос игр с модом
    for (const port of MOD_PORTS) {
        modGet(port, '/info', 500, (info) => {
            if (info && info.port) {
                const isNew = !modInstances.has(port);
                modInstances.set(port, info);
                if (isNew && info.player) {
                    logEvent(`Игра подключена к панели: ${info.player} (порт ${port})`, 'info');
                }
            } else if (modInstances.has(port)) {
                modInstances.delete(port);
            }
        });
    }
}

function modGet(port, apiPath, timeoutMs, cb) {
    const req = http.get({ host: '127.0.0.1', port, path: apiPath, timeout: timeoutMs }, res => {
        let data = '';
        res.on('data', c => data += c);
        res.on('end', () => {
            try { cb(JSON.parse(data)); } catch (e) { cb(null); }
        });
    });
    // жёсткий дедлайн независимо от стадии (connect/read)
    const killer = setTimeout(() => req.destroy(new Error('deadline')), timeoutMs);
    req.on('close', () => clearTimeout(killer));
    req.on('timeout', () => { req.destroy(); cb(null); });
    req.on('error', () => cb(null));
}

// Свежий опрос мод-портов; мёртвые порты кэшируются (проверяются раз в 5с),
// поэтому при запущенной игре ответ приходит за ~20-40мс
const deadPorts = new Map(); // port -> ts
let deadRecheckAt = 0;
function fetchModsFresh(cb) {
    const now = Date.now();
    if (now > deadRecheckAt) { deadPorts.clear(); deadRecheckAt = now + 5000; }
    const alive = MOD_PORTS.filter(p => !deadPorts.has(p));
    const out = [];
    const total = alive.length;
    let completed = 0;
    let done = false;
    const finish = () => {
        if (done) return;
        done = true;
        out.sort((a, b) => a.port - b.port);
        cb(out);
    };
    if (!total) return finish();
    const timer = setTimeout(finish, 300);
    for (const port of alive) {
        modGet(port, '/info', 250, info => {
            if (info && info.port) out.push(info);
            else deadPorts.set(port, now);
            if (++completed >= total) { clearTimeout(timer); finish(); }
        });
    }
}

function modPost(port, apiPath, payload, cb) {
    const body = JSON.stringify(payload || {});
    const req = http.request({
        host: '127.0.0.1', port, path: apiPath, method: 'POST', timeout: 5000,
        headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) }
    }, res => {
        let data = '';
        res.on('data', c => data += c);
        res.on('end', () => cb(res.statusCode, data));
    });
    req.on('timeout', () => { req.destroy(); cb(0, 'timeout'); });
    req.on('error', e => cb(0, e.message));
    req.write(body);
    req.end();
}

// ---------- Запуск игры с ником ----------

function offlineUuid(nick) {
    // как в Minecraft: UUID v3 от "OfflinePlayer:<ник>"
    const md5 = crypto.createHash('md5').update('OfflinePlayer:' + nick, 'utf8').digest();
    md5[6] = (md5[6] & 0x0f) | 0x30;
    md5[8] = (md5[8] & 0x3f) | 0x80;
    const h = md5.toString('hex');
    return h.slice(0, 8) + h.slice(8, 12) + h.slice(12, 16) + h.slice(16, 20) + h.slice(20);
}

function findLaunchTemplate(cb) {
    // Ищем самый свежий лог лаунчера с полной командой запуска
    fs.readdir(LOG_DIR, (err, files) => {
        if (err) return cb(null, 'Нет папки логов TLauncher');
        const logs = files.filter(f => /^launcher\.log/.test(f)).sort().reverse();
        let tryNext = (idx) => {
            if (idx >= logs.length) return cb(null, 'В логах TLauncher нет команды запуска (запусти игру через лаунчер один раз)');
            const file = path.join(LOG_DIR, logs[idx]);
            fs.readFile(file, 'utf8', (err2, text) => {
                if (err2) return tryNext(idx + 1);
                const clean = text.replace(/\x1b\[[0-9;]*m/g, '');
                const lines = clean.split(/\r?\n/);
                for (let i = lines.length - 1; i >= 0; i--) {
                    if (/Half command/.test(lines[i]) && i + 1 < lines.length) {
                        const line = lines[i + 1];
                        const m = line.match(/\[MinecraftLauncher\]\s*(.+)$/);
                        if (m && m[1].includes('KnotClient')) {
                            return cb(parseTemplate(m[1]));
                        }
                    }
                }
                tryNext(idx + 1);
            });
        };
        tryNext(0);
    });
}

function parseTemplate(cmd) {
    try {
        const jreEnd = cmd.indexOf(' -Xms');
        if (jreEnd < 0) return { error: 'Не найдены флаги JVM в команде' };
        const javaw = cmd.slice(0, jreEnd).trim();

        const nativesM = cmd.match(/-Djava\.library\.path=(.+?) -Djna\.tmpdir=/);
        if (!nativesM) return { error: 'Не найден путь natives' };
        const natives = nativesM[1];

        const cpM = cmd.match(/ -cp (.+?) -DFabricMcEmu=/);
        if (!cpM) return { error: 'Не найден classpath' };
        const cp = cpM[1];

        const mainM = cmd.match(/KnotClient\s+(.+)$/);
        if (!mainM) return { error: 'Не найдены аргументы игры' };
        const gameArgs = mainM[1];

        const gameDirM = gameArgs.match(/--gameDir (.+?) --assetsDir/);
        const assetsDirM = gameArgs.match(/--assetsDir (.+?) --assetIndex/);
        const assetIndexM = gameArgs.match(/--assetIndex (\S+)/);
        const versionM = gameArgs.match(/--version (.+?) --gameDir/);
        const widthM = gameArgs.match(/--width (\d+)/);
        const heightM = gameArgs.match(/--height (\d+)/);
        if (!gameDirM || !assetsDirM || !assetIndexM || !versionM) return { error: 'Не разобраны аргументы игры' };

        // JVM-флаги: от -Xms до -Djava.library.path (все без пробелов)
        const flagsStr = cmd.slice(jreEnd, cmd.indexOf('-Djava.library.path='));
        const flags = flagsStr.trim().split(/\s+/).filter(f => f && f !== '-Djava.library.path=');

        return {
            javaw, flags, cp, natives,
            gameDir: gameDirM[1], assetsDir: assetsDirM[1], assetIndex: assetIndexM[1],
            version: versionM[1],
            width: widthM ? widthM[1] : '925', height: heightM ? heightM[1] : '530'
        };
    } catch (e) {
        return { error: 'Ошибка разбора команды: ' + e.message };
    }
}

function launchGame(nick, cb) {
    const doLaunch = (tpl) => {
        if (!tpl || tpl.error) return cb(tpl && tpl.error ? tpl.error : 'Нет шаблона запуска');

        const args = [
            ...tpl.flags,
            `-Djava.library.path=${tpl.natives}`,
            `-Djna.tmpdir=${tpl.natives}`,
            `-Dorg.lwjgl.system.SharedLibraryExtractPath=${tpl.natives}`,
            `-Dio.netty.native.workdir=${tpl.natives}`,
            '-Dminecraft.launcher.brand=java-minecraft-launcher',
            '-Dminecraft.launcher.version=1.6.84-j',
            '-Xss2M',
            '-cp', tpl.cp,
            'net.fabricmc.loader.impl.launch.knot.KnotClient',
            '--username', nick,
            '--version', tpl.version,
            '--gameDir', tpl.gameDir,
            '--assetsDir', tpl.assetsDir,
            '--assetIndex', tpl.assetIndex,
            '--uuid', offlineUuid(nick),
            '--accessToken', '0',
            '--clientId', '',
            '--xuid', '',
            '--versionType', 'modified',
            '--width', tpl.width,
            '--height', tpl.height
        ];

        const child = spawn(tpl.javaw, args, {
            cwd: tpl.gameDir,
            detached: true,
            stdio: 'ignore',
            windowsHide: false
        });
        child.unref();
        logEvent(`Запуск игры из панели: ${nick} [PID ${child.pid}]`, 'start');
        cb(null, 'Игра запускается: ' + nick);
    };

    if (launchTemplate) return doLaunch(launchTemplate);
    findLaunchTemplate((tpl, err) => {
        if (err) return cb(err);
        if (!tpl || tpl.error) return cb(tpl && tpl.error ? tpl.error : (err || 'Нет шаблона'));
        launchTemplate = tpl;
        doLaunch(tpl);
    });
}

function killProcess(pid, cb) {
    execFile('taskkill.exe', ['/PID', String(pid), '/F', '/T'],
        { windowsHide: true, timeout: 10000 },
        (err) => {
            if (!err) logEvent(`Процесс ${pid} остановлен по команде`, 'kill');
            cb(err ? 'Не удалось остановить процесс ' + pid : 'ok');
        });
}

// ---------- HTTP ----------
function sendJson(res, code, data) {
    const body = JSON.stringify(data);
    res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(body);
}

function readBody(req, cb) {
    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => cb(body));
}

const server = http.createServer((req, res) => {
    const url = new URL(req.url, `http://localhost:${PORT}`);
    const p = url.pathname;

    // --- Настройки интерфейса (сохраняются в ui-settings.json) ---
    if (p === '/api/settings' && req.method === 'GET') {
        return sendJson(res, 200, loadUiSettings());
    }
    if (p === '/api/settings' && req.method === 'POST') {
        return readBody(req, body => {
            try {
                const s = JSON.parse(body || '{}');
                if (s && typeof s === 'object') {
                    uiSettings = Object.assign(loadUiSettings(), s);
                    try { fs.writeFileSync(UI_SETTINGS_FILE, JSON.stringify(uiSettings, null, 2)); } catch (e) {}
                }
            } catch (e) {}
            sendJson(res, 200, { ok: true });
        });
    }

    // --- Состояние (данные модов берём НАПРЯМУЮ, свежие, без кэша) ---
    if (p === '/api/data' && req.method === 'GET') {
        return fetchModsFresh(freshMods => {
            for (const info of freshMods) modInstances.set(info.port, info);
            sendJson(res, 200, {
                now: Date.now(),
                instances: [...instances.values()],
                mods: freshMods.length ? freshMods : [...modInstances.values()],
                history,
                events: events.slice(0, 30),
                cores: CORES
            });
        });
    }

    // --- Процессы ---
    if (p === '/api/kill' && req.method === 'POST') {
        return readBody(req, body => {
            try {
                const { pid } = JSON.parse(body);
                if (!pid) return sendJson(res, 400, { error: 'pid required' });
                killProcess(pid, msg => sendJson(res, 200, { result: msg }));
            } catch (e) { sendJson(res, 400, { error: 'bad json' }); }
        });
    }

    if (p === '/api/killall' && req.method === 'POST') {
        const pids = [...instances.keys()];
        if (!pids.length) return sendJson(res, 200, { result: 'Нет запущенных' });
        let done = 0;
        for (const pid of pids) {
            killProcess(pid, () => {
                done++;
                if (done === pids.length) sendJson(res, 200, { result: `Остановлено: ${pids.length}` });
            });
        }
        return;
    }

    // --- Управление играми с модом ---
    if (p === '/api/mod' && req.method === 'POST') {
        return readBody(req, body => {
            try {
                const { port, action, payload } = JSON.parse(body);
                if (!port || !action) return sendJson(res, 400, { error: 'port, action required' });
                const apiPath = '/' + action;
                modPost(port, apiPath, payload, (code, data) => {
                    sendJson(res, code || 502, code === 200 ? { ok: true } : { error: data || 'нет связи' });
                });
            } catch (e) { sendJson(res, 400, { error: 'bad json' }); }
        });
    }

    // --- Запуск игры ---
    if (p === '/api/launch' && req.method === 'POST') {
        return readBody(req, body => {
            try {
                const { nick } = JSON.parse(body);
                if (!nick || !/^[A-Za-z0-9_]{3,16}$/.test(nick)) {
                    return sendJson(res, 400, { error: 'Ник: 3-16 символов, латиница/цифры/_ ' });
                }
                launchGame(nick, (err, msg) => {
                    if (err) sendJson(res, 500, { error: err });
                    else sendJson(res, 200, { result: msg });
                });
            } catch (e) { sendJson(res, 400, { error: 'bad json' }); }
        });
    }

    // --- Никнеймы ---
    if (p === '/api/nicknames' && req.method === 'GET') {
        return sendJson(res, 200, { nicknames: readJson(nicknamesFile, []) });
    }
    if (p === '/api/nicknames' && req.method === 'POST') {
        return readBody(req, body => {
            try {
                const { nick } = JSON.parse(body);
                if (!nick) return sendJson(res, 400, { error: 'nick required' });
                let list = readJson(nicknamesFile, []);
                if (!list.includes(nick)) {
                    list.push(nick);
                    writeJson(nicknamesFile, list);
                    logEvent(`Сохранён ник: ${nick}`, 'info');
                }
                sendJson(res, 200, { ok: true, nicknames: list });
            } catch (e) { sendJson(res, 400, { error: 'bad json' }); }
        });
    }
    if (p === '/api/nicknames' && req.method === 'DELETE') {
        return readBody(req, body => {
            try {
                const { nick } = JSON.parse(body);
                let list = readJson(nicknamesFile, []).filter(n => n !== nick);
                writeJson(nicknamesFile, list);
                sendJson(res, 200, { ok: true, nicknames: list });
            } catch (e) { sendJson(res, 400, { error: 'bad json' }); }
        });
    }

    // --- Координаты ---
    if (p === '/api/coords' && req.method === 'GET') {
        return sendJson(res, 200, { coords: readJson(coordsFile, []) });
    }
    if (p === '/api/coords' && req.method === 'POST') {
        return readBody(req, body => {
            try {
                const { name, x, y, z } = JSON.parse(body);
                if (!name || [x, y, z].some(v => v === undefined || v === null || isNaN(Number(v)))) {
                    return sendJson(res, 400, { error: 'name, x, y, z required' });
                }
                let list = readJson(coordsFile, []);
                list.push({ name, x: Math.round(Number(x)), y: Math.round(Number(y)), z: Math.round(Number(z)) });
                writeJson(coordsFile, list);
                logEvent(`Сохранена точка: ${name} (${x}, ${y}, ${z})`, 'info');
                sendJson(res, 200, { ok: true, coords: list });
            } catch (e) { sendJson(res, 400, { error: 'bad json' }); }
        });
    }
    if (p === '/api/coords' && req.method === 'DELETE') {
        return readBody(req, body => {
            try {
                const { index } = JSON.parse(body);
                let list = readJson(coordsFile, []);
                list.splice(Number(index), 1);
                writeJson(coordsFile, list);
                sendJson(res, 200, { ok: true, coords: list });
            } catch (e) { sendJson(res, 400, { error: 'bad json' }); }
        });
    }

    // --- статика ---
    if (p === '/' || p === '/index.html') {
        fs.readFile(path.join(__dirname, 'index.html'), (err, data) => {
            if (err) { res.writeHead(500); return res.end('index.html not found'); }
            res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
            res.end(data);
        });
        return;
    }

    res.writeHead(404);
    res.end('not found');
});

server.listen(PORT, '127.0.0.1', () => {
    try {
        events = readJson(eventsFile, []);
    } catch (e) { /* первого запуска нет */ }
    logEvent('Сервер панели запущен', 'info');
    console.log(`Minecraft Manager: http://localhost:${PORT}`);
    poll();
    setInterval(poll, POLL_MS);
});
