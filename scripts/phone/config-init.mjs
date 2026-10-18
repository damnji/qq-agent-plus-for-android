#!/usr/bin/env node
// 写 / 补 data/config.json（手机端一键安装用）。
//
// 为什么不用控制台：手机首次配置时控制台地址还没出来、模型还没配，命令行一次写完好排查。
// 设计约束（重要）：**已经在的配置一个字都不覆盖** —— 手机上的 config.json 里可能已经有
// 用户填的 API Key、群白名单、人设，重跑安装脚本不该把它冲掉。只做三件事：
//   1) 文件不存在 → 按参数生成一份最小可用配置；
//   2) 文件存在 → 只补**确实缺失**的键（逐层 merge，缺什么补什么）；
//   3) 打印最终生效的关键字段（token 打码），便于确认。
//
// 用法：node scripts/phone/config-init.mjs --data-dir <dir> [--console-port 3210]
//        [--ws-port 3001] [--http-port 3000] [--token xxx]
//        [--model-base-url u] [--model-api-key k] [--model m] [--allow-group gid]
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

function parseArgs(argv) {
  const out = {
    dataDir: '',
    consolePort: 3210,
    wsPort: 3001,
    httpPort: 3000,
    token: '',
    modelBaseUrl: '',
    modelApiKey: '',
    model: '',
    allowGroup: [],
    // 允许显式覆盖已有的模型配置（默认不允许：重跑安装脚本不该冲掉用户填的 Key）
    force: false
  };
  for (let i = 0; i < argv.length; i += 1) {
    const flag = argv[i];
    const next = () => {
      const value = argv[i + 1];
      if (value === undefined) throw new Error(`${flag} 缺少取值`);
      i += 1;
      return value;
    };
    switch (flag) {
      case '--data-dir': out.dataDir = next(); break;
      case '--console-port': out.consolePort = Number(next()); break;
      case '--ws-port': out.wsPort = Number(next()); break;
      case '--http-port': out.httpPort = Number(next()); break;
      case '--token': out.token = next(); break;
      case '--model-base-url': out.modelBaseUrl = next(); break;
      case '--model-api-key': out.modelApiKey = next(); break;
      case '--model': out.model = next(); break;
      case '--allow-group': out.allowGroup.push(String(next())); break;
      case '--force': out.force = true; break;
      default: throw new Error(`未知参数：${flag}`);
    }
  }
  if (!out.dataDir) throw new Error('必须给 --data-dir');
  for (const [name, value] of [['--console-port', out.consolePort], ['--ws-port', out.wsPort], ['--http-port', out.httpPort]]) {
    if (!Number.isInteger(value) || value < 1 || value > 65535) throw new Error(`${name} 不是合法端口：${value}`);
  }
  return out;
}

/** 生成一个够长、只含 URL 安全字符的随机 token。 */
function randomToken() {
  return crypto.randomBytes(24).toString('base64url');
}

const isPlainObject = (value) => Boolean(value) && typeof value === 'object' && !Array.isArray(value);

/**
 * 把 defaults 里 defaults **有而 current 没有**的键补进 current。
 * 数组与标量：current 有就保留（哪怕是空数组/空串 —— 那是用户的选择）。
 */
function fillMissing(current, defaults) {
  if (!isPlainObject(current)) return defaults;
  const out = { ...current };
  for (const [key, value] of Object.entries(defaults)) {
    if (!(key in out)) {
      out[key] = value;
    } else if (isPlainObject(value) && isPlainObject(out[key])) {
      out[key] = fillMissing(out[key], value);
    }
  }
  return out;
}

function maskSecret(value) {
  const text = String(value || '');
  if (!text) return '（未设置）';
  if (text.length <= 8) return `${text.slice(0, 2)}***`;
  return `${text.slice(0, 4)}***${text.slice(-2)}（${text.length} 字符）`;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const dataDir = path.resolve(args.dataDir);
  fs.mkdirSync(dataDir, { recursive: true });
  const file = path.join(dataDir, 'config.json');

  const existed = fs.existsSync(file);
  let current = {};
  if (existed) {
    try {
      current = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (error) {
      throw new Error(`已存在的 ${file} 不是合法 JSON（${error?.message ?? error}）。` +
        '请先备份/修好它，本脚本不会覆盖坏文件。');
    }
    if (!isPlainObject(current)) throw new Error(`${file} 顶层不是对象，拒绝处理`);
  }

  // token：已存在就用已有的；否则用 --token；都没有就随机生成一个
  const existingToken = String(current?.onebot?.accessToken || '');
  const token = existingToken || args.token || randomToken();

  const defaults = {
    server: {
      host: '127.0.0.1',
      port: args.consolePort,
      strictPort: true,
      token: ''
    },
    onebot: {
      wsUrl: `ws://127.0.0.1:${args.wsPort}`,
      httpUrl: `http://127.0.0.1:${args.httpPort}`,
      accessToken: token,
      httpAccessToken: token,
      // NapCat 收到协议层 PING 会断连接，保持 auto（见 docs/CONFIG-EXAMPLES.md）
      wsHeartbeat: 'auto'
    },
    // 手机上默认先 observe：装完不会立刻在群里说话，人确认配置后再切 active
    runtime: { mode: 'observe', paused: false },
    api: {
      baseUrl: args.modelBaseUrl || '',
      apiKey: args.modelApiKey || '',
      model: args.model || ''
    },
    // 白名单默认空 + allowAllWhenEmpty=false ⇒ 不填群号就什么都不回（故意的安全默认）
    allow: { groups: args.allowGroup, private: [] },
    allowAllWhenEmpty: false,
    admin: { ownerUin: '' },
    // 手机上关掉这些"会自己往外跑"的功能，减少第一次跑起来的变量
    autoUpdate: { enabled: false },
    groupDigest: { enabled: false },
    proactive: { enabled: false }
  };

  // 已有配置里显式写了模型/白名单，就别用参数顶掉它（只补缺）
  if (existed && isPlainObject(current.api)) {
    if (String(current.api.baseUrl || '').trim()) delete defaults.api.baseUrl;
    if (String(current.api.apiKey || '').trim()) delete defaults.api.apiKey;
    if (String(current.api.model || '').trim()) delete defaults.api.model;
  }
  if (existed && Array.isArray(current?.allow?.groups) && current.allow.groups.length > 0) {
    delete defaults.allow.groups;
  }

  const merged = fillMissing(current, defaults);
  // 显式传的模型参数：**只用于把空值填上**，绝不顶掉用户已经填好的值。
  // 手机上 config.json 里的 key/model 是用户花钱配的，重跑安装脚本把它冲掉是最招人烦的事故。
  // 真要改模型：控制台里改，或加 --force。
  const applied = [];
  const skipped = [];
  const applyExplicit = (key, value) => {
    if (!value) return;
    const currentValue = String(merged?.api?.[key] ?? '').trim();
    if (currentValue && !args.force) {
      skipped.push(`${key}=${currentValue}`);
      return;
    }
    merged.api[key] = value;
    applied.push(`${key}=${value}`);
  };
  if (!isPlainObject(merged.api)) merged.api = {};
  applyExplicit('baseUrl', args.modelBaseUrl);
  applyExplicit('apiKey', args.modelApiKey);
  applyExplicit('model', args.model);
  // 白名单是**追加**语义（加群不该删掉已有群）；要清空请用 ctl.sh allow ""
  if (args.allowGroup.length > 0) {
    const groups = new Set([...(Array.isArray(merged?.allow?.groups) ? merged.allow.groups : []).map(String)]);
    for (const group of args.allowGroup) groups.add(String(group));
    if (!isPlainObject(merged.allow)) merged.allow = {};
    merged.allow.groups = [...groups];
  }
  if (skipped.length > 0) {
    console.log('[phone-config] 以下模型项已有值，本次未覆盖（要覆盖加 --force）：');
    for (const item of skipped) console.log(`[phone-config]   ${item}`);
  }
  if (applied.length > 0) {
    console.log(`[phone-config] 已写入模型项：${applied.join(', ')}`);
  }

  // 原子写：先写临时文件再 rename，避免断电留下半个 config.json
  const tmp = `${file}.tmp-${process.pid}`;
  fs.writeFileSync(tmp, `${JSON.stringify(merged, null, 2)}\n`, { mode: 0o600 });
  fs.renameSync(tmp, file);
  try { fs.chmodSync(file, 0o600); } catch { /* 某些文件系统不支持，忽略 */ }

  const mode = String(merged?.runtime?.mode || 'observe');
  console.log(`[phone-config] ${existed ? '已更新（只补缺）' : '已新建'}：${file}`);
  console.log(`[phone-config]   控制台     http://${merged.server?.host}:${merged.server?.port}`);
  console.log(`[phone-config]   OneBot WS  ${merged.onebot?.wsUrl}`);
  console.log(`[phone-config]   OneBot HTTP ${merged.onebot?.httpUrl}`);
  console.log(`[phone-config]   token      ${maskSecret(merged.onebot?.accessToken)}`);
  console.log(`[phone-config]   模型       ${merged.api?.model || '（未设置，控制台里填）'}`);
  console.log(`[phone-config]   白名单群   ${(merged.allow?.groups || []).join(', ') || '（空 = 不回复任何群）'}`);
  console.log(`[phone-config]   运行模式   ${mode}${mode === 'observe' ? '（只观察不发言，确认配置后用 ctl.sh mode active 切换）' : ''}`);
}

try {
  main();
} catch (error) {
  console.error(`[phone-config] 失败：${error?.message ?? error}`);
  process.exit(1);
}
