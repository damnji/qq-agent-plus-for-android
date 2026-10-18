// 手机端部署工具链的契约用例（scripts/phone/）。
//
// 为什么需要：手机路线（proot、无 systemd、无 Docker）与服务器路线是两套完全不同的
// 落地方式，而服务器那套已经有 deploy-*.test.mjs 盯着。手机这套如果没人盯，很容易在
// 后续改动里悄悄退回"依赖 systemctl"或"依赖 jq / python"——那些在 proot 容器里都没有，
// 现场才发现就太晚了。
//
// 这里只做「静态契约 + 纯函数行为」两层，不需要真机、不需要联网：
//   1) 文件存在且可执行、shebang 正确；
//   2) 不许出现 systemctl（有意设计：手机没有 systemd）；
//   3) 不许依赖 jq / python（ubuntu-base 精简 rootfs 里没有）；
//   4) config-init.mjs 的「只补缺、不覆盖」语义（这是最容易改坏的一条）；
//   5) 文档与 NapCat 模板的字段契约（websocketServers 而不是 websocketClients）。
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';

const repo = path.resolve('.');
const phoneDir = path.join(repo, 'scripts', 'phone');

function read(file) {
  return fs.readFileSync(path.join(phoneDir, file), 'utf8');
}
/** 去掉注释行后参与「禁用命令」检查：注释里提到 systemctl 是说明文字，不算违规。 */
function stripComments(source) {
  return source
    .split('\n')
    .filter((line) => !/^\s*#/.test(line))
    .join('\n');
}

test('scripts/phone/ 四个入口都在', () => {
  for (const file of ['install.sh', 'ctl.sh', 'supervise.sh', 'config-init.mjs']) {
    const full = path.join(phoneDir, file);
    assert.ok(fs.existsSync(full), `缺文件：scripts/phone/${file}`);
    const source = fs.readFileSync(full, 'utf8');
    assert.ok(source.length > 500, `${file} 短得不像话，可能被截断了`);
  }
});

test('shell 入口都有 bash shebang 且先 set -uo pipefail', () => {
  for (const file of ['install.sh', 'ctl.sh', 'supervise.sh']) {
    const source = read(file);
    assert.match(source.split('\n')[0], /^#!\/usr\/bin\/env bash$/, `${file} 首行应是 #!/usr/bin/env bash`);
    assert.match(source, /^set -uo pipefail$/m, `${file} 缺 set -uo pipefail`);
  }
});

test('手机工具链不把进程托管交给 systemd（proot 里没有 init）', () => {
  // 允许"只读探测"（install/doctor 里探测 systemd 是否存在，只是为了打印一行提示），
  // 禁止的是"用它托管/启停服务"—— 那是服务器路线的 deploy.sh/manage.sh 在做的事，
  // 在手机容器里必然失败，所以手机这条路必须自带 supervise.sh。
  const forbidden = [
    /systemctl\s+(--user\s+)?(start|stop|restart|enable|disable|daemon-reload|is-active|is-enabled)\b/,
    /loginctl\s+enable-linger/,
    /\bsystemd-analyze\b/
  ];
  for (const file of ['install.sh', 'ctl.sh', 'supervise.sh']) {
    const code = stripComments(read(file));
    for (const pattern of forbidden) {
      assert.ok(!pattern.test(code),
        `${file} 里出现了 ${pattern}：手机容器没有 systemd，这条路必须用 supervise.sh 自守护`);
    }
  }
});

test('手机工具链不依赖 jq / python（精简 rootfs 里不一定有）', () => {
  for (const file of ['install.sh', 'ctl.sh', 'supervise.sh']) {
    const code = stripComments(read(file));
    assert.ok(!/(^|[\s|(`])jq\s/.test(code), `${file} 不该依赖 jq（用 node 读 JSON）`);
    assert.ok(!/\bpython3?\s/.test(code), `${file} 不该依赖 python（用 node）`);
  }
});

test('install.sh 会校验 node:sqlite（本项目硬依赖，缺了启动即失败）', () => {
  const source = read('install.sh');
  assert.match(source, /node:sqlite/, 'install.sh 应探测 node:sqlite 可用性');
});

test('install.sh 的 Node 下载不是只认 nodejs.org（国内常不通）', () => {
  const source = read('install.sh');
  assert.match(source, /nodejs\.org/, '应至少尝试官方源');
  const mirrors = ['mirrors.nju.edu.cn', 'registry.npmmirror.com', 'mirrors.ustc.edu.cn', 'mirrors.aliyun.com'];
  assert.ok(mirrors.some((m) => source.includes(m)), `应有国内镜像兜底，当前只有：${mirrors.join(' / ')}`);
});

test('ctl.sh 的 status 从 /api/status 读正确字段（estimatedYuan，不是 cost）', () => {
  const source = read('ctl.sh');
  assert.match(source, /estimatedYuan/, 'status 应读 usage.estimatedYuan');
  // 只看非注释行：注释里出现 usage.cost 是解释「为什么不能这么读」，不算违规
  const code = source.split('\n')
    .filter((line) => !/^\s*(\/\/|#)/.test(line))
    .join('\n');
  assert.ok(!/usage\.cost\b/.test(code),
    '/api/status 没有 usage.cost 这个键，读它会一直显示 ?');
});

test('ctl.sh 的 mode active 带 confirmExclusive（否则服务返回 409）', () => {
  const source = read('ctl.sh');
  // JSON 里键名带引号：`{"mode":"active","confirmExclusive":true}`
  assert.match(source, /confirmExclusive"?\s*:\s*true/,
    'POST /api/runtime 切 active 时必须 confirmExclusive:true，否则 409');
});

test('supervise.sh 会转发 SIGTERM 并支持退避（= systemd Restart 的最小替代）', () => {
  const source = read('supervise.sh');
  assert.match(source, /trap shutdown TERM INT/, '应捕获 TERM/INT');
  assert.match(source, /kill -TERM "\$child"/, '停止时应先把 TERM 转发给 node，让它优雅退出');
  assert.match(source, /RESTART_DELAY/, '重起间隔应可配置');
  assert.match(source, /MAX_FAST_FAILS|fast_fails/, '应有快速失败保护，避免配置错误时疯狂重启');
});

test('config-init.mjs：新建时写入最小可用配置', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'qq-phone-cfg-'));
  try {
    execFileSync(process.execPath, [
      path.join(phoneDir, 'config-init.mjs'),
      '--data-dir', dir,
      '--console-port', '13310',
      '--ws-port', '3101',
      '--http-port', '3100',
      '--token', 'test-token-abcdefghijklmnop',
      '--model', 'mock-model',
      '--allow-group', '90001'
    ], { encoding: 'utf8' });
    const cfg = JSON.parse(fs.readFileSync(path.join(dir, 'config.json'), 'utf8'));
    assert.equal(cfg.server.port, 13310);
    assert.equal(cfg.server.host, '127.0.0.1');
    assert.equal(cfg.onebot.wsUrl, 'ws://127.0.0.1:3101');
    assert.equal(cfg.onebot.httpUrl, 'http://127.0.0.1:3100');
    assert.equal(cfg.onebot.accessToken, 'test-token-abcdefghijklmnop');
    assert.equal(cfg.onebot.httpAccessToken, 'test-token-abcdefghijklmnop');
    // 手机侧必须显式关掉 NapCat 的 PING（见 docs/CONFIG-EXAMPLES.md）
    assert.equal(cfg.onebot.wsHeartbeat, 'auto');
    assert.equal(cfg.api.model, 'mock-model');
    // 安全默认：observe + 白名单制，装完不会立刻在群里说话
    assert.equal(cfg.runtime.mode, 'observe');
    assert.equal(cfg.allowAllWhenEmpty, false);
    assert.deepEqual(cfg.allow.groups, ['90001']);
    // 自动更新在手机上是坏的（它要 systemctl --user），必须默认关
    assert.equal(cfg.autoUpdate.enabled, false);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('config-init.mjs：重跑只补缺，不覆盖用户已填的值', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'qq-phone-cfg2-'));
  try {
    const file = path.join(dir, 'config.json');
    fs.writeFileSync(file, JSON.stringify({
      server: { port: 3210 },
      onebot: { wsUrl: 'ws://127.0.0.1:3001', accessToken: 'user-token' },
      api: { baseUrl: 'https://api.example.com/v1', apiKey: 'sk-user', model: 'user-model' },
      runtime: { mode: 'active' },
      allow: { groups: ['111111'] },
      persona: { botName: '我的人设' }
    }));
    execFileSync(process.execPath, [
      path.join(phoneDir, 'config-init.mjs'),
      '--data-dir', dir,
      '--model', 'should-not-win',
      '--allow-group', '222222'
    ], { encoding: 'utf8' });
    const cfg = JSON.parse(fs.readFileSync(file, 'utf8'));
    assert.equal(cfg.onebot.accessToken, 'user-token', '不能把用户的 token 换掉');
    assert.equal(cfg.api.model, 'user-model', '不能把用户选的模型顶掉');
    assert.equal(cfg.api.apiKey, 'sk-user', '不能覆盖 API Key');
    assert.equal(cfg.api.baseUrl, 'https://api.example.com/v1');
    assert.equal(cfg.runtime.mode, 'active', '运行模式是用户的选择，不能被重置成 observe');
    assert.equal(cfg.persona.botName, '我的人设', '其它已有配置必须原样保留');
    // 显式 --allow-group 是"追加"语义（这条是给手机上加群用的，覆盖会误删）
    assert.deepEqual([...cfg.allow.groups].sort(), ['111111', '222222']);
    // 缺的键仍应被补上
    assert.equal(cfg.onebot.wsHeartbeat, 'auto');
    assert.equal(cfg.allowAllWhenEmpty, false);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('config-init.mjs：已有文件不是合法 JSON 时拒绝处理（不写坏数据）', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'qq-phone-cfg3-'));
  try {
    const file = path.join(dir, 'config.json');
    fs.writeFileSync(file, '{ this is not json');
    const result = spawnSync(process.execPath, [path.join(phoneDir, 'config-init.mjs'), '--data-dir', dir], { encoding: 'utf8' });
    assert.notEqual(result.status, 0, '坏 JSON 应非零退出');
    assert.match(result.stderr, /不是合法 JSON/, '应说明原因');
    assert.equal(fs.readFileSync(file, 'utf8'), '{ this is not json', '不能改写坏文件');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('NapCat 模板用 websocketServers（正向），不能是 websocketClients', () => {
  const file = path.join(repo, 'docs', 'assets', 'napcat-onebot11.example.json');
  assert.ok(fs.existsSync(file), '缺 docs/assets/napcat-onebot11.example.json');
  // 模板里有 _comment 说明字段，不是严格 JSON：去掉注释字段再看结构
  const raw = JSON.parse(fs.readFileSync(file, 'utf8'));
  const network = raw.network;
  assert.ok(Array.isArray(network.websocketServers) && network.websocketServers.length > 0,
    '必须开 websocketServers：本项目是 WS 客户端，NapCat 要当服务端');
  assert.deepEqual(network.websocketClients, [],
    'websocketClients 是反向 WS（AstrBot 那种），本项目配了会收不到事件');
  const ws = network.websocketServers[0];
  assert.equal(ws.enable, true);
  assert.equal(ws.port, 3001, '与 onebot.wsUrl 默认端口一致');
  assert.equal(ws.host, '127.0.0.1');
  assert.equal(ws.messagePostFormat, 'array', '本项目按消息段数组解析');
  assert.ok(typeof ws.token === 'string' && ws.token.length > 0, '必须有占位 token');
  assert.ok(typeof ws.heartInterval === 'number');
  const http = network.httpServers[0];
  assert.equal(http.enable, true);
  assert.equal(http.port, 3000, '与 onebot.httpUrl 默认端口一致');
  assert.ok(typeof http.token === 'string' && http.token.length > 0);
});

test('docs/PHONE.md 覆盖了手机路线的关键约束', () => {
  const file = path.join(repo, 'docs', 'PHONE.md');
  assert.ok(fs.existsSync(file), '缺 docs/PHONE.md');
  const source = fs.readFileSync(file, 'utf8');
  const mustMention = [
    ['proot', '手机路线就是 proot 容器'],
    ['websocketServers', 'NapCat 必须开正向 WS 服务端'],
    ['NAPCAT_WORKDIR', 'NapCat 配置落盘位置，不设就丢配置'],
    ['QQ_AGENT_DATA_DIR', '本项目数据目录必须显式指定'],
    ['node:sqlite', 'Node 版本门槛'],
    ['ctl.sh', '启停入口'],
    ['observe', '默认只观察不发言'],
    ['allow', '白名单制，不填群号不回复'],
    ['termux-wake-lock', '安卓保活第一步'],
    ['--no-sandbox', 'QQ 在 proot 里必须这么起']
  ];
  for (const [needle, why] of mustMention) {
    assert.ok(source.includes(needle), `docs/PHONE.md 应提到「${needle}」——${why}`);
  }
});

test('ctl.sh 的 HTTP 状态码不会拼成 000000', () => {
  const source = read('ctl.sh');
  // curl 连不上时自己会打印 000 且退出码非零；再 `|| echo 000` 就变成 000000
  // （2026-10-18 实测踩到，届时报错文案会变成 "HTTP 000000"，看不出是连不上）
  const bad = source.split('\n').filter((line) => !/^\s*#/.test(line)
    && /-w '%\{http_code\}'/.test(line) && /\|\|\s*echo\s+000/.test(line));
  assert.deepEqual(bad, [], `这些行会把 http_code 拼成 000000，改用 \${code:-000}：\n${bad.join('\n')}`);
});

test('docs/README.md 已索引 PHONE.md（docs-index.test.mjs 依赖它）', () => {
  const source = fs.readFileSync(path.join(repo, 'docs', 'README.md'), 'utf8');
  assert.ok(source.includes('(PHONE.md)'), 'docs/README.md 的索引表里要加 PHONE.md');
});
