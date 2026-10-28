#!/usr/bin/env node
'use strict';

// One run of the Docker integration test: the plugin jar against one real Halo version, with the
// local mock of MomoBird's connect API (dev/mock-connect.js). Started by scripts/integration.sh,
// which owns cleanup; this process also removes what it started when it is stopped.
//
//   node scripts/integration.js --version 2.17 --jar build/libs/halo-momobird-ai-0.1.0.jar \
//        --work /tmp/dir --run-id abc123 [--mock-host 127.0.0.1]
//
// Every check prints PASS or FAIL with its evidence; the exit status is 1 when any failed. Check
// numbers: 1 to 10 are the ten live checks carried over from the plugin's reviews, S is the post
// life cycle scenario, X are extra scenarios.
//
// Halo needs several seconds for everything; one version takes about six minutes, of which one
// is spent waiting for the plugin's first full sync (it runs a minute after the plugin starts).

const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn } = require('child_process');
const L = require('./integration-lib');

const { KEY, BAD_KEY, SITE_URL, SOURCE_ID, ADMIN, USERS, ROLES, sleep, postId, median, percentile, waitFor, Halo, Mock, request } = L;

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 2) out[argv[i].replace(/^--/, '')] = argv[i + 1];
  return out;
}

const args = parseArgs(process.argv.slice(2));
const VERSION = args.version;
const JAR = path.resolve(args.jar);
const WORK = args.work;
const RUN_ID = args['run-id'] || String(process.pid);
const MOCK_HOST = args['mock-host'] || '127.0.0.1';
const V = VERSION;
if (!VERSION || !args.jar || !WORK) {
  console.error('usage: integration.js --version V --jar JAR --work DIR [--run-id ID] [--mock-host HOST]');
  process.exit(2);
}
fs.mkdirSync(WORK, { recursive: true });

// ----- verdicts ---------------------------------------------------------------------------------

const results = [];

function check(id, title, ok, evidence) {
  results.push({ id, title, ok, evidence: String(evidence) });
  console.log((ok ? 'PASS' : 'FAIL') + ' [' + V + '] ' + id + '  ' + title + '\n       ' + String(evidence).replace(/\n/g, '\n       '));
}

// A check that was not run on purpose; it is listed in the summary, not counted as passed or failed.
function skip(id, title, reason) {
  results.push({ id, title, ok: true, skipped: true, evidence: reason });
  console.log('SKIPPED [' + V + '] ' + id + '  ' + title + '\n       ' + reason);
}

// Runs fn; what it returns is the evidence of a PASS, what it throws the evidence of a FAIL.
async function step(id, title, fn) {
  try {
    check(id, title, true, await fn());
  } catch (err) {
    check(id, title, false, err && err.message ? err.message : String(err));
  }
}

function fail(message) {
  throw new Error(message);
}

function must(condition, message) {
  if (!condition) fail(message);
}

// ----- cleanup -----------------------------------------------------------------------------------

const toStop = [];
const cleanups = [];
let cleaned = false;

function cleanup() {
  if (cleaned) return;
  cleaned = true;
  // Last registered first: the mock and the browser go before the (slow) container removal.
  for (const fn of cleanups.splice(0).reverse()) {
    try {
      fn();
    } catch (err) {
      // best effort
    }
  }
}

for (const [signal, code] of [['SIGINT', 130], ['SIGTERM', 143], ['SIGHUP', 129]]) {
  process.on(signal, () => {
    console.error('[' + V + '] ' + signal + ' received, cleaning up');
    cleanup();
    process.exit(code);
  });
}
process.on('exit', cleanup);

// ----- the mock ------------------------------------------------------------------------------------

async function startMock(name) {
  const logFile = path.join(WORK, name + '.jsonl');
  const portFile = path.join(WORK, name + '.port');
  const out = fs.openSync(path.join(WORK, name + '.out'), 'w');
  const child = spawn(process.execPath, [path.join(__dirname, '..', 'dev', 'mock-connect.js'), '--site-url', SITE_URL,
    '--log', logFile, '--port-file', portFile, '--host', MOCK_HOST], { stdio: ['ignore', out, out] });
  cleanups.push(() => child.kill('SIGKILL'));
  await waitFor('the mock to listen', async () => fs.existsSync(portFile) && fs.readFileSync(portFile, 'utf8').trim() !== '', { timeout: 10000, interval: 100 });
  return new Mock(parseInt(fs.readFileSync(portFile, 'utf8').trim(), 10));
}

// ----- the browser ----------------------------------------------------------------------------------

// Playwright comes from PLAYWRIGHT_DIR (a node_modules/playwright directory) or the usual Node resolution
// (this repository, ui/, the current directory). The browser is Playwright's own unless PLAYWRIGHT_CHROMIUM names an executable.
function loadPlaywright() {
  const attempts = [];
  const tries = process.env.PLAYWRIGHT_DIR ? [process.env.PLAYWRIGHT_DIR] : [];
  tries.push('playwright');
  for (const target of tries) {
    try {
      return require(require.resolve(target, { paths: [process.cwd(), __dirname, path.join(__dirname, '..', 'ui')] }));
    } catch (err) {
      attempts.push(target + ': ' + err.message.split('\n')[0]);
    }
  }
  fail('Playwright was not found (' + attempts.join('; ') + '). Set PLAYWRIGHT_DIR to a node_modules/playwright directory, or SKIP_BROWSER=1 to skip the console checks');
}

async function launchBrowser() {
  const playwright = loadPlaywright();
  const executablePath = process.env.PLAYWRIGHT_CHROMIUM || undefined;
  try {
    return await playwright.chromium.launch(executablePath ? { executablePath } : {});
  } catch (err) {
    fail('no usable browser (' + err.message.split('\n')[0] + '). Run "npx playwright install chromium", or set PLAYWRIGHT_CHROMIUM to a Chromium / chrome-headless-shell executable, or SKIP_BROWSER=1');
  }
}

// Logs in as the user in a fresh context, looks at the sidebar and opens the MomoBird page.
async function browse(browser, halo, user) {
  const context = await browser.newContext({ locale: 'zh-CN' });
  const page = await context.newPage();
  const consoleErrors = [];
  const pageErrors = [];
  const failedRequests = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 300) + ' @' + (m.location().url || '').replace(halo.base, ''));
  });
  page.on('response', (r) => {
    if (r.status() >= 400) failedRequests.push(r.status() + ' ' + r.url().replace(halo.base, '').slice(0, 120));
  });
  page.on('pageerror', (e) => pageErrors.push(String(e).slice(0, 300)));
  try {
    // Newer versions serve a login form at /login; 2.17 has it inside the console, at /console/login.
    await page.goto(halo.base + '/login', { waitUntil: 'load' });
    if (!(await page.waitForSelector('input[name=username]', { timeout: 8000 }).then(() => true, () => false))) {
      await page.goto(halo.base + '/console/login', { waitUntil: 'load' });
    }
    // The username field and the visible password field (newer versions also keep a hidden, encrypted copy).
    await page.fill('input[name=username]', user.name);
    await page.fill('input[type=password]', user.password);
    await page.press('input[type=password]', 'Enter');
    const left = () => page.waitForURL((u) => !/login/.test(u.pathname), { timeout: 8000 }).then(() => true, () => false);
    if (!(await left())) {
      // 2.17's login is a button of type "button": Enter alone may not submit it.
      await page.click('form button[type=submit], button.btn-secondary, button:has-text("登录"), button:has-text("Login")').catch(() => {});
    }
    await page.waitForURL((u) => /^\/(console|uc)/.test(u.pathname) && !/login/.test(u.pathname), { timeout: 30000 }).catch(async (err) => {
      throw new Error('login of ' + user.name + ' did not finish: now at ' + page.url() + ', page says: ' + (await page.innerText('body').catch(() => '')).replace(/\s+/g, ' ').slice(0, 200));
    });
    await page.goto(halo.base + '/console/dashboard', { waitUntil: 'networkidle' });
    // The console renders after its scripts ran: wait for the sidebar (or the refusal page) before reading the menu.
    await page.waitForFunction(() => /仪表盘|没有权限/.test(document.body.innerText), null, { timeout: 45000 }).catch(() => {});
    await page.waitForTimeout(1500);
    const menu = await page.evaluate(() => [...document.querySelectorAll('*')]
      .filter((e) => e.children.length === 0 && e.textContent.trim() === 'MomoBird').length > 0);
    await page.goto(halo.base + '/console/momobird', { waitUntil: 'networkidle' });
    const loaded = await page.waitForFunction(() => /连接状态|还没有配置好|没有权限/.test(document.body.innerText), null, { timeout: 30000 })
      .then(() => true, () => false);
    const text = (await page.innerText('body')).replace(/\s+/g, ' ');
    return {
      menu, url: page.url(), loaded,
      pageShown: /连接状态|还没有配置好/.test(text), denied: /没有权限|\/403/.test(text + page.url()),
      text: text.slice(0, 400), consoleErrors, pageErrors, failedRequests
    };
  } finally {
    await context.close();
  }
}

// ----- the scenario -----------------------------------------------------------------------------------

const adminResponses = [];

function logCount(halo, pattern) {
  return (halo.logs().match(new RegExp(pattern, 'g')) || []).length;
}

function momoCall(halo, user, method, p, options) {
  return request(halo.base, method, '/apis/' + L.API + p, Object.assign({ user, headers: { 'X-Requested-With': 'XMLHttpRequest' } }, options || {}))
    .then((r) => {
      adminResponses.push({ who: user ? user.name : 'anonymous', path: p, status: r.status, text: r.text });
      return r;
    });
}

const admin = (halo, method, p, options) => momoCall(halo, ADMIN, method, p, options);

function textOf(value) {
  return JSON.stringify(value);
}

function leaks(text) {
  const found = [];
  for (const [label, pattern] of [['the key', KEY], ['the wrong key', BAD_KEY], ['HALOLOCALTESTKEY', 'HALOLOCALTESTKEY'], ['mbk_', 'mbk_']]) {
    if (text.indexOf(pattern) !== -1) found.push(label);
  }
  return found;
}

async function main() {
  const halo = new Halo({
    image: 'halohub/halo:' + VERSION, name: 'halo-it-' + RUN_ID + '-' + VERSION.replace(/\./g, ''), runId: RUN_ID, version: VERSION,
    extraArgs: ['--halo.external-url=' + SITE_URL]
  });
  cleanups.push(() => halo.remove());
  const mock = await startMock('mock-' + VERSION);
  const settings = (overrides) => Object.assign({
    connection: { baseUrl: mock.baseForHalo, key: KEY },
    widget: { enabled: true, position: 'right' },
    podcast: { player: true, transcript: false }
  }, overrides || {});

  console.log('=== Halo ' + VERSION + ' (image ' + halo.image + '), mock ' + mock.base + ' for the container as ' + mock.baseForHalo + ' ===');
  halo.start();
  await halo.ready();
  await halo.initialize();
  const info = await halo.api('GET', '/actuator/info');
  console.log('Halo is up at ' + halo.base + (info.json && info.json.build ? ', build ' + info.json.build.version : ''));

  // ------------------------------------------------------------------ the posts, before the plugin
  const samples = await halo.removeAllPosts();
  console.log('removed ' + samples.length + ' sample post(s) the fresh Halo came with: ' + (samples.join(', ') || 'none'));
  const Q = {
    pub: 'it-public', priv: 'it-private', excl: 'it-excluded', draft: 'it-draft', trash: 'it-trashed'
  };
  await halo.createPost({
    name: Q.pub, title: '公开的测试文章', slug: Q.pub,
    html: '<h2>第一节</h2><p>这是 <strong>公开</strong> 文章的正文，含有独特词语 柚子汽水。</p><ul><li>条目一</li><li>条目二</li></ul>'
      + '<pre><code>int answer = 42;</code></pre><script>alert("must-not-be-indexed")</script><style>.x{color:red}</style>'
  });
  await halo.createPost({ name: Q.priv, title: '私有文章', slug: Q.priv, visible: 'PRIVATE', html: '<p>私有正文 不应该同步 荔枝气泡水</p>' });
  await halo.createPost({ name: Q.excl, title: '排除的文章', slug: Q.excl, annotations: { 'momobird.ai/exclude': 'true' }, html: '<p>排除正文 不应该同步 杨梅苏打</p>' });
  await halo.createPost({ name: Q.draft, title: '草稿文章', slug: Q.draft, publish: false, html: '<p>草稿正文</p>' });
  await halo.createPost({ name: Q.trash, title: '回收站文章', slug: Q.trash, html: '<p>回收站正文</p>' });
  await halo.recyclePost(Q.trash);
  await halo.createPage('it-page', '独立页面');
  await sleep(3000);

  // ------------------------------------------------------------------ 7: rendering cost, before
  async function timing(label, p, n, warm) {
    const times = [];
    let last;
    for (let i = 0; i < n + warm; i += 1) {
      last = await halo.site(p);
      if (last.status !== 200) fail(label + ' ' + p + ' answered ' + last.status);
      if (i >= warm) times.push(last.ms);
    }
    return { median: median(times), p95: percentile(times, 0.95), last };
  }
  const base = {
    home: await timing('without', '/', 30, 6),
    post: await timing('without', '/archives/' + Q.pub, 30, 6)
  };
  check('7.0', 'baseline without the plugin: home page and a post page render (200, with the post)',
    base.home.last.text.indexOf('公开的测试文章') !== -1 && base.post.last.text.indexOf('柚子汽水') !== -1,
    'home median ' + base.home.median.toFixed(1) + ' ms (p95 ' + base.home.p95.toFixed(1) + '), post median ' + base.post.median.toFixed(1)
      + ' ms (p95 ' + base.post.p95.toFixed(1) + ')');

  // ------------------------------------------------------------------ install, settings, enable
  await halo.installPlugin(JAR);
  await halo.writeSettings(settings());
  const enabledAt = Date.now();
  await halo.setPlugin(true);
  const afterEnable = Date.now();
  // The log may lag the plugin state by a moment.
  const startedLine = await waitFor('the start line', async () => halo.logs().match(/\[MomoBird\] 同步服务已启动[^\n]*/), { timeout: 20000 }).catch(() => null);
  await step('4.1', 'enabling the plugin logs "[MomoBird] 同步服务已启动"', async () => {
    must(startedLine, 'no such line in the Halo log; plugin phase ' + (await halo.pluginPhase()));
    return startedLine[0].replace(/^.*?\[MomoBird\]/, '[MomoBird]');
  });

  // The roles and users, while the plugin counts down to its first full sync.
  for (const [role, template] of Object.entries(ROLES)) await halo.createRole(role, template);
  for (const user of Object.values(USERS)) await halo.createUser(user);

  // ------------------------------------------------------------------ 4: the first full sync
  await step('4.2', 'no request to MomoBird during the first 40 seconds (the full sync waits a minute)', async () => {
    await sleep(Math.max(0, 40000 - (Date.now() - enabledAt)));
    const n = await mock.count();
    must(n === 0, 'the mock already saw ' + n + ' requests: ' + (await mock.summary(0)).join('; '));
    return 'requests so far: 0, ' + Math.round((Date.now() - enabledAt) / 1000) + ' s after enabling';
  });
  let firstPush;
  await step('4.3', 'the first full sync runs about a minute after enabling and pushes only the public post', async () => {
    firstPush = await waitFor('the first PUT /posts', async () => {
      const r = (await mock.requests(0)).requests;
      return r.find((e) => e.method === 'PUT');
    }, { timeout: 60000, interval: 1000 });
    const delay = (Date.parse(firstPush.time) - enabledAt) / 1000;
    const ids = await mock.ids();
    const summary = (await mock.summary(0)).join('; ');
    must(delay >= 45 && delay <= 90, 'the first push came ' + delay.toFixed(1) + ' s after enabling (expected ~60): ' + summary);
    must(ids.length === 1 && ids[0] === postId(Q.pub), 'mock holds ' + JSON.stringify(ids) + ', expected only ' + postId(Q.pub) + ' (' + Q.pub + '); ' + summary);
    return 'first request ' + delay.toFixed(1) + ' s after enabling; mock holds ' + JSON.stringify(ids) + '; ' + summary;
  });
  await sleep(1500);

  await step('S.1', 'only the public post reaches MomoBird: private, excluded, draft, trashed posts and the page stay out', async () => {
    const all = (await mock.requests(0)).requests.filter((e) => e.method === 'PUT').flatMap((e) => e.body.posts);
    const sent = all.map((p) => p.external_id);
    const unwanted = [Q.priv, Q.excl, Q.draft, Q.trash, 'it-page'].map(postId).filter((id) => sent.indexOf(id) !== -1);
    must(unwanted.length === 0, 'these ids were pushed: ' + unwanted.join(','));
    const post = all.find((p) => p.external_id === postId(Q.pub));
    must(post.url === SITE_URL + '/archives/' + Q.pub, 'unexpected url ' + post.url);
    return 'pushed ids ' + JSON.stringify(sent) + '; url ' + post.url + '; category ' + (post.category === undefined ? 'none' : post.category);
  });

  await step('1', 'jsoup is available to the plugin: released HTML is converted to indexed text', async () => {
    const post = (await mock.requests(0)).requests.filter((e) => e.method === 'PUT').flatMap((e) => e.body.posts).find((p) => p.external_id === postId(Q.pub));
    const c = post.content;
    must(c.indexOf('# 公开的测试文章') === 0, 'content does not start with the title heading: ' + JSON.stringify(c.slice(0, 80)));
    must(c.indexOf('## 第一节') !== -1 && c.indexOf('柚子汽水') !== -1 && c.indexOf('int answer = 42;') !== -1, 'body text missing: ' + JSON.stringify(c));
    must(!/<[a-z]/i.test(c) && c.indexOf('must-not-be-indexed') === -1 && c.indexOf('color:red') === -1, 'markup or script text leaked into the content: ' + JSON.stringify(c));
    const noisy = (halo.logs().match(/NoClassDefFoundError|ClassNotFoundException[^\n]*jsoup/g) || []).length;
    must(noisy === 0, 'class loading errors in the log');
    return 'pushed content: ' + JSON.stringify(c);
  });

  await step('3', 'Halo lists drafts and trashed posts with empty ListOptions; the sync rules filter them', async () => {
    const all = await halo.allPostsViaApi();
    const draft = all.find((p) => p.name === Q.draft);
    const trashed = all.find((p) => p.name === Q.trash);
    must(draft && !draft.publish && trashed && trashed.deleted, 'the extension list lacks the draft or the trashed post: ' + JSON.stringify(all));
    const status = await admin(halo, 'GET', '/status?check=false');
    const last = status.json.sync.last;
    return 'extension listing without filters returns ' + all.length + ' posts incl. draft (' + Q.draft + ': publish=' + draft.publish + ') and trashed ('
      + Q.trash + ': deleted=' + trashed.deleted + '); the first full sync pushed ' + last.pushed + ' of them (the public one) and left the other ' + (all.length - last.pushed)
      + ' out (the mock holds ' + (await mock.ids()).length + ')';
  });

  // ------------------------------------------------------------------ 6, 7: the site
  let home = '';
  let postPage = '';
  const siteReady = await waitFor('the assistant script on the home page', async () => {
    const r = await halo.site('/');
    return r.text.indexOf('loader.v1.js') !== -1 ? r : null;
  }, { timeout: 30000, interval: 1000 }).catch(() => null);
  home = siteReady ? siteReady.text : (await halo.site('/')).text;
  postPage = (await halo.site('/archives/' + Q.pub)).text;
  await step('6', 'the assistant <script> is inside <head>, before </head>', async () => {
    const tag = home.match(/<script src="https:\/\/momobird\.atlaspaces\.com\/widget\/loader\.v1\.js"[^>]*><\/script>/);
    must(tag, 'no loader script on the home page; head: ' + JSON.stringify((home.match(/<head[\s\S]*?<\/head>/) || [home.slice(0, 400)])[0].slice(-600)));
    const open = home.indexOf('<head');
    const close = home.indexOf('</head>');
    const at = home.indexOf(tag[0]);
    must(open !== -1 && close !== -1 && at > open && at < close, 'script at ' + at + ' but <head> is ' + open + '..' + close);
    must(tag[0].indexOf('data-project="pub_halo_local"') !== -1 && tag[0].indexOf('data-position="right"') !== -1, 'unexpected attributes: ' + tag[0]);
    const onPost = postPage.indexOf('loader.v1.js') > postPage.indexOf('<head') && postPage.indexOf('loader.v1.js') < postPage.indexOf('</head>');
    must(onPost, 'the script is not inside <head> on the post page');
    return tag[0] + ' at offset ' + at + ' (<head> at ' + open + ', </head> at ' + close + ') on the home page and in <head> on the post page';
  });
  await step('X.podcast', 'a public post page carries the podcast placeholder and script; an excluded post does not', async () => {
    const holder = postPage.match(/<div class="momobird-podcast"[^>]*><\/div>/);
    must(holder, 'no podcast placeholder on the post page');
    must(holder[0].indexOf('data-source="' + SOURCE_ID + '"') !== -1 && holder[0].indexOf('data-post="' + postId(Q.pub) + '"') !== -1
      && holder[0].indexOf('data-api="' + mock.baseForHalo + '"') !== -1, 'unexpected placeholder ' + holder[0]);
    must(postPage.indexOf('widget/podcast.v1.js') !== -1, 'no podcast script');
    const excluded = await halo.site('/archives/' + Q.excl);
    must(excluded.status === 200 && excluded.text.indexOf('杨梅苏打') !== -1, 'the excluded post page did not render: ' + excluded.status);
    must(excluded.text.indexOf('momobird-podcast') === -1, 'the excluded post has a podcast placeholder');
    return holder[0];
  });

  let withPlugin;
  await step('7', 'rendering cost: home and post page with the plugin vs the cold baseline before it was installed (flag over +200 ms)', async () => {
    withPlugin = {
      home: await timing('with', '/', 30, 6),
      post: await timing('with', '/archives/' + Q.pub, 30, 6)
    };
    const dh = withPlugin.home.median - base.home.median;
    const dp = withPlugin.post.median - base.post.median;
    const dh95 = withPlugin.home.p95 - base.home.p95;
    const dp95 = withPlugin.post.p95 - base.post.p95;
    const line = 'home median ' + base.home.median.toFixed(1) + ' -> ' + withPlugin.home.median.toFixed(1) + ' ms (' + (dh >= 0 ? '+' : '') + dh.toFixed(1) + '; p95 '
      + base.home.p95.toFixed(1) + ' -> ' + withPlugin.home.p95.toFixed(1) + '); post median ' + base.post.median.toFixed(1) + ' -> ' + withPlugin.post.median.toFixed(1)
      + ' ms (' + (dp >= 0 ? '+' : '') + dp.toFixed(1) + '; p95 ' + base.post.p95.toFixed(1) + ' -> ' + withPlugin.post.p95.toFixed(1) + ')';
    must(dh <= 200 && dp <= 200 && dh95 <= 200 && dp95 <= 200, 'over +200 ms: ' + line);
    must(withPlugin.home.last.text.indexOf('loader.v1.js') !== -1 && withPlugin.post.last.text.indexOf('momobird-podcast') !== -1, 'plugin markup missing from the measured pages');
    return line;
  });

  // ------------------------------------------------------------------ the admin status
  await step('S.2', 'GET /status (live) returns the full picture', async () => {
    const r = await admin(halo, 'GET', '/status');
    must(r.status === 200 && r.json, 'status answered ' + r.status + ' ' + r.text.slice(0, 200));
    const s = r.json;
    const c = s.connection;
    must(s.configured === true && s.problems.length === 0, 'not configured: ' + textOf(s.problems));
    must(c.checked && c.ok, 'connection not ok: ' + textOf(c));
    must(c.project.name === 'Halo 本地测试项目', 'project ' + textOf(c.project));
    must(c.source.kind === 'halo' && (c.source.isHalo === true || c.source.halo === true), 'source ' + textOf(c.source));
    must(c.plan.status === 'trial' && c.plan.active === true, 'plan ' + textOf(c.plan));
    must(c.widget.available === true, 'widget ' + textOf(c.widget));
    must(c.origin.ok === true && c.origin.siteOrigin === SITE_URL && c.origin.sourceOrigin === SITE_URL, 'origin ' + textOf(c.origin));
    must(s.externalUrl === SITE_URL, 'externalUrl ' + s.externalUrl);
    must(s.sync.last && s.sync.last.mode === 'full' && s.sync.last.pushed >= 1 && s.sync.last.ok === true, 'last sync ' + textOf(s.sync));
    must(s.widgetEnabled === true && s.podcastEnabled === true && Array.isArray(s.failures), 'flags ' + textOf(s));
    return 'configured, project "' + c.project.name + '", source ' + c.source.kind + ', plan ' + c.plan.status + ', widget available, origin ' + c.origin.siteOrigin
      + ', last sync ' + s.sync.last.mode + ' pushed ' + s.sync.last.pushed + ', failures ' + s.failures.length;
  });

  // ------------------------------------------------------------------ the post life cycle
  const NEW = { a: 'it-new-a', priv: 'it-new-private', excl: 'it-new-excluded', del: 'it-new-delete', late: 'it-new-late' };
  const idsNow = () => mock.ids();
  const expect = async (what, wanted, unwanted, timeout) => waitFor(what, async () => {
    const ids = await idsNow();
    return wanted.every((n) => ids.indexOf(postId(n)) !== -1) && unwanted.every((n) => ids.indexOf(postId(n)) === -1) ? ids : null;
  }, { timeout: timeout || 25000, interval: 500 }).catch(async () => fail(what + ': the mock holds ' + JSON.stringify(await idsNow())
    + '; wanted ' + JSON.stringify(wanted.map(postId)) + ', not ' + JSON.stringify(unwanted.map(postId)) + '; recent: ' + (await mock.summary(0)).slice(-6).join('; ')));
  const titleOf = async (name) => ((await mock.posts()).find((p) => p.external_id === postId(name)) || {}).title;

  await step('S.3', 'edit the title: the new title is pushed', async () => {
    await halo.patchPost(Q.pub, (p) => { p.spec.title = '公开的测试文章（已修改）'; });
    await waitFor('the new title', async () => (await titleOf(Q.pub)) === '公开的测试文章（已修改）', { timeout: 25000 })
      .catch(async () => fail('title in the mock is ' + (await titleOf(Q.pub))));
    return 'mock title: ' + (await titleOf(Q.pub));
  });

  await step('S.4', 'publish a new public post: pushed; a new private and a new excluded post: not pushed', async () => {
    await halo.createPost({ name: NEW.a, title: '新的公开文章', html: '<p>新文章正文 百香果</p>' });
    await halo.createPost({ name: NEW.priv, title: '新的私有文章', visible: 'PRIVATE', html: '<p>新的私有正文</p>' });
    await halo.createPost({ name: NEW.excl, title: '新的排除文章', annotations: { 'momobird.ai/exclude': 'yes' }, html: '<p>新的排除正文</p>' });
    await expect('the new public post', [NEW.a], [NEW.priv, NEW.excl]);
    await sleep(5000);
    const ids = await idsNow();
    must(ids.indexOf(postId(NEW.priv)) === -1 && ids.indexOf(postId(NEW.excl)) === -1, 'a private or excluded post reached the mock: ' + JSON.stringify(ids));
    return 'mock holds ' + JSON.stringify(ids);
  });

  await step('S.5', 'unpublish a post: taken down; publish it again: pushed again', async () => {
    await halo.unpublishPost(NEW.a);
    await expect('the unpublished post to be taken down', [], [NEW.a]);
    await halo.publishPost(NEW.a);
    await expect('the post to come back', [NEW.a], []);
    return 'taken down after unpublish, pushed again after publish';
  });

  await step('S.6', 'make the first post private: taken down; public again: pushed', async () => {
    await halo.patchPost(Q.pub, (p) => { p.spec.visible = 'PRIVATE'; });
    await expect('the private post to be taken down', [], [Q.pub]);
    await halo.patchPost(Q.pub, (p) => { p.spec.visible = 'PUBLIC'; });
    await expect('the public post to come back', [Q.pub], []);
    return 'taken down as PRIVATE, pushed again as PUBLIC';
  });

  await step('S.7', 'move to the recycle bin: taken down; restore and publish: pushed', async () => {
    await halo.recyclePost(Q.pub);
    await expect('the trashed post to be taken down', [], [Q.pub]);
    await halo.patchPost(Q.pub, (p) => { p.spec.deleted = false; });
    await halo.publishPost(Q.pub);
    await expect('the restored post to come back', [Q.pub], []);
    return 'taken down in the bin, pushed again once restored and published';
  });

  await step('S.8', 'delete a published post for good: taken down', async () => {
    await halo.createPost({ name: NEW.del, title: '将被删除的文章', html: '<p>将被删除 蓝莓</p>' });
    await expect('the post to be pushed', [NEW.del], []);
    await halo.deletePost(NEW.del);
    await expect('the deleted post to be taken down', [], [NEW.del]);
    return 'pushed, then taken down after DELETE';
  });

  await step('S.9', 'delete a draft that MomoBird still holds (a stale copy): the take-down request goes out', async () => {
    await mock.control({ seed: [postId(Q.draft)] });
    const before = await mock.count();
    await halo.deletePost(Q.draft);
    await waitFor('the stale copy to be taken down', async () => (await idsNow()).indexOf(postId(Q.draft)) === -1, { timeout: 25000 })
      .catch(async () => fail('the mock still holds ' + JSON.stringify(await idsNow()) + '; ' + (await mock.summary(before)).join('; ')));
    return (await mock.summary(before)).join('; ');
  });

  await step('5', 'all five post events reach the listener (debug log lines)', async () => {
    const names = ['PostPublishedEvent', 'PostUpdatedEvent', 'PostUnpublishedEvent', 'PostDeletedEvent', 'PostVisibleChangedEvent'];
    const text = halo.logs();
    const counts = names.map((n) => n + '=' + (text.match(new RegExp('收到文章事件 ' + n, 'g')) || []).length);
    const missing = counts.filter((c) => /=0$/.test(c));
    must(missing.length === 0, 'no log line for: ' + missing.join(', ') + ' (' + counts.join(', ') + ')');
    return counts.join(', ') + ' (and each action above had its push or take-down within seconds, long before the next daily sync)';
  });

  // ------------------------------------------------------------------ 402, 401, kind, origin
  await step('S.10', 'plan expired (402): no push, take-downs still go out, status says so; after renewing, the next full sync pushes', async () => {
    await mock.control({ planExpired: true });
    await halo.createPost({ name: NEW.late, title: '套餐到期后的文章', html: '<p>套餐到期后的正文 桑葚</p>' });
    await waitFor('a refused push', async () => (await mock.requests(0)).requests.some((e) => e.method === 'PUT' && e.status === 402), { timeout: 25000 });
    let ids = await idsNow();
    must(ids.indexOf(postId(NEW.late)) === -1, 'the post was stored although the plan is expired');
    const run = await admin(halo, 'POST', '/sync', { json: {} });
    must(run.status === 200 && run.json.report, 'sync answered ' + run.status + ' ' + run.text.slice(0, 200));
    const rep = run.json.report;
    must(rep.planRequired >= 1 && rep.ok === false, 'report ' + textOf(rep));
    const before = await mock.count();
    await halo.patchPost(NEW.a, (p) => { p.spec.visible = 'PRIVATE'; });
    await waitFor('the take-down under 402', async () => (await idsNow()).indexOf(postId(NEW.a)) === -1, { timeout: 25000 })
      .catch(async () => fail('take-down did not happen: ' + (await mock.summary(before)).join('; ')));
    const st = await admin(halo, 'GET', '/status');
    must(st.json.sync.planRequired === true && st.json.connection.plan.active === false, 'status does not show the expired plan: ' + textOf(st.json.sync) + ' ' + textOf(st.json.connection.plan));
    // A post refused for the plan is not a failure and not on the retry list: while the plan is expired the
    // status lists none of them and /retry pushes nothing; the next full sync after renewing catches them up.
    const late = '套餐到期后的文章';
    must(!st.json.failures.some((f) => f.title === late || f.name === NEW.late), 'the 402 post is on the failures list: ' + textOf(st.json.failures));
    const beforeRetry = await mock.count();
    const retried = await admin(halo, 'POST', '/retry');
    const afterRetry = await mock.summary(beforeRetry);
    must(retried.status === 200 || retried.status === 202 || retried.status === 503, '/retry answered ' + retried.status + ' ' + retried.text.slice(0, 200));
    must(!afterRetry.some((s) => /^PUT .*-> 200/.test(s)) && (await idsNow()).indexOf(postId(NEW.late)) === -1,
      '/retry pushed the 402 post: ' + afterRetry.join('; '));
    await mock.control({ planExpired: false });
    await halo.patchPost(NEW.a, (p) => { p.spec.visible = 'PUBLIC'; });
    const afterRenewal = await admin(halo, 'POST', '/retry');
    await sleep(3000);
    must((await idsNow()).indexOf(postId(NEW.late)) === -1, 'after renewing /retry pushed the 402 post, so it is on the retry list: ' + textOf(afterRenewal.json));
    const retry = await admin(halo, 'POST', '/sync', { json: {} });
    must(retry.json.report && retry.json.report.pushed >= 1, 'the full sync after renewing pushed nothing: ' + retry.text.slice(0, 300));
    await expect('the post refused under 402 to be pushed by the next full sync', [NEW.late], [], 30000);
    ids = await idsNow();
    const st2 = await admin(halo, 'GET', '/status?check=false');
    must(st2.json.failures.length === 0, 'failures remain: ' + textOf(st2.json.failures));
    return 'full sync under 402: pushed ' + rep.pushed + ', planRequired ' + rep.planRequired + ', error "' + rep.error + '"; take-down during 402 worked; failures list without the 402 post; /retry while expired sent: ' + (afterRetry.join('; ') || 'nothing') + '; /retry after renewing pushed nothing either; status planRequired=true, plan '
      + st.json.connection.plan.status + '; after renewing the next full sync (' + retry.status + ', pushed ' + (retry.json.report ? retry.json.report.pushed : '?') + ') pushed the post; mock holds ' + ids.length + ' posts';
  });

  await step('X.401', 'a rejected key (401): sync reports it without the key, nothing is lost; the right key works again', async () => {
    await halo.writeSettings(settings({ connection: { baseUrl: mock.baseForHalo, key: BAD_KEY } }));
    const before = await mock.count();
    const run = await admin(halo, 'POST', '/sync', { json: {} });
    must(run.json && run.json.ok === false, 'sync did not fail: ' + run.status + ' ' + run.text.slice(0, 200));
    const test = await admin(halo, 'POST', '/test');
    must(test.json && test.json.connection && test.json.connection.ok === false, 'test did not fail: ' + test.text.slice(0, 200));
    must(leaks(run.text + test.text).length === 0, 'a key shows in the responses');
    const idsBefore = await idsNow();
    must(idsBefore.length >= 2, 'posts disappeared with a bad key');
    await halo.writeSettings(settings());
    const ok = await admin(halo, 'POST', '/sync', { json: {} });
    must(ok.json && ok.json.ok === true, 'sync with the right key failed: ' + ok.text.slice(0, 300));
    return 'with a wrong key: sync ok=false ("' + run.json.message + '"), test error "' + test.json.connection.error + '"; ' + (await mock.summary(before)).slice(0, 3).join('; ')
      + '; right key again: ok=true';
  });

  await step('X.kind', 'a source of another kind: removals are held back until confirmed; confirming takes them down', async () => {
    await mock.control({ kind: 'hexo', seed: ['4242424242'] });
    const held = await admin(halo, 'POST', '/sync', { json: {} });
    const ids = await idsNow();
    must(held.json.report.wrongKind === true && held.json.report.heldRemovals === 1 && ids.indexOf('4242424242') !== -1,
      'removal was not held: ' + textOf(held.json.report) + ' ids ' + JSON.stringify(ids));
    const confirmed = await admin(halo, 'POST', '/sync', { json: { confirmRemovals: true } });
    must(confirmed.json.report.removed === 1 && (await idsNow()).indexOf('4242424242') === -1, 'confirmed removal did not happen: ' + textOf(confirmed.json.report));
    await mock.control({ kind: 'halo' });
    return 'kind=hexo: heldRemovals=1 and the stale post stayed; confirmRemovals=true: removed=1';
  });

  await step('X.origin', 'the source address differs from Halo\'s external address: nothing is pushed or taken down', async () => {
    await mock.control({ siteUrl: 'https://other.example.com', seed: ['4242424243'] });
    await halo.patchPost(Q.pub, (p) => { p.spec.title = '地址不一致时改的标题'; });
    const before = await mock.count();
    const run = await admin(halo, 'POST', '/sync', { json: {} });
    const sent = (await mock.summary(before)).filter((s) => /^(PUT|POST \/posts\/delete)/.test(s));
    must(run.json.report.originBlocked === true && sent.length === 0 && (await idsNow()).indexOf('4242424243') !== -1,
      'report ' + textOf(run.json.report) + '; requests ' + (await mock.summary(before)).join('; '));
    await mock.control({ siteUrl: SITE_URL });
    const clean = await admin(halo, 'POST', '/sync', { json: {} });
    must(clean.json.ok === true && (await idsNow()).indexOf('4242424243') === -1, 'did not recover: ' + clean.text.slice(0, 300));
    return 'originBlocked=true, requests: ' + (await mock.summary(before)).join('; ') + '; after the addresses match again the sync is ok and removed the stale post';
  });

  // ------------------------------------------------------------------ 8 and 2: access and the console
  await step('8.1', 'API access matrix on /status', async () => {
    const rows = [];
    const expectations = [['anonymous', null, 401], ['plain user', USERS.plain, 403], ['plugin view only', USERS.pview, 403], ['plugin manage only', USERS.pmanage, 200],
      ['MomoBird role template', USERS.momo, 200], ['super admin', ADMIN, 200]];
    for (const [label, user, wanted] of expectations) {
      const r = await momoCall(halo, user, 'GET', '/status?check=false');
      rows.push(label + '=' + r.status);
      must(r.status === wanted, label + ' got ' + r.status + ', wanted ' + wanted + ' (' + rows.join(', ') + ')');
    }
    const plain = await request(halo.base, 'GET', '/apis/' + L.API + '/status?check=false');
    const wrong = await request(halo.base, 'GET', '/apis/' + L.API + '/status?check=false', { user: { name: 'admin', password: 'wrong' } });
    const own = await request(halo.base, 'GET', '/apis/api.console.halo.run/v1alpha1/plugins');
    return rows.join(', ') + '; without the XHR header an anonymous call gets ' + plain.status + (plain.headers.get('location') ? ' -> ' + plain.headers.get('location') : '')
      + ' (Halo\'s own console API answers the same: ' + own.status + '); a wrong password gets ' + wrong.status;
  });
  await step('8.2', 'write endpoints (/sync, /retry, /test) refuse users without the permission and start nothing', async () => {
    // Let earlier steps' background work (retries, the widget refresh) finish, so any request seen below is caused by these calls.
    let quiet = await mock.count();
    for (let i = 0; i < 20; i += 1) {
      await sleep(2500);
      const now = await mock.count();
      if (now === quiet) break;
      quiet = now;
    }
    const before = await mock.count();
    const rows = [];
    for (const [label, user] of [['anonymous', null], ['plain user', USERS.plain], ['plugin view only', USERS.pview]]) {
      for (const p of ['/sync', '/retry', '/test']) {
        const r = await momoCall(halo, user, 'POST', p, { json: {} });
        const wanted = user ? 403 : 401;
        must(r.status === wanted, label + ' POST ' + p + ' got ' + r.status + ', wanted ' + wanted);
        rows.push(r.status);
      }
    }
    must((await mock.count()) === before, 'MomoBird was contacted: ' + (await mock.summary(before)).join('; '));
    const allowed = await momoCall(halo, USERS.pmanage, 'POST', '/test', { json: {} });
    must(allowed.status === 200 && allowed.json.connection.ok === true, 'plugin manager /test answered ' + allowed.status);
    return 'denied statuses ' + rows.join(',') + ', no request reached the mock; the plugin manager may POST /test (200)';
  });

  let browser = null;
  if (process.env.SKIP_BROWSER !== '1') {
    browser = await launchBrowser().catch((err) => {
      check('2.0', 'a headless browser is available for the console checks', false, err.message.split('\n')[0]);
      return null;
    });
  } else {
    for (const [id, title] of [['8.3', 'console: the 工具 → MomoBird menu entry shows for managers only, the page opens for them, others are refused'],
      ['2', 'the admin UI loads in this console: route registered, bundle fetched, no console or page errors']]) {
      skip(id, title, 'SKIP_BROWSER=1');
    }
  }
  if (browser) {
    cleanups.push(() => browser.close());
    const seen = {};
    await step('8.3', 'console: the 工具 → MomoBird menu entry shows for managers only, the page opens for them, others are refused', async () => {
      const wanted = { admin: [true, true], pmanage: [true, true], momo: [true, true], pview: [false, false], plain: [false, false] };
      const rows = [];
      for (const [label, user] of [['admin', ADMIN], ['pmanage', USERS.pmanage], ['momo', USERS.momo], ['pview', USERS.pview], ['plain', USERS.plain]]) {
        const r = await browse(browser, halo, user);
        seen[label] = r;
        rows.push(label + ': menu=' + r.menu + ' page=' + r.pageShown + (r.denied ? ' denied(' + r.url.replace(halo.base, '') + ')' : ''));
        must(r.menu === wanted[label][0] && r.pageShown === wanted[label][1],
          label + ' expected menu=' + wanted[label][0] + ' page=' + wanted[label][1] + ' but ' + rows[rows.length - 1] + '; body: ' + r.text.slice(0, 200));
      }
      return rows.join('; ');
    });
    await step('2', 'the admin UI loads in this console: route registered, bundle fetched, no console or page errors', async () => {
      const bundle = await request(halo.base, 'GET', '/apis/api.console.halo.run/v1alpha1/plugins/-/bundle.js', { user: ADMIN });
      const loc = bundle.headers.get('location');
      const js = bundle.status === 307 && loc ? await request(halo.base, 'GET', loc, { user: ADMIN }) : bundle;
      must(js.status === 200 && js.text.indexOf('MomoBirdAi') !== -1 && js.text.indexOf('/momobird') !== -1,
        'the console bundle (' + js.status + ', ' + js.text.length + ' bytes) does not hold the route');
      const entry = await request(halo.base, 'GET', '/plugins/momobird-ai/assets/console/main.js');
      const css = await request(halo.base, 'GET', '/plugins/momobird-ai/assets/console/style.css');
      must(entry.status === 200 && entry.text.length > 1000 && css.status === 200, 'plugin assets ' + entry.status + '/' + css.status);
      const rows = [];
      for (const label of ['admin', 'pmanage', 'momo']) {
        const r = seen[label];
        // Halo's own console makes some calls that fail by themselves (the login screen's first probe, system settings a
        // non-super-admin may not read): only what points at the plugin counts.
        const ours = r.consoleErrors.filter((e) => /momobird/i.test(e)).concat(r.failedRequests.filter((e) => /momobird/i.test(e)));
        rows.push(label + ': page errors ' + r.pageErrors.length + ', errors that point at the plugin ' + ours.length + ' (console errors ' + r.consoleErrors.length
          + ', failed requests ' + r.failedRequests.length + (r.failedRequests.length ? ': ' + Array.from(new Set(r.failedRequests)).slice(0, 4).join(' | ') : '') + ')');
        must(r.pageErrors.length === 0 && ours.length === 0, label + ' had errors: ' + JSON.stringify(ours.concat(r.pageErrors)));
      }
      return 'route MomoBirdAi at /momobird is in the bundle (' + js.text.length + ' bytes), plugin main.js ' + entry.text.length + ' bytes, style.css ' + css.text.length
        + ' bytes; admin page text: "' + seen.admin.text.slice(0, 110) + '..."; ' + rows.join('; ');
    });
  }

  // ------------------------------------------------------------------ 4: disabling stops everything
  const threads = () => {
    const r = L.docker(['exec', halo.name, 'sh', '-c', 'cat /proc/[0-9]*/task/*/comm 2>/dev/null | grep -c momobird || true']);
    return parseInt(r.stdout.trim(), 10);
  };
  let offWarm = null;
  await step('4.4', 'disabling the plugin stops its threads and any further request to MomoBird; enabling it again starts a fresh service', async () => {
    const threadsOn = threads();
    const startLines = logCount(halo, '\\[MomoBird\\] 同步服务已启动');
    await halo.patchPost(Q.pub, (p) => { p.spec.title = '停用前改的标题'; });
    await halo.setPlugin(false);
    await sleep(500);
    const after = await mock.count();
    const threadsOff = threads();
    // The same pages again with the plugin off, on a warmed-up JVM: the fair baseline for check 7.
    offWarm = {
      home: await timing('without (warm)', '/', 30, 6),
      post: await timing('without (warm)', '/archives/' + Q.pub, 30, 6)
    };
    await halo.patchPost(Q.pub, (p) => { p.spec.title = '停用后改的标题'; });
    await sleep(9000);
    const later = await mock.count();
    const phase = await halo.pluginPhase();
    const titleSeen = await titleOf(Q.pub);
    must(phase !== 'STARTED', 'plugin phase ' + phase);
    must(later === after, 'the mock saw ' + (later - after) + ' requests after the plugin was disabled: ' + (await mock.summary(after)).join('; '));
    must(threadsOn > 0, 'precondition: no momobird thread was visible while the plugin ran (' + threadsOn + '), so "none after disabling" would prove nothing');
    must(threadsOff === 0, 'plugin threads still alive after disabling: ' + threadsOff + ' (before: ' + threadsOn + ')');
    must(titleSeen !== '停用后改的标题', 'a change made while disabled was pushed');
    await halo.setPlugin(true);
    await waitFor('the second start line', async () => logCount(halo, '\\[MomoBird\\] 同步服务已启动') === startLines + 1, { timeout: 20000 });
    await halo.patchPost(Q.pub, (p) => { p.spec.title = '重新启用后改的标题'; });
    await waitFor('a push after enabling again', async () => (await titleOf(Q.pub)) === '重新启用后改的标题', { timeout: 30000 })
      .catch(async () => fail('no push after enabling again; title ' + (await titleOf(Q.pub))));
    return 'momobird threads: ' + threadsOn + ' running, ' + threadsOff + ' after disabling; requests after disabling: 0 (' + after + ' -> ' + later
      + ' over 9 s, with two title changes); phase ' + phase + '; enabling again logged the start line a second time and the next change was pushed';
  });

  await step('7.2', 'rendering cost against the warmed-up baseline (the plugin disabled, same pages, same JVM)', async () => {
    must(offWarm && withPlugin, 'no measurements');
    const dh = withPlugin.home.median - offWarm.home.median;
    const dp = withPlugin.post.median - offWarm.post.median;
    const dh95 = withPlugin.home.p95 - offWarm.home.p95;
    const dp95 = withPlugin.post.p95 - offWarm.post.p95;
    const f = (n) => (n >= 0 ? '+' : '') + n.toFixed(1);
    const line = 'home median ' + offWarm.home.median.toFixed(1) + ' -> ' + withPlugin.home.median.toFixed(1) + ' ms (' + f(dh) + '; p95 ' + offWarm.home.p95.toFixed(1) + ' -> '
      + withPlugin.home.p95.toFixed(1) + ' ' + f(dh95) + '); post median ' + offWarm.post.median.toFixed(1) + ' -> ' + withPlugin.post.median.toFixed(1) + ' ms (' + f(dp)
      + '; p95 ' + offWarm.post.p95.toFixed(1) + ' -> ' + withPlugin.post.p95.toFixed(1) + ' ' + f(dp95) + ')';
    must(dh <= 200 && dp <= 200 && dh95 <= 200 && dp95 <= 200, 'over +200 ms: ' + line);
    return line;
  });

  // ------------------------------------------------------------------ 10: leak scans
  await step('10.1', 'front end: no key and no mbk_ in the site\'s pages (after asserting the plugin\'s markup is there)', async () => {
    const pages = ['/', '/archives', '/archives/' + Q.pub, '/archives/' + Q.excl, '/archives/' + NEW.late, '/categories', '/tags', '/rss.xml', '/sitemap.xml', '/robots.txt', '/page/2', '/it-page'];
    const bodies = {};
    let ok200 = 0;
    for (const p of pages) {
      const r = await halo.site(p);
      bodies[p] = r.text;
      if (r.status === 200) ok200 += 1;
    }
    const withWidget = Object.keys(bodies).filter((p) => bodies[p].indexOf('loader.v1.js') !== -1).length;
    const withPodcast = Object.keys(bodies).filter((p) => bodies[p].indexOf('momobird-podcast') !== -1).length;
    must(ok200 >= 6 && withWidget >= 3 && withPodcast >= 2, 'precondition: pages ' + ok200 + ' ok, with widget ' + withWidget + ', with podcast ' + withPodcast + ' (' + Object.keys(bodies).map((p) => p + ':' + bodies[p].length).join(' ') + ')');
    const hits = Object.keys(bodies).filter((p) => leaks(bodies[p]).length > 0);
    must(hits.length === 0, 'found in ' + hits.join(', '));
    return 'scanned ' + pages.length + ' pages (' + ok200 + ' answered 200, ' + withWidget + ' with the assistant script, ' + withPodcast + ' with a podcast placeholder, '
      + Object.values(bodies).reduce((n, b) => n + b.length, 0) + ' bytes): no key, no mbk_';
  });
  await step('10.2', 'admin API: no key and no mbk_ in any response the plugin gave (after asserting they hold real content)', async () => {
    const fresh = await admin(halo, 'GET', '/status');
    const all = adminResponses;
    const withContent = all.filter((r) => r.status === 200 && /"configured"/.test(r.text)).length;
    const errors = all.filter((r) => r.status >= 400).length;
    must(withContent >= 5 && all.length >= 20 && errors >= 3 && fresh.status === 200, 'precondition: ' + all.length + ' responses, ' + withContent + ' real status bodies, ' + errors + ' refusals');
    const reports = all.filter((r) => /"report"/.test(r.text)).length;
    const hits = all.filter((r) => leaks(r.text).length > 0).map((r) => r.who + ' ' + r.path);
    must(hits.length === 0, 'found in: ' + hits.join(', '));
    // Halo's own plugin object must not hold the key either (settings live in the ConfigMap, which only setting managers read).
    const plugin = await halo.api('GET', '/apis/plugin.halo.run/v1alpha1/plugins/momobird-ai');
    must(leaks(plugin.text).length === 0, 'the Plugin object holds a key');
    return 'scanned ' + all.length + ' responses (' + withContent + ' status bodies, ' + reports + ' sync reports, ' + errors + ' refusals) and the Plugin object: no key, no mbk_';
  });
  await step('10.3', 'Halo logs: no key and no mbk_ (after asserting the plugin logged at info, warn and debug level)', async () => {
    const console_ = halo.logs();
    const file = halo.logFile();
    const lines = (console_.match(/\[MomoBird\]/g) || []).length;
    const warns = (console_.match(/WARN[^\n]*\[MomoBird\]/g) || []).length;
    const debugs = (console_.match(/DEBUG[^\n]*\[MomoBird\]/g) || []).length;
    must(lines >= 20 && warns >= 1 && debugs >= 5 && file.length > 1000, 'precondition: ' + lines + ' plugin lines, ' + warns + ' warnings, ' + debugs + ' debug lines, log file ' + file.length + ' bytes');
    const hits = [].concat(leaks(console_).map((l) => 'console output: ' + l), leaks(file).map((l) => 'halo.log: ' + l));
    must(hits.length === 0, 'found: ' + hits.join(', '));
    return 'scanned the container output (' + console_.length + ' bytes, ' + lines + ' plugin lines, ' + warns + ' of them warnings, ' + debugs + ' debug) and halo.log (' + file.length + ' bytes): no key, no mbk_';
  });
  await step('X.logs', 'every plugin log line is Chinese and carries the [MomoBird] prefix; no stack trace from the plugin', async () => {
    const text = halo.logs();
    // lines written by the plugin's own loggers (Halo's lines that merely name the plugin class do not count)
    const own = text.split('\n').filter((l) => /\s\[?\s*ai\.momobird\.[A-Za-z.]+\s+:/.test(l));
    const bare = own.filter((l) => l.indexOf('[MomoBird]') === -1);
    const traces = text.split('\n').filter((l) => /^\s+at ai\.momobird\./.test(l)).length;
    must(own.length >= 10, 'precondition: only ' + own.length + ' plugin log lines');
    must(bare.length === 0 && traces === 0, 'unprefixed plugin lines: ' + JSON.stringify(bare.slice(0, 3)) + '; stack frames: ' + traces);
    return own.length + ' plugin log lines, all prefixed; no plugin stack frames; unexpected errors from the plugin: ' + (text.match(/ERROR[^\n]*ai\.momobird/g) || []).length;
  });

  if (browser) await browser.close();
  halo.remove();

  // ------------------------------------------------------------------ 9: no external address
  await noExternalUrl(mock);
}

// A second Halo that does not know its external address: nothing is pushed or taken down for public posts.
async function noExternalUrl(mock) {
  await mock.control({ resetPosts: true, planExpired: false, unauthorized: false, kind: 'halo', siteUrl: SITE_URL, widgetAvailable: null });
  const halo = new Halo({
    image: 'halohub/halo:' + VERSION, name: 'halo-it-' + RUN_ID + '-' + VERSION.replace(/\./g, '') + '-noext', runId: RUN_ID, version: VERSION, extraArgs: []
  });
  cleanups.push(() => halo.remove());
  console.log('=== Halo ' + VERSION + ' again, without halo.external-url ===');
  halo.start();
  await halo.ready();
  await halo.initialize();
  if (VERSION !== '2.17') {
    // Newer versions keep the address from the setup form in the system settings too: clear it and restart.
    const cm = await halo.api('GET', '/api/v1alpha1/configmaps/system');
    const basic = JSON.parse(cm.json.data.basic);
    basic.externalUrl = '';
    cm.json.data.basic = JSON.stringify(basic);
    const put = await halo.api('PUT', '/api/v1alpha1/configmaps/system', { json: cm.json });
    if (put.status !== 200) fail('clearing the external address answered ' + put.status);
    halo.restart();
    await sleep(3000);
    await halo.ready();
    await waitFor('the admin account after the restart', async () => (await halo.api('GET', '/apis/api.console.halo.run/v1alpha1/plugins')).status === 200, { timeout: 60000 });
  }
  const pub = 'it-noext-public';
  const priv = 'it-noext-private';
  await halo.createPost({ name: pub, title: '没有外部地址时的公开文章', html: '<p>没有外部地址 芒果</p>' });
  await halo.createPost({ name: priv, title: '没有外部地址时的私有文章', visible: 'PRIVATE', html: '<p>私有</p>' });
  await halo.installPlugin(JAR);
  await halo.writeSettings({ connection: { baseUrl: mock.baseForHalo, key: KEY }, widget: { enabled: true, position: 'right' }, podcast: { player: true, transcript: false } });
  await halo.setPlugin(true);
  const noExt = await momoCall(halo, ADMIN, 'GET', '/status?check=false');

  await step('9.1', 'without halo.external-url the plugin sees no external address', async () => {
    must(noExt.status === 200 && (noExt.json.externalUrl === null || noExt.json.externalUrl === '' || noExt.json.externalUrl === undefined), 'externalUrl is ' + textOf(noExt.json && noExt.json.externalUrl));
    return 'status.externalUrl = ' + textOf(noExt.json.externalUrl === undefined ? null : noExt.json.externalUrl);
  });
  await step('9.2', 'full sync without an external address: refused before listing, pushing or removing anything', async () => {
    await mock.control({ seed: [postId(pub), postId(priv), '4242424244'] });
    const before = await mock.count();
    const run = await momoCall(halo, ADMIN, 'POST', '/sync', { json: { confirmRemovals: true } });
    const requests = await mock.summary(before);
    const writes = requests.filter((s) => /^(PUT|POST \/posts\/delete|GET \/posts)/.test(s));
    must(run.json && run.json.report && run.json.report.originBlocked === true, 'report: ' + run.text.slice(0, 400));
    must(writes.length === 0, 'requests that must not happen: ' + writes.join('; '));
    must(/外部访问地址/.test(run.json.message || '') || /外部访问地址/.test(textOf(run.json.report.origin)), 'no hint about the external address: ' + textOf(run.json.report.origin));
    const ids = await mock.ids();
    must(ids.length === 3, 'the mock changed: ' + JSON.stringify(ids));
    return 'originBlocked=true even with confirmRemovals; requests: ' + (requests.join('; ') || 'none') + '; origin: ' + textOf(run.json.report.origin).slice(0, 160);
  });
  await step('9.3', 'a public post edited without an external address is neither pushed nor taken down; a private one is still taken down', async () => {
    const before = await mock.count();
    await halo.patchPost(pub, (p) => { p.spec.title = '没有外部地址时改了标题'; });
    await halo.patchPost(priv, (p) => { p.spec.title = '私有文章改了标题'; });
    await waitFor('the take-down of the private post', async () => (await mock.ids()).indexOf(postId(priv)) === -1, { timeout: 30000 })
      .catch(async () => fail('the private post was not taken down: ' + (await mock.summary(before)).join('; ')));
    await sleep(4000);
    const requests = await mock.summary(before);
    const ids = await mock.ids();
    const pushes = requests.filter((s) => /^PUT/.test(s));
    must(pushes.length === 0, 'pushes happened: ' + pushes.join('; '));
    must(ids.indexOf(postId(pub)) !== -1 && ids.indexOf('4242424244') !== -1, 'the public post or the stale copy was taken down: ' + JSON.stringify(ids));
    const failures = (await momoCall(halo, ADMIN, 'GET', '/status?check=false')).json.failures;
    return 'requests: ' + requests.join('; ') + '; mock still holds the public post and the stale copy (' + ids.length + ' ids); retry list: ' + failures.map((f) => f.title + ' (' + f.kind + ')').join(', ');
  });
  halo.remove();
}

(async () => {
  let crashed = null;
  try {
    await main();
  } catch (err) {
    crashed = err;
    check('crash', 'the run finished', false, err && err.stack ? err.stack.split('\n').slice(0, 6).join('\n') : String(err));
  }
  cleanup();
  const failed = results.filter((r) => !r.ok).length;
  const skipped = results.filter((r) => r.skipped).length;
  fs.writeFileSync(path.join(WORK, 'results-' + V + '.tsv'), results.map((r) => [V, r.id, r.skipped ? 'SKIP' : r.ok ? 'PASS' : 'FAIL', r.title, r.evidence.replace(/\s+/g, ' ')].join('\t')).join('\n') + '\n');
  console.log('RESULT [' + V + '] ' + (results.length - failed - skipped) + ' passed, ' + failed + ' failed, ' + skipped + ' skipped' + (crashed ? ' (the run crashed)' : ''));
  process.exit(failed === 0 ? 0 : 1);
})();
