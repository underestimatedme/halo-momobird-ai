'use strict';

// Plumbing for scripts/integration.js: Docker, HTTP against Halo, Halo's own APIs for the
// things the test sets up (users, roles, posts, plugin state), the mock's control calls and a
// few measuring helpers. Not part of the plugin jar.
//
// Everything here uses test values only: the admin and user passwords below exist only in the
// throw-away Docker containers this test starts.

const crypto = require('crypto');
const { spawnSync } = require('child_process');

const KEY = 'mbk_HALOLOCALTESTKEYHALOLOCALTESTKEYHALOLOCALTE';
const BAD_KEY = 'mbk_WRONGKEYWRONGKEYWRONGKEYWRONGKEYWRONGKEYWR';
const SITE_URL = 'https://halo-it.example.com';
const SOURCE_ID = '0192a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b';
const PLUGIN = 'momobird-ai';
const API = 'api.momobird.ai/v1alpha1';

// Halo's password rule: letters, digits and !@#$%^&* only.
const ADMIN = { name: 'admin', password: 'ItAdmin#2026x' };
const USERS = {
  plain: { name: 'itplain', password: 'ItUser#2026x', roles: [] },
  pview: { name: 'itpview', password: 'ItUser#2026x', roles: ['it-role-plugin-view'] },
  pmanage: { name: 'itpmanage', password: 'ItUser#2026x', roles: ['it-role-plugin-manage'] },
  momo: { name: 'itmomo', password: 'ItUser#2026x', roles: ['it-role-momobird'] }
};
const ROLES = {
  'it-role-plugin-view': 'role-template-view-plugins',
  'it-role-plugin-manage': 'role-template-manage-plugins',
  'it-role-momobird': 'role-template-momobird-ai-manage'
};

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// The plugin's post id: SHA-256 of the NFC-normalised name, first 13 hex characters, as decimal.
function postId(name) {
  const hex = crypto.createHash('sha256').update(name.normalize('NFC'), 'utf8').digest('hex').slice(0, 13);
  return BigInt('0x' + hex).toString();
}

function median(values) {
  const sorted = values.slice().sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
}

function percentile(values, p) {
  const sorted = values.slice().sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * p) - 1)];
}

// ----- Docker ------------------------------------------------------------------------------

function docker(args, options) {
  const result = spawnSync('docker', args, { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024, ...(options || {}) });
  return { status: result.status, stdout: result.stdout || '', stderr: result.stderr || '' };
}

function dockerOk(args) {
  const result = docker(args);
  if (result.status !== 0) {
    throw new Error('docker ' + args.join(' ') + ' failed: ' + result.stderr.trim());
  }
  return result.stdout.trim();
}

// ----- HTTP ---------------------------------------------------------------------------------

function basic(user) {
  return 'Basic ' + Buffer.from(user.name + ':' + user.password).toString('base64');
}

// request(base, method, path, {user, json, form, headers, timeout}) -> {status, headers, text, json, ms}
async function request(base, method, path, options) {
  const o = options || {};
  const headers = Object.assign({}, o.headers || {});
  if (o.user) headers.Authorization = basic(o.user);
  let body;
  if (o.json !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(o.json);
  } else if (o.form !== undefined) {
    body = o.form;
  } else if (o.urlencoded !== undefined) {
    headers['Content-Type'] = 'application/x-www-form-urlencoded';
    body = new URLSearchParams(o.urlencoded).toString();
  }
  const started = process.hrtime.bigint();
  const response = await fetch(base + path, {
    method,
    headers,
    body,
    redirect: 'manual',
    signal: AbortSignal.timeout(o.timeout || 60000)
  });
  const text = await response.text();
  const ms = Number(process.hrtime.bigint() - started) / 1e6;
  let parsed;
  try {
    parsed = text === '' ? undefined : JSON.parse(text);
  } catch (err) {
    parsed = undefined;
  }
  return { status: response.status, headers: response.headers, text, json: parsed, ms };
}

async function waitFor(what, fn, options) {
  const o = options || {};
  const deadline = Date.now() + (o.timeout || 30000);
  let last;
  for (;;) {
    try {
      last = await fn();
      if (last) return last;
    } catch (err) {
      last = undefined;
    }
    if (Date.now() > deadline) throw new Error('timed out waiting for ' + what);
    await sleep(o.interval || 500);
  }
}

// ----- one Halo container ---------------------------------------------------------------------

class Halo {
  // opts: image, name, runId, extraArgs
  constructor(opts) {
    this.image = opts.image;
    this.name = opts.name;
    this.runId = opts.runId;
    this.extraArgs = opts.extraArgs || [];
    this.version = opts.version;
    this.base = null;
  }

  start() {
    dockerOk([
      'run', '-d', '--name', this.name, '--label', 'momobird-halo-it=' + this.runId,
      '--add-host', 'host.docker.internal:host-gateway', '-p', '127.0.0.1::8090', this.image,
      '--halo.security.basic-auth.disabled=false',
      '--logging.level.ai.momobird=DEBUG',
      // Form logins are limited to 3 a minute per address; the console checks log in several users.
      '--resilience4j.ratelimiter.configs.authentication.limitForPeriod=200',
      ...this.extraArgs
    ]);
    this.readPort();
  }

  // The published port is picked by Docker and changes when the container restarts.
  readPort() {
    const mapping = dockerOk(['port', this.name, '8090/tcp']).split('\n')[0];
    this.base = 'http://127.0.0.1:' + mapping.slice(mapping.lastIndexOf(':') + 1);
  }

  async ready() {
    await waitFor('Halo ' + this.version + ' to answer', async () => {
      const r = await request(this.base, 'GET', '/actuator/health', { timeout: 3000 });
      return r.status === 200;
    }, { timeout: 180000, interval: 1000 });
  }

  restart() {
    dockerOk(['restart', this.name]);
    this.readPort();
  }

  logs() {
    const result = docker(['logs', this.name]);
    return result.stdout + result.stderr;
  }

  // Halo also writes its own log file; the console output above is the same text, but the file is
  // what an operator would send in a bug report, so it is scanned too.
  logFile() {
    return docker(['exec', this.name, 'sh', '-c', 'cat /root/.halo2/logs/halo.log 2>/dev/null']).stdout;
  }

  remove() {
    docker(['rm', '-f', '-v', this.name]);
  }

  // ---- API with the admin's basic auth ----
  api(method, path, options) {
    return request(this.base, method, path, Object.assign({ user: ADMIN }, options || {}));
  }

  async cookieAndToken() {
    const response = await fetch(this.base + '/', { redirect: 'manual', headers: { Accept: 'text/html' } });
    const cookies = response.headers.getSetCookie ? response.headers.getSetCookie() : [];
    const xsrf = cookies.map((c) => c.split(';')[0]).find((c) => c.indexOf('XSRF-TOKEN=') === 0);
    return xsrf ? xsrf.slice('XSRF-TOKEN='.length) : '';
  }

  // First start: create the super administrator. 2.17 has a JSON API for it, newer versions a
  // setup form (which also takes the external address).
  async initialize() {
    const token = await this.cookieAndToken();
    const headers = { 'X-XSRF-TOKEN': token, Cookie: 'XSRF-TOKEN=' + token };
    const form = await request(this.base, 'GET', '/system/setup', { headers: { Accept: 'text/html' } });
    if (form.status === 200) {
      const r = await request(this.base, 'POST', '/system/setup', {
        headers: Object.assign({ Accept: 'application/json' }, headers),
        urlencoded: {
          username: ADMIN.name,
          password: ADMIN.password,
          email: 'admin@example.com',
          siteTitle: 'Halo IT',
          language: 'zh-CN',
          externalUrl: SITE_URL
        }
      });
      if (r.status !== 204 && r.status !== 302) throw new Error('setup form answered ' + r.status + ' ' + r.text);
    } else {
      const r = await request(this.base, 'POST', '/apis/api.console.halo.run/v1alpha1/system/initialize', {
        headers,
        json: { username: ADMIN.name, password: ADMIN.password, email: 'admin@example.com', siteTitle: 'Halo IT' }
      });
      if (r.status !== 201 && r.status !== 200) throw new Error('initialize answered ' + r.status + ' ' + r.text);
    }
    await waitFor('the admin account', async () => (await this.api('GET', '/apis/api.console.halo.run/v1alpha1/plugins')).status === 200,
      { timeout: 60000 });
    // The default theme wants a primary menu; a fresh 2.17 has none.
    const menu = await this.api('GET', '/api/v1alpha1/menus/primary');
    if (menu.status === 404) {
      await this.api('POST', '/api/v1alpha1/menus', {
        json: { apiVersion: 'v1alpha1', kind: 'Menu', metadata: { name: 'primary' }, spec: { displayName: 'Primary', menuItems: [] } }
      });
    }
  }

  // ---- plugin ----
  async installPlugin(jarPath) {
    const fs = require('fs');
    const form = new FormData();
    form.append('file', new Blob([fs.readFileSync(jarPath)]), 'plugin.jar');
    const r = await this.api('POST', '/apis/api.console.halo.run/v1alpha1/plugins/install', { form, timeout: 120000 });
    if (r.status !== 200) throw new Error('plugin install answered ' + r.status + ' ' + r.text.slice(0, 300));
  }

  async setPlugin(enable) {
    const r = await this.api('PUT', '/apis/api.console.halo.run/v1alpha1/plugins/' + PLUGIN + '/plugin-state', {
      json: { enable, async: false }, timeout: 120000
    });
    if (r.status !== 200) throw new Error('plugin-state answered ' + r.status + ' ' + r.text.slice(0, 300));
    const phase = enable ? 'STARTED' : 'STOPPED';
    await waitFor('plugin ' + phase, async () => {
      const p = await this.api('GET', '/apis/plugin.halo.run/v1alpha1/plugins/' + PLUGIN);
      return p.json && p.json.status && (enable ? p.json.status.phase === 'STARTED' : p.json.status.phase !== 'STARTED');
    }, { timeout: 60000 });
  }

  async pluginPhase() {
    const p = await this.api('GET', '/apis/plugin.halo.run/v1alpha1/plugins/' + PLUGIN);
    return p.json && p.json.status ? p.json.status.phase : 'MISSING';
  }

  // Replaces the plugin's setting groups (each group is a JSON string in the ConfigMap).
  async writeSettings(groups) {
    for (let attempt = 0; attempt < 5; attempt += 1) {
      const current = await this.api('GET', '/api/v1alpha1/configmaps/momobird-ai-configmap');
      if (current.status !== 200) throw new Error('configmap answered ' + current.status);
      const map = current.json;
      map.data = map.data || {};
      for (const group of Object.keys(groups)) map.data[group] = JSON.stringify(groups[group]);
      const put = await this.api('PUT', '/api/v1alpha1/configmaps/momobird-ai-configmap', { json: map });
      if (put.status === 200) {
        // Halo's setting fetcher caches the ConfigMap and drops the cache a moment after a change.
        await sleep(2500);
        return;
      }
      if (put.status !== 409) throw new Error('configmap update answered ' + put.status + ' ' + put.text.slice(0, 200));
    }
    throw new Error('configmap update kept conflicting');
  }

  // ---- users and roles ----
  async createRole(name, template) {
    const r = await this.api('POST', '/api/v1alpha1/roles', {
      json: {
        apiVersion: 'v1alpha1', kind: 'Role',
        metadata: {
          name,
          annotations: {
            'rbac.authorization.halo.run/dependencies': JSON.stringify([template]),
            'rbac.authorization.halo.run/display-name': name
          }
        },
        rules: []
      }
    });
    if (r.status !== 201) throw new Error('role ' + name + ' answered ' + r.status + ' ' + r.text.slice(0, 200));
  }

  async createUser(user) {
    const r = await this.api('POST', '/apis/api.console.halo.run/v1alpha1/users', {
      json: { name: user.name, email: user.name + '@example.com', displayName: user.name, password: user.password, roles: [], avatar: '', phone: '', bio: '' }
    });
    if (r.status !== 200 && r.status !== 201) throw new Error('user ' + user.name + ' answered ' + r.status + ' ' + r.text.slice(0, 200));
    if (user.roles.length > 0) {
      const g = await this.api('POST', '/apis/api.console.halo.run/v1alpha1/users/' + user.name + '/permissions', { json: { roles: user.roles } });
      if (g.status !== 200) throw new Error('grant to ' + user.name + ' answered ' + g.status + ' ' + g.text.slice(0, 200));
    }
  }

  // ---- posts ----
  // spec: {name, title, slug, visible, annotations, html, publish}
  async createPost(spec) {
    const html = spec.html || '<p>正文 ' + spec.title + '</p>';
    const r = await this.api('POST', '/apis/api.console.halo.run/v1alpha1/posts', {
      json: {
        post: {
          apiVersion: 'content.halo.run/v1alpha1', kind: 'Post',
          metadata: { name: spec.name, annotations: spec.annotations || {} },
          spec: {
            title: spec.title, slug: spec.slug || spec.name, deleted: false, publish: false, pinned: false,
            allowComment: true, visible: spec.visible || 'PUBLIC', priority: 0,
            excerpt: { autoGenerate: true, raw: '' }, categories: spec.categories || [], tags: [], htmlMetas: []
          }
        },
        content: { raw: html, content: html, rawType: 'html' }
      }
    });
    if (r.status !== 200 && r.status !== 201) throw new Error('create post ' + spec.name + ' answered ' + r.status + ' ' + r.text.slice(0, 300));
    if (spec.publish !== false) await this.publishPost(spec.name);
  }

  async publishPost(name) {
    const r = await this.api('PUT', '/apis/api.console.halo.run/v1alpha1/posts/' + name + '/publish');
    if (r.status !== 200) throw new Error('publish ' + name + ' answered ' + r.status + ' ' + r.text.slice(0, 200));
    await waitFor(name + ' to be PUBLISHED', async () => {
      const p = await this.api('GET', '/apis/content.halo.run/v1alpha1/posts/' + name);
      return p.json && p.json.status && p.json.status.phase === 'PUBLISHED' && p.json.status.permalink;
    }, { timeout: 30000 });
  }

  async unpublishPost(name) {
    const r = await this.api('PUT', '/apis/api.console.halo.run/v1alpha1/posts/' + name + '/unpublish');
    if (r.status !== 200) throw new Error('unpublish ' + name + ' answered ' + r.status);
  }

  async recyclePost(name) {
    const r = await this.api('PUT', '/apis/api.console.halo.run/v1alpha1/posts/' + name + '/recycle');
    if (r.status !== 200) throw new Error('recycle ' + name + ' answered ' + r.status + ' ' + r.text.slice(0, 200));
  }

  async deletePost(name) {
    const r = await this.api('DELETE', '/apis/content.halo.run/v1alpha1/posts/' + name);
    if (r.status !== 200 && r.status !== 204) throw new Error('delete ' + name + ' answered ' + r.status + ' ' + r.text.slice(0, 200));
  }

  // Get, change, put back; Halo answers 409 when the post moved on meanwhile.
  async patchPost(name, change) {
    for (let attempt = 0; attempt < 8; attempt += 1) {
      const current = await this.api('GET', '/apis/content.halo.run/v1alpha1/posts/' + name);
      if (current.status !== 200) throw new Error('get ' + name + ' answered ' + current.status);
      const post = current.json;
      change(post);
      const put = await this.api('PUT', '/apis/content.halo.run/v1alpha1/posts/' + name, { json: post });
      if (put.status === 200) return;
      if (put.status !== 409) throw new Error('update ' + name + ' answered ' + put.status + ' ' + put.text.slice(0, 200));
      await sleep(300);
    }
    throw new Error('update ' + name + ' kept conflicting');
  }

  // A fresh Halo may come with sample posts (newer versions add a "Hello Halo"); the test wants only its own.
  async removeAllPosts() {
    const existing = await this.allPostsViaApi();
    for (const post of existing) await this.deletePost(post.name);
    await waitFor('the sample posts to be gone', async () => (await this.allPostsViaApi()).length === 0, { timeout: 30000 });
    return existing.map((p) => p.name);
  }

  async allPostsViaApi() {
    const names = [];
    for (let page = 1; page < 20; page += 1) {
      const r = await this.api('GET', '/apis/content.halo.run/v1alpha1/posts?page=' + page + '&size=50');
      if (r.status !== 200) throw new Error('list posts answered ' + r.status);
      for (const item of r.json.items) names.push({ name: item.metadata.name, deleted: item.spec.deleted, publish: item.spec.publish, visible: item.spec.visible, phase: item.status && item.status.phase });
      if (!r.json.hasNext) break;
    }
    return names;
  }

  async createPage(name, title) {
    const html = '<p>这是一个独立页面 ' + title + '</p>';
    const r = await this.api('POST', '/apis/api.console.halo.run/v1alpha1/singlepages', {
      json: {
        page: {
          apiVersion: 'content.halo.run/v1alpha1', kind: 'SinglePage', metadata: { name },
          spec: { title, slug: name, deleted: false, publish: false, pinned: false, allowComment: true, visible: 'PUBLIC', priority: 0, excerpt: { autoGenerate: true, raw: '' }, htmlMetas: [] }
        },
        content: { raw: html, content: html, rawType: 'html' }
      }
    });
    if (r.status !== 200 && r.status !== 201) throw new Error('create page answered ' + r.status + ' ' + r.text.slice(0, 300));
    const p = await this.api('PUT', '/apis/api.console.halo.run/v1alpha1/singlepages/' + name + '/publish');
    if (p.status !== 200) throw new Error('publish page answered ' + p.status);
  }

  // GET a page of the site as a visitor.
  site(path) {
    return request(this.base, 'GET', path, { headers: { Accept: 'text/html' } });
  }
}

// ----- the mock ------------------------------------------------------------------------------

class Mock {
  constructor(port) {
    this.port = port;
    this.base = 'http://127.0.0.1:' + port;
    // How the container reaches it.
    this.baseForHalo = 'http://host.docker.internal:' + port;
  }

  async control(change) {
    const r = await request(this.base, 'POST', '/__control', { json: change });
    if (r.status !== 200) throw new Error('mock control answered ' + r.status);
  }

  async requests(since) {
    const r = await request(this.base, 'GET', '/__requests?since=' + (since || 0));
    return r.json;
  }

  async count() {
    return (await this.requests(1e9)).count;
  }

  async posts() {
    const r = await request(this.base, 'GET', '/__posts');
    return r.json;
  }

  async ids() {
    return (await this.posts()).map((p) => p.external_id).sort();
  }

  // The requests after the first `since`, as short strings like "PUT /posts posts=2 -> 200".
  async summary(since) {
    const prefix = '/momobird/api/public/connect/v1';
    const all = (await this.requests(since)).requests;
    return all.map((e) => {
      const path = (e.path.indexOf(prefix) === 0 ? e.path.slice(prefix.length) : e.path) + (e.query || '');
      let detail = '';
      if (e.method === 'PUT' && e.body && Array.isArray(e.body.posts)) detail = ' posts=[' + e.body.posts.map((p) => p.external_id).join(',') + ']';
      if (path.indexOf('/posts/delete') === 0 && e.body && Array.isArray(e.body.external_ids)) detail = ' ids=[' + e.body.external_ids.join(',') + ']';
      return e.method + ' ' + path + detail + ' -> ' + e.status + (e.authorized ? '' : ' (unauthorized)');
    });
  }
}

module.exports = {
  KEY, BAD_KEY, SITE_URL, SOURCE_ID, PLUGIN, API, ADMIN, USERS, ROLES,
  sleep, postId, median, percentile, docker, dockerOk, request, basic, waitFor, Halo, Mock
};
