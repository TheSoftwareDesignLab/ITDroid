#!/usr/bin/env node
/*
 * Checks the web report without a browser: runs webreport/js/main.js against the real report
 * server and looks at what every tab shows. It needs Node 18 or later and the built jar.
 *
 *   node webreport/test/smoke.js                  serves webreport/test/sample and checks it
 *   node webreport/test/smoke.js --url http://127.0.0.1:8585
 *                                                 checks whatever a server already running serves
 *                                                 (the checks that hold for any results folder)
 *   --jar <file>    the ITDroid jar (default target/ITDroid-1.0.0.jar)
 *   --java <file>   the java to run it with (default $JAVA_HOME/bin/java, else java)
 *   --verbose       also print what each tab shows
 *
 * The page is given a small stand-in for the DOM, enough for what it uses; there is no canvas, so
 * the visual difference between screenshots is the one thing a browser has to be asked for.
 *
 * webreport/test/sample holds one made-up app, org.example.shop, in the shape a run leaves it: three
 * screens explored in English and five languages that between them show every case of the report
 * (see the expectations at the end of this file), plus a run that was killed. Its screens were
 * written by hand; its findings were computed by ITDroid from those screens (--reanalyze) and
 * copied into report.json. If the comparison changes, compute them again the same way.
 */
'use strict';
const fs = require('fs');
const net = require('net');
const path = require('path');
const { spawn } = require('child_process');

// ------------------------------------------------------------------ a stand-in for the DOM

class Node {
  constructor() { this.parentNode = null; }
}
class Text extends Node {
  constructor(t) { super(); this.nodeValue = String(t); }
  get textContent() { return this.nodeValue; }
}
class Element extends Node {
  constructor(tag) {
    super();
    this.tagName = tag.toLowerCase();
    this.children = [];
    this.attrs = {};
    this.listeners = {};
    this.dataset = {};
    this.style = {};
    this._class = '';
    this._html = '';
    this.value = '';
    this.disabled = false;
    this.open = false;
  }
  get className() { return this._class; }
  set className(v) { this._class = String(v); }
  get classList() {
    const self = this;
    const list = () => self._class.split(/\s+/).filter(Boolean);
    return {
      contains: n => list().includes(n),
      add: n => { if (!list().includes(n)) self._class = list().concat(n).join(' '); },
      remove: n => { self._class = list().filter(c => c !== n).join(' '); },
      toggle(n, force) { const on = force === undefined ? !list().includes(n) : !!force; if (on) this.add(n); else this.remove(n); return on; }
    };
  }
  setAttribute(k, v) { this.attrs[k] = String(v); if (k === 'value') this.value = String(v); }
  getAttribute(k) { return k in this.attrs ? this.attrs[k] : null; }
  appendChild(n) { n.parentNode = this; this.children.push(n); return n; }
  addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); }
  dispatch(type) { for (const fn of (this.listeners[type] || [])) fn({ preventDefault() {}, target: this }); }
  click() { this.dispatch('click'); }
  set innerHTML(v) { this.children = []; this._html = String(v); }
  get innerHTML() { return this._html; }
  set textContent(v) { this.children = [new Text(v)]; this._html = ''; }
  get textContent() { return this.children.length ? this.children.map(c => c.textContent).join('') : this._html.replace(/<[^>]*>/g, ''); }
  scrollIntoView() {}
  /** tag, #id and .class, in that order, any of them left out: "div.card", "#tabs", "span.badge.badge-light". */
  matches(simple) {
    const m = /^([a-z0-9]*)(?:#([\w-]+))?((?:\.[\w-]+)*)$/i.exec(simple);
    if (!m) throw new Error('selector not supported by the stand-in: ' + simple);
    if (m[1] && m[1].toLowerCase() !== this.tagName) return false;
    if (m[2] && this.attrs.id !== m[2]) return false;
    return m[3].split('.').filter(Boolean).every(c => this.classList.contains(c));
  }
  descendants() {
    const out = [];
    const walk = n => { for (const c of n.children) if (c instanceof Element) { out.push(c); walk(c); } };
    walk(this);
    return out;
  }
  /** Simple selectors separated by spaces (descendants). */
  querySelectorAll(selector) {
    let found = [this];
    for (const part of selector.trim().split(/\s+/)) {
      const next = [];
      for (const f of found) for (const d of f.descendants()) if (d.matches(part) && !next.includes(d)) next.push(d);
      found = next;
    }
    return found;
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
}

/** Reads the nodes of a uiautomator dump, which is all the page parses. */
class DOMParser {
  parseFromString(xml) {
    const unescape = s => s.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>')
      .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(+n)).replace(/&amp;/g, '&');
    const nodes = [];
    const tag = /<node\b([^>]*)>/g;
    let m;
    while ((m = tag.exec(xml))) {
      const attrs = {};
      const attr = /([\w:-]+)="([^"]*)"/g;
      let a;
      while ((a = attr.exec(m[1]))) attrs[a[1]] = unescape(a[2]);
      nodes.push({ getAttribute: k => (k in attrs ? attrs[k] : null) });
    }
    return { getElementsByTagName: () => nodes };
  }
}

/** Opens the page at an address (the part after #) and waits until it has stopped loading. */
async function openPage(base, hash) {
  const root = new Element('body');
  const ids = { filter: 'input', reload: 'button', appList: 'div', appCount: 'p', welcome: 'div', noServer: 'div', app: 'div', appHeader: 'div', tabs: 'ul', tabContent: 'div', rootInfo: 'span' };
  for (const id of Object.keys(ids)) { const e = new Element(ids[id]); e.setAttribute('id', id); root.appendChild(e); }
  root.querySelector('#noServer').className = 'd-none';
  root.querySelector('#app').className = 'd-none';
  let pending = 0;
  const windowListeners = {};
  const location = {
    _hash: hash ? '#' + hash : '',
    get hash() { return this._hash; },
    set hash(v) {
      const h = v ? (String(v).startsWith('#') ? String(v) : '#' + v) : '';
      if (h !== this._hash) { this._hash = h; setTimeout(() => (windowListeners.hashchange || []).forEach(f => f()), 0); }
    }
  };
  const document = { createElement: t => new Element(t), createTextNode: t => new Text(t), querySelector: s => root.querySelector(s), querySelectorAll: s => root.querySelectorAll(s) };
  const window = { addEventListener: (t, f) => { (windowListeners[t] = windowListeners[t] || []).push(f); } };
  const release = () => setTimeout(() => { pending--; }, 0);
  const pageFetch = async (url, opts) => {
    pending++;
    try {
      const res = await fetch(base + url, opts);
      // reading the body is work in progress too: a result.json can be large
      return { ok: res.ok, status: res.status, json: async () => { pending++; try { return await res.json(); } finally { release(); } } };
    } finally {
      release();
    }
  };
  const script = fs.readFileSync(path.join(__dirname, '..', 'js', 'main.js'), 'utf8');
  new Function('document', 'window', 'location', 'fetch', 'DOMParser', 'Node', script)(document, window, location, pageFetch, DOMParser, Node);
  const settle = async () => {
    for (let i = 0, quiet = 0; i < 800 && quiet < 4; i++) {
      await new Promise(r => setTimeout(r, 25));
      quiet = pending === 0 ? quiet + 1 : 0;
    }
  };
  await settle();
  return { root, location, settle, $: s => root.querySelector(s), $$: s => root.querySelectorAll(s) };
}

// ------------------------------------------------------------------ checks

const text = e => (e ? e.textContent.replace(/\s+/g, ' ').trim() : '');
const short = (s, n) => (s.length > n ? s.substring(0, n) + '…' : s);
const link = (...parts) => parts.map(encodeURIComponent).join('/');
let verbose = false;
let passed = 0;
const failures = [];

function check(what, ok, detail) {
  if (ok) passed++; else failures.push(what + (detail ? ' :: ' + detail : ''));
  if (!ok || verbose) console.log((ok ? '  ok   ' : '  FAIL ') + what + (detail ? ' :: ' + detail : ''));
}

const getJson = async url => (await fetch(url)).json();

/** The cards of the languages tab, by language code. */
function languageCards(page) {
  const cards = {};
  for (const c of page.$$('#tabContent div.card')) {
    const m = /\(([\w-]+)\)/.exec(text(c.querySelector('div.card-header')));
    if (m) cards[m[1]] = c;
  }
  return cards;
}

const screensOf = card => card.querySelectorAll('details').filter(d => /^Screen \d+/.test(text(d.querySelector('summary'))));
const findingsIn = screens => screens.reduce((sum, d) => sum + Number((/, (\d+) elements? with a finding/.exec(text(d.querySelector('summary'))) || [0, 0])[1]), 0);
const buttonOf = (card, label) => card.querySelectorAll('button').find(b => text(b).replace(/\s*\d+$/, '') === label);

async function openScreen(page, details) {
  details.open = true;
  details.dispatch('toggle');
  await page.settle();
}

/** What holds for any evaluation of any results folder. */
async function checkEvaluation(base, dir) {
  const at = name => dir + ': ' + name;
  const report = await getJson(base + '/results/' + encodeURIComponent(dir) + '/report.json').catch(() => null);
  if (!report) {
    check(at('report.json can be read'), false);
    return;
  }

  for (const tab of ['overview', 'languages', 'hardcoded', 'strings', 'performance', 'environment', 'artifacts']) {
    const page = await openPage(base, link(dir, tab));
    const content = text(page.$('#tabContent'));
    // the words a failing script leaves behind, looked for outside the app's own texts: a string of an
    // app can say "undefined" (orWall logs "Just got an undefined action!")
    let own = content;
    for (const t of page.$$('#tabContent span.node-text').map(text).filter(Boolean)) own = own.split(t).join(' ');
    check(at('the ' + tab + ' tab renders'), content.length > 20 && !/Could not (render|read)/.test(content) && !/undefined|NaN|\[object/.test(own), short(content, 140));
    if (tab === 'performance') {
      check(at('the performance tab says it is that of ITDroid, not of the app'), /ITDroid performance/.test(text(page.$('#tabs'))) && (!page.$('#tabContent p.perf-note') || /Nothing here measures how the app itself performs/.test(content)), text(page.$('#tabs')));
    }
  }

  // every finding of a language is in one screen, and each kind of change narrows the screens to its own count
  const langs = report.langsReport || {};
  const page = await openPage(base, link(dir, 'languages'));
  const cards = languageCards(page);
  for (const code of Object.keys(langs).filter(c => !langs[c].dflt)) {
    const l = langs[code];
    const card = cards[code];
    check(at(code + ' has its card'), !!card);
    if (!card) continue;
    const total = Array.isArray(l.ipfs) ? l.ipfs.length : 0;
    check(at(code + ': the screens hold the ' + total + ' findings of the report'), findingsIn(screensOf(card)) === total, String(findingsIn(screensOf(card))));
    const summary = card.querySelector('div.ipf-summary');
    for (const b of (summary ? summary.querySelectorAll('button') : [])) {
      b.click();
      await page.settle();
      const expected = Number(/(\d+)$/.exec(text(b))[1]);
      check(at(code + ': "' + text(b) + '" narrows the screens to that many'), findingsIn(screensOf(card)) === expected, String(findingsIn(screensOf(card))));
    }
    if (summary) { summary.querySelectorAll('button')[0].click(); await page.settle(); }
    // the list opens on every screen of the source exploration
    check(at(code + ': the screens open on "every screen"'), buttonOf(card, 'every screen').classList.contains('active') && !buttonOf(card, 'with findings').classList.contains('active'));
    const first = screensOf(card).find(d => /with a finding/.test(text(d.querySelector('summary'))));
    if (first) {
      await openScreen(page, first);
      const images = first.querySelectorAll('img');
      const served = await Promise.all(images.map(async i => (await fetch(base + i.attrs.src)).status));
      check(at(code + ': the first screen with findings opens, with its findings in a table'), first.querySelectorAll('div.shot').length >= 2 && first.querySelectorAll('tbody tr').length > 0);
      check(at(code + ': its screenshots are served'), served.every(s => s === 200), served.join(','));
    }
    // all the screens of the source exploration, each with what happened to it
    const all = buttonOf(card, 'every screen');
    all.click();
    await page.settle();
    const listed = screensOf(card);
    const source = Object.keys(langs).find(c => langs[c].dflt);
    const sourceScreens = source && Number(langs[source].amStates);
    const stored = !/is not stored with this evaluation/.test(text(card));   // without the source exploration only the findings can be listed
    if (listed.length && sourceScreens && stored) {
      check(at(code + ': "every screen" lists the ' + sourceScreens + ' screens of the source exploration'), listed.length === sourceScreens, listed.map(d => text(d.querySelector('summary'))).join(' | '));
    }
    check(at(code + ': and they still hold every finding'), findingsIn(listed) === total || !listed.length);
  }

  // the groups of hardcoded strings add up
  const inventory = await getJson(base + '/results/' + encodeURIComponent(dir) + '/strings_inventory.json').catch(() => null);
  if (inventory && Array.isArray(inventory.entries)) {
    const hardcoded = inventory.entries.filter(e => e.kind === 'hardcoded' || e.kind === 'hardcoded-xml').length;
    const tab = await openPage(base, link(dir, 'hardcoded'));
    const groups = tab.$$('#tabContent div.card').filter(c => c.querySelector('div.card-header span.badge'));
    const sum = groups.reduce((s, g) => s + Number(text(g.querySelector('div.card-header span.badge'))), 0);
    check(at('the groups of hardcoded strings add up to the ' + hardcoded + ' of the inventory'), sum === hardcoded, String(sum));
    if (report.inventory && typeof report.inventory.hardcodedCode === 'number') {
      check(at('which is the number the report gives'), report.hardcoded === hardcoded, report.hardcoded + ' in report.json');
    }
    const paged = groups.find(g => g.querySelectorAll('button').some(b => /Next/.test(text(b)) && !b.classList.contains('d-none')));
    if (paged) {
      const before = text(paged.querySelector('tbody tr'));
      paged.querySelectorAll('button').find(b => /Next/.test(text(b))).click();
      check(at('the next page of a long group shows other rows'), text(paged.querySelector('tbody tr')) !== before);
    }
  }

  // every link of the artifacts tab answers
  const artifacts = await openPage(base, link(dir, 'artifacts'));
  const links = artifacts.$$('#tabContent a').map(a => a.attrs.href);
  let dead = [];
  for (const href of links) {
    const res = await fetch(base + href);
    if (res.body) await res.body.cancel();
    if (res.status !== 200) dead.push(res.status + ' ' + decodeURIComponent(href));
  }
  check(at('the ' + links.length + ' links of the artifacts tab answer'), links.length > 0 && !dead.length, dead.join(', '));
}

/** What the bundled sample must show: one check per case the sample was made for. */
async function checkSample(base) {
  const index = await getJson(base + '/api/results');
  const status = dir => (index.apps.find(a => a.dir === dir) || {}).status;
  check('sample: the finished run is listed as finished', status('org.example.shop') === 'finished', status('org.example.shop'));
  check('sample: the run whose process is gone is listed as interrupted', status('org.example.killed') === 'interrupted', status('org.example.killed'));
  const killed = await openPage(base, link('org.example.killed', 'overview'));
  check('sample: and its page says so', /Interrupted run/.test(text(killed.$('#appHeader'))), short(text(killed.$('#appHeader')), 160));

  const shop = 'org.example.shop';
  const page = await openPage(base, link(shop, 'languages'));
  const cards = languageCards(page);

  // es: a button grew over its neighbour, an image is gone, a button was pushed down; the detail screen is clean
  check('sample es: findings by kind, with an overlap and a missing element', !!buttonOf(cards.es, 'overlap') && !!buttonOf(cards.es, 'missing element'), text(cards.es.querySelector('div.ipf-summary')));
  const esMain = screensOf(cards.es)[0];
  await openScreen(page, esMain);
  const shots = esMain.querySelectorAll('div.shot');
  check('sample es: the element only the source screen has is outlined there and not in the translated one',
    shots[0].querySelectorAll('div.box.only').length === 1 && shots[1].querySelectorAll('div.box.only').length === 0);
  check('sample es: the pair of screenshots is followed by their visual difference', shots.length === 3 && /Visual difference/.test(text(shots[2])), shots.map(s => short(text(s), 40)).join(' | '));
  buttonOf(cards.es, 'every screen').click();
  await page.settle();
  const esAll = screensOf(cards.es).map(d => text(d.querySelector('summary')));
  check('sample es: the screen without findings is listed as such', esAll.length === 3 && /Screen 2, no findings/.test(esAll[1]), esAll.join(' | '));

  // ar: shipped by the app and mirrored, except one row of buttons
  check('sample ar: shipped by the app', /shipped by the app/.test(text(cards.ar.querySelector('div.card-header'))));
  check('sample ar: one of the pairs of siblings checked did not mirror', /kept their sides/.test(text(cards.ar.querySelector('div.ipf-summary'))), short(text(cards.ar.querySelector('div.ipf-summary')), 400));
  const arMain = screensOf(cards.ar)[0];
  await openScreen(page, arMain);
  check('sample ar: the two buttons carry the mark and their estimated mirrored position is drawn',
    arMain.querySelectorAll('span.badge').filter(b => text(b) === 'Missing mirroring').length === 2 && arMain.querySelectorAll('div.box.mirror').length === 2,
    arMain.querySelectorAll('div.box.mirror').map(b => b.attrs.style).join(' | '));
  const arShots = arMain.querySelectorAll('div.shot').map(s => text(s.querySelector('div.shot-caption')));
  check('sample ar: source, translated, after the RTL repair, estimated position and visual difference',
    arShots.length === 5 && /^Source/.test(arShots[0]) && /^Translated/.test(arShots[1]) && /^After the RTL repair/.test(arShots[2]), arShots.join(' | '));
  // the same language evaluated again with the layouts repaired: what was changed, and before and after
  const repair = cards.ar.querySelector('div.rtl-repair');
  check('sample ar: the RTL repair says what it changed in the app', !!repair && /2 attributes of 1 layout renamed/.test(text(repair)) && /3 layouts given a right-to-left copy/.test(text(repair)),
    repair ? short(text(repair), 300) : 'no block');
  const repairRows = repair ? repair.querySelectorAll('tbody tr').map(text) : [];
  check('sample ar: and the findings as the app is and after the repair', repairRows.some(r => /^Findings20$/.test(r)) && repairRows.some(r => /kept their sides1 of 100 of 10$/.test(r)),
    repairRows.join(' | '));
  check('sample es: a language that is not right-to-left has no repair', !cards.es.querySelector('div.rtl-repair'));

  // ru: an error dialog where the second screen should be, and the third one never reached
  buttonOf(cards.ru, 'every screen').click();
  await page.settle();
  const ruAll = screensOf(cards.ru).map(d => text(d.querySelector('summary')));
  check('sample ru: one screen compared, one not compared, one not reached', ruAll.length === 3 && /with a finding/.test(ruAll[0]) && /not compared/.test(ruAll[1]) && /not reached/.test(ruAll[2]), ruAll.join(' | '));
  const notCompared = screensOf(cards.ru)[1];
  await openScreen(page, notCompared);
  check('sample ru: the screen not compared shows both screenshots and says why', notCompared.querySelectorAll('img').length === 2 && /different screen/.test(text(notCompared)), short(text(notCompared), 200));

  // pt: the translation model does not support it
  check('sample pt: not evaluated, with the reason', /does not support the language/.test(text(cards.pt)), short(text(cards.pt), 160));

  // fr: the build was made and replayed, and its screens show the English texts: nothing to find, and the report says why
  check('sample fr: no findings, and marked as showing the same text as the source',
    /0 IPFs/.test(text(cards.fr.querySelector('div.card-header'))) && /same text as source/.test(text(cards.fr.querySelector('div.card-header'))),
    short(text(cards.fr.querySelector('div.card-header')), 200));
  check('sample fr: with the count of texts and what it means', /15 of the 15 texts/.test(text(cards.fr)) && /does not mean a translation fits/.test(text(cards.fr)),
    short(text(cards.fr), 300));
  check('sample es: a language that was shown carries the count without the warning',
    /0 of the 15 texts/.test(text(cards.es)) && !/same text as source/.test(text(cards.es.querySelector('div.card-header'))), short(text(cards.es), 200));

  // the overview: how the exploration ended, and the same mark per language
  const overview = await openPage(base, link(shop, 'overview'));
  const overviewText = text(overview.$('#tabContent'));
  check('sample: the overview says the exploration ran out of things to tap, and how much of the app it saw',
    /nothing left to tap/.test(overviewText) && /3 of 4 activities/.test(overviewText) && /needs content or an account/.test(overviewText), short(overviewText, 300));
  const perLanguage = overview.$$('#tabContent div.card').find(c => /Per language/.test(text(c.querySelector('div.card-header'))));
  const frRow = perLanguage ? perLanguage.querySelectorAll('tbody tr').map(text).find(r => /French/.test(r)) : '';
  check('sample: the table per language has the texts that stayed the same', !!perLanguage && /Same text as source/.test(text(perLanguage)) && /15\/15/.test(frRow || ''), frRow);

  // an address opens one screen, also one without findings
  const linked = await openPage(base, link(shop, 'languages', 'es', '2'));
  const open = linked.$$('#tabContent details').filter(d => d.open);
  check('sample: #' + link(shop, 'languages', 'es', '2') + ' opens that screen', open.length === 1 && /^Screen 2/.test(text(open[0].querySelector('summary'))) && open[0].querySelectorAll('div.shot').length >= 2,
    open.map(d => text(d.querySelector('summary'))).join(' | '));

  // hardcoded strings: the four groups, the search box and the filter by use
  const hardcoded = await openPage(base, link(shop, 'hardcoded'));
  const groups = {};
  for (const c of hardcoded.$$('#tabContent div.card')) {
    const title = c.querySelector('div.card-header strong');
    if (c.querySelector('div.card-header span.badge')) groups[text(title)] = c;
  }
  const count = name => (groups[name] ? text(groups[name].querySelector('div.card-header span.badge')) : 'no such group');
  check('sample: 2 strings extracted from the code, 1 from the XML, 1 patched, 3 not translated',
    count('Extracted from the code') === '2' && count('Extracted from the XML') === '1' && count('Patched in each language build') === '1' && count('Not translated') === '3',
    Object.keys(groups).map(g => g + ' ' + count(g)).join(' | '));
  const search = hardcoded.$$('#tabContent input')[0];
  search.value = 'PREMIUM';
  search.dispatch('input');
  check('sample: the search finds a string by its text, whatever the case', count('Patched in each language build') === '1 of 1' && count('Not translated') === '0 of 3',
    Object.keys(groups).map(g => g + ' ' + count(g)).join(' | '));
  search.value = 'cartdao';
  search.dispatch('input');
  check('sample: and by its class', count('Not translated') === '2 of 3', count('Not translated'));
  search.value = '';
  search.dispatch('input');
  const use = hardcoded.$$('#tabContent select')[0];
  use.value = 'data';
  use.dispatch('change');
  check('sample: the filter by use keeps the strings of that use', count('Not translated') === '2 of 3' && count('Extracted from the code') === '0 of 2');
  const applied = hardcoded.$$('#tabContent div.card').find(c => /Applied in each language build/.test(text(c.querySelector('div.card-header'))));
  check('sample: what was applied is told per language, and pt is told as not evaluated', !!applied && applied.querySelectorAll('tbody tr').length === 5 && /not evaluated/.test(text(applied)));

  // the strings each language lacks
  const strings = await openPage(base, link(shop, 'strings'));
  const rows = strings.$$('#tabContent div.card tbody tr').map(text);
  check('sample: the strings each language lacks, and which language the app shipped', rows.length === 5 && rows.some(r => /Arabic.*shipped by the app/.test(r)) && rows.some(r => /Spanish.*translated by ITDroid/.test(r)), rows.join(' | '));
  const lacking = strings.$$('#tabContent details')[0];
  lacking.open = true;
  lacking.dispatch('toggle');
  check('sample: each missing string with its text and its translation', lacking.querySelectorAll('tbody tr').length === 6 && /Comprar ahora/.test(text(lacking)), short(text(lacking), 200));
}

// ------------------------------------------------------------------ the server

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.on('error', reject);
    s.listen(0, '127.0.0.1', () => { const port = s.address().port; s.close(() => resolve(port)); });
  });
}

async function startServer(java, jar, folder) {
  const root = path.resolve(__dirname, '..', '..');
  if (!fs.existsSync(path.resolve(root, jar))) throw new Error('jar not found: ' + jar + ' (build it with "mvn package", or pass --jar)');
  const port = await freePort();
  const server = spawn(java, ['-jar', jar, '--report', folder, '--port', String(port), '--no-browser'], { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  server.stdout.on('data', d => { output += d; });
  server.stderr.on('data', d => { output += d; });
  const base = 'http://127.0.0.1:' + port;
  for (let i = 0; i < 100; i++) {
    if (server.exitCode != null) throw new Error('the report server stopped:\n' + output);
    try {
      if ((await fetch(base + '/api/results')).ok) return { base, stop: () => server.kill() };
    } catch (e) {
      // not listening yet
    }
    await new Promise(r => setTimeout(r, 200));
  }
  server.kill();
  throw new Error('the report server did not answer:\n' + output);
}

(async () => {
  const args = process.argv.slice(2);
  const option = (name, fallback) => (args.includes(name) ? args[args.indexOf(name) + 1] : fallback);
  verbose = args.includes('--verbose');
  const url = option('--url', null);
  const java = option('--java', process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java');
  const server = url ? { base: url.replace(/\/+$/, ''), stop() {} } : await startServer(java, option('--jar', 'target/ITDroid-1.0.0.jar'), path.join('webreport', 'test', 'sample'));
  try {
    const index = await getJson(server.base + '/api/results');
    console.log('Checking ' + index.apps.length + ' evaluation' + (index.apps.length === 1 ? '' : 's') + ' at ' + server.base + (url ? '' : ' (webreport/test/sample)'));
    // The page separates the parts of a line with commas. Looked for in what the page itself writes,
    // not in what it shows: a text of an app may well have a middle dot of its own.
    for (const file of [path.join('js', 'main.js'), 'index.html']) {
      const source = fs.readFileSync(path.join(__dirname, '..', file), 'utf8');
      const dot = source.indexOf('·');
      check('the page writes no middle dot (' + file.replace(/\\/g, '/') + ')', dot < 0, dot < 0 ? '' : short(source.substring(Math.max(0, dot - 40)), 90));
    }
    for (const a of index.apps) {
      if (a.status === 'unreadable') { check(a.dir + ': report.json can be read', false, a.error); continue; }
      await checkEvaluation(server.base, a.dir);
    }
    if (!url) await checkSample(server.base);
  } finally {
    server.stop();
  }
  console.log(passed + ' checks passed' + (failures.length ? ', ' + failures.length + ' FAILED' : ''));
  process.exit(failures.length ? 1 : 0);
})().catch(e => { console.error(e.message || e); process.exit(2); });
