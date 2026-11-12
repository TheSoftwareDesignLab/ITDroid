/*
 * ITDroid web report. Served by `java -jar target/ITDroid-1.0.0.jar --report [resultsFolder]`:
 * GET /api/results lists the evaluations of the folder, /results/<dir>/... serves their files.
 * No framework, no network access: everything comes from the local results folder.
 */
(function () {
  'use strict';

  const state = { apps: [], dir: null, app: null, report: null, inventory: null, meta: null, results: {}, tab: 'overview' };

  // ------------------------------------------------------------------ helpers

  const $ = (sel, root) => (root || document).querySelector(sel);

  function el(tag, attrs, ...children) {
    const node = document.createElement(tag);
    if (attrs) {
      for (const k of Object.keys(attrs)) {
        if (k === 'class') node.className = attrs[k];
        else if (k === 'html') node.innerHTML = attrs[k];
        else if (k.startsWith('on')) node.addEventListener(k.substring(2), attrs[k]);
        else node.setAttribute(k, attrs[k]);
      }
    }
    for (const c of children) {
      if (c == null) continue;
      node.appendChild(typeof c === 'string' || typeof c === 'number' ? document.createTextNode(String(c)) : c);
    }
    return node;
  }

  const isNum = v => typeof v === 'number' && !isNaN(v);
  const num = (v, d) => isNum(v) ? v.toLocaleString('en-US', { maximumFractionDigits: d == null ? 0 : d }) : (v == null ? '–' : String(v));
  const sec = v => !isNum(v) ? '–' : v >= 3600 ? (v / 3600).toFixed(2) + ' h' : v >= 60 ? (v / 60).toFixed(1) + ' min' : v.toFixed(1) + ' s';
  const mb = v => isNum(v) ? (v >= 1024 ? (v / 1024).toFixed(1) + ' GB' : Math.round(v) + ' MB') : '–';
  const date = iso => { if (!iso) return '–'; const d = new Date(iso); return isNaN(d) ? String(iso) : d.toLocaleString(); };
  const pct = (a, b) => (isNum(a) && isNum(b) && b > 0) ? Math.round(100 * a / b) + '%' : '–';
  const basename = p => p ? String(p).split(/[\\/]/).pop() : '';

  async function fetchJson(url, optional) {
    const r = await fetch(url, { cache: 'no-store' });
    if (!r.ok) {
      if (optional) return null;
      throw new Error(r.status + ' ' + url);
    }
    return r.json();
  }

  const resultsUrl = rel => '/results/' + encodeURIComponent(state.dir) + '/' + rel.split('/').map(encodeURIComponent).join('/');

  function badge(text, kind) {
    return el('span', { class: 'badge badge-' + (kind || 'secondary') + ' mr-1' }, text);
  }

  function statusKind(status) {
    if (status === 'finished' || status === 'ok') return 'success';
    if (status === 'aborted' || status === 'failed' || status === 'unreadable') return 'danger';
    if (status === 'partial' || status === 'timeout' || status === 'blocked') return 'warning';
    return 'secondary';
  }

  function kvTable(rows) {
    const tbody = el('tbody');
    for (const [k, v] of rows) {
      if (v == null || v === '') continue;
      tbody.appendChild(el('tr', null, el('th', null, k), el('td', null, v instanceof Node ? v : String(v))));
    }
    return el('table', { class: 'table table-sm kv mb-0' }, tbody);
  }

  function card(title, body, extraClass) {
    return el('div', { class: 'card ' + (extraClass || '') },
      el('div', { class: 'card-header py-2' }, el('strong', null, title)),
      el('div', { class: 'card-body py-2' }, body));
  }

  function table(headers, rows, numericCols) {
    const thead = el('thead', null, el('tr', null, ...headers.map((h, i) => el('th', numericCols && numericCols.includes(i) ? { class: 'number' } : null, h))));
    const tbody = el('tbody');
    for (const r of rows) {
      tbody.appendChild(el('tr', null, ...r.map((c, i) => el('td', numericCols && numericCols.includes(i) ? { class: 'number' } : null, c instanceof Node ? c : (c == null ? '–' : String(c))))));
    }
    return el('div', { class: 'table-responsive' }, el('table', { class: 'table table-sm table-hover mb-0' }, thead, tbody));
  }

  // ------------------------------------------------------------------ app list

  async function loadIndex() {
    let index;
    try {
      index = await fetchJson('/api/results');
    } catch (e) {
      $('#noServer').classList.remove('d-none');
      $('#welcome').classList.add('d-none');
      $('#appCount').textContent = 'results not available';
      return;
    }
    $('#noServer').classList.add('d-none');
    state.apps = index.apps || [];
    $('#rootInfo').textContent = index.root || '';
    renderList();
    const hash = decodeURIComponent(location.hash.replace(/^#/, ''));
    if (hash) {
      const [dir, tab] = hash.split('/');
      if (state.apps.some(a => a.dir === dir)) {
        selectApp(dir, tab || 'overview');
      }
    }
  }

  function renderList() {
    const filter = ($('#filter').value || '').toLowerCase();
    const list = $('#appList');
    list.innerHTML = '';
    let shown = 0;
    for (const a of state.apps) {
      const text = [a.appName, a.apkName, a.dir, a.status].join(' ').toLowerCase();
      if (filter && !text.includes(filter)) continue;
      shown++;
      const status = a.harness && a.harness.status ? a.harness.status : a.status;
      const item = el('a', { class: 'list-group-item list-group-item-action app-item' + (a.dir === state.dir ? ' active' : ''), href: '#' + encodeURIComponent(a.dir),
        onclick: ev => { ev.preventDefault(); selectApp(a.dir, 'overview'); } },
        el('div', { class: 'd-flex justify-content-between align-items-center' },
          el('span', { class: 'app-name' }, a.appName || a.dir),
          badge(status, statusKind(status))),
        el('div', { class: 'app-meta' }, (a.apkName || '') + (a.versionName ? ' · v' + a.versionName : '')),
        el('div', { class: 'app-meta' }, (isNum(a.totalIPFs) ? a.totalIPFs + ' IPFs' : '') + (isNum(a.hardcoded) ? ' · ' + a.hardcoded + ' hardcoded' : '') + (a.finishedAt ? ' · ' + date(a.finishedAt) : '')));
      list.appendChild(item);
    }
    $('#appCount').textContent = shown + ' of ' + state.apps.length + ' evaluation' + (state.apps.length === 1 ? '' : 's');
  }

  // ------------------------------------------------------------------ one app

  async function selectApp(dir, tab) {
    state.dir = dir;
    state.app = state.apps.find(a => a.dir === dir) || null;
    state.tab = tab || 'overview';
    state.results = {};
    location.hash = encodeURIComponent(dir) + '/' + state.tab;
    renderList();
    $('#welcome').classList.add('d-none');
    const panel = $('#app');
    panel.classList.remove('d-none');
    $('#appHeader').innerHTML = '';
    $('#tabContent').innerHTML = '<p class="text-muted">Loading…</p>';
    try {
      const [report, inventory, meta] = await Promise.all([
        fetchJson(resultsUrl('report.json')),
        fetchJson(resultsUrl('strings_inventory.json'), true),
        fetchJson(resultsUrl('meta.json'), true)]);
      state.report = report;
      state.inventory = inventory;
      state.meta = meta;
    } catch (e) {
      $('#tabContent').innerHTML = '';
      $('#tabContent').appendChild(el('div', { class: 'alert alert-danger' }, 'Could not read the report: ' + e.message));
      return;
    }
    renderHeader();
    renderTabs();
    showTab(state.tab);
  }

  function renderHeader() {
    const r = state.report;
    const it = r.itdroid || {};
    const status = it.status || (r.error ? 'aborted' : 'finished');
    const h = $('#appHeader');
    h.innerHTML = '';
    h.appendChild(el('h3', null, r.appName || state.dir));
    h.appendChild(badge(status, statusKind(status)));
    if (state.meta && state.meta.status) h.appendChild(badge('harness: ' + state.meta.status, statusKind(state.meta.status)));
    h.appendChild(el('span', { class: 'text-muted small' }, (r.apkName || '') + (it.finishedAt ? ' · finished ' + date(it.finishedAt) : '') + (isNum(it.durationSec) ? ' · ' + sec(it.durationSec) : '')));
    if (r.error) {
      h.appendChild(el('div', { class: 'alert alert-danger w-100 mt-2 mb-0 py-2' }, el('strong', null, 'Run error: '), r.error));
    }
  }

  const TABS = [
    ['overview', 'Overview'], ['languages', 'Languages & IPFs'], ['hardcoded', 'Hardcoded strings'],
    ['performance', 'Performance'], ['environment', 'Environment'], ['artifacts', 'Artifacts']];

  function renderTabs() {
    const ul = $('#tabs');
    ul.innerHTML = '';
    for (const [id, label] of TABS) {
      ul.appendChild(el('li', { class: 'nav-item' },
        el('a', { class: 'nav-link' + (id === state.tab ? ' active' : ''), href: '#', 'data-tab': id,
          onclick: ev => { ev.preventDefault(); showTab(id); } }, label)));
    }
  }

  function showTab(id) {
    state.tab = id;
    location.hash = encodeURIComponent(state.dir) + '/' + id;
    for (const a of document.querySelectorAll('#tabs .nav-link')) {
      a.classList.toggle('active', a.getAttribute('data-tab') === id);
    }
    const content = $('#tabContent');
    content.innerHTML = '';
    const render = { overview: renderOverview, languages: renderLanguages, hardcoded: renderHardcoded,
      performance: renderPerformance, environment: renderEnvironment, artifacts: renderArtifacts }[id];
    try {
      render(content);
    } catch (e) {
      content.appendChild(el('div', { class: 'alert alert-danger' }, 'Could not render this section: ' + e.message));
    }
  }

  function languages() {
    const lr = state.report.langsReport || {};
    const codes = Object.keys(lr);
    const dflt = codes.find(c => lr[c].dflt) || state.report.dfltLang;
    return { lr, codes, dflt, targets: codes.filter(c => c !== dflt).sort() };
  }

  // ------------------------------------------------------------------ overview

  function renderOverview(root) {
    const r = state.report;
    const pre = r.preflight || {};
    const app = pre.app || {};
    const env = r.environment || {};
    const dev = env.device || pre.device || {};
    const llm = env.llm || {};
    const it = r.itdroid || {};
    const inv = r.inventory || {};
    const { lr, dflt, targets } = languages();

    const total = targets.reduce((s, c) => s + (isNum(lr[c].amIPFs) ? lr[c].amIPFs : 0), 0);
    const row = el('div', { class: 'row' });
    row.appendChild(el('div', { class: 'col-lg-6' }, card('Application', kvTable([
      ['Package', r.appName],
      ['APK', r.apkName + (isNum(r.apkSizeKB) ? ' (' + mb(r.apkSizeKB / 1024) + ')' : '')],
      ['Version', app.versionName ? app.versionName + (app.versionCode ? ' (' + app.versionCode + ')' : '') : null],
      ['SDK', app.minSdk != null ? 'min ' + app.minSdk + (app.targetSdk != null ? ', target ' + app.targetSdk : '') : null],
      ['Native code', pre.apkAbis ? pre.apkAbis : (pre.apkAbis === '' ? 'none (pure Java/Kotlin)' : null)],
      ['Source language', r.dfltLang],
      ['Launch test', pre.launchTest],
      ['Results folder', r.outputFolder]]))));
    row.appendChild(el('div', { class: 'col-lg-6' }, card('Run', kvTable([
      ['Status', it.status || (r.error ? 'aborted' : 'finished')],
      ['Started', date(it.startedAt)],
      ['Finished', date(it.finishedAt)],
      ['Duration', sec(it.durationSec)],
      ['Device', [dev.manufacturer, dev.model].filter(Boolean).join(' ') + (dev.sdk != null ? ' · Android ' + (dev.androidVersion || '') + ' (API ' + dev.sdk + ')' : '') + (dev.serial ? ' · ' + dev.serial : '')],
      ['Emulator', r.emulatorName],
      ['Translation model', llm.model ? llm.model + (llm.style ? ' (' + llm.style + ')' : '') : null],
      ['ITDroid', it.version || env.itdroidVersion]]))));
    root.appendChild(row);

    const totals = el('div', { class: 'row' });
    totals.appendChild(el('div', { class: 'col-md-4' }, stat('IPFs (all languages)', num(total), total > 0 ? 'text-danger' : 'text-success')));
    totals.appendChild(el('div', { class: 'col-md-4' }, stat('Hardcoded strings', num(r.hardcoded), isNum(inv.hardcodedTranslatable) ? inv.hardcodedTranslatable + ' look like UI text' : '')));
    totals.appendChild(el('div', { class: 'col-md-4' }, stat('Screens explored (' + (dflt || 'source') + ')', num(lr[dflt] ? lr[dflt].amStates : null), lr[dflt] ? num(lr[dflt].amTrans) + ' transitions' : '')));
    root.appendChild(totals);

    const rows = [];
    for (const c of targets) {
      const l = lr[c], rep = l.replay || {};
      rows.push([el('span', null, badge(c, 'info'), l.lang || c), l.amStates, l.pairedStates, (l.missingDfltStates || 0) + (l.missingLangStates || 0),
        rep.actions != null ? rep.reproduced + '/' + rep.actions + ' (' + pct(rep.reproduced, rep.actions) + ')' : '–',
        isNum(l.amIPFs) ? l.amIPFs : (l.error ? '–' : 0), l.error ? el('span', { class: 'text-danger' }, l.error) : (l.hardcodedFallback ? el('span', { class: 'text-warning' }, 'rebuilt without hardcoded patches') : 'ok')]);
    }
    root.appendChild(card('Per language', table(['Language', 'Screens', 'Paired', 'Unpaired', 'Replay', 'IPFs', 'Outcome'], rows, [1, 2, 3, 5])));
    if (inv.uniqueTranslatableTexts != null) {
      root.appendChild(card('String inventory', kvTable([
        ['Resource strings', num(inv.resources) + ' (' + num(inv.resourcesTranslatable) + ' translatable)'],
        ['Hardcoded literals', num(inv.hardcoded) + ' (' + num(inv.hardcodedTranslatable) + ' translatable)'],
        ['Unique texts sent to the translator', num(inv.uniqueTranslatableTexts)]])));
    }
  }

  function stat(label, value, sub) {
    return el('div', { class: 'card' }, el('div', { class: 'card-body py-2' },
      el('div', { class: 'text-muted small' }, label),
      el('div', { class: 'h3 mb-0 ' + (typeof sub === 'string' && sub.startsWith('text-') ? sub : '') }, value),
      typeof sub === 'string' && !sub.startsWith('text-') && sub ? el('div', { class: 'text-muted small' }, sub) : null));
  }

  // ------------------------------------------------------------------ languages & IPFs

  function renderLanguages(root) {
    const { lr, dflt, targets } = languages();
    if (!targets.length) {
      root.appendChild(el('p', { class: 'text-muted' }, 'No target language was evaluated.'));
      return;
    }
    root.appendChild(el('p', { class: 'text-muted small' },
      'An IPF is a node of a screen whose spatial relations to the other nodes changed in the translated version ' +
      '(a relation lost or gained: LEFT, RIGHT, ABOVE, BELOW, alignment). The screens are paired with the source exploration (' + (dflt || '') + ') ' +
      'by replayed state; expand a screen to see both versions with the affected nodes outlined.'));
    for (const c of targets) {
      const l = lr[c];
      const rep = l.replay || {};
      const head = el('div', { class: 'd-flex flex-wrap align-items-center' },
        el('strong', { class: 'mr-2' }, (l.lang || c) + ' (' + c + ')'),
        badge((isNum(l.amIPFs) ? l.amIPFs : 0) + ' IPFs', isNum(l.amIPFs) && l.amIPFs > 0 ? 'danger' : 'success'),
        badge(num(l.amStates) + ' screens', 'secondary'),
        badge(num(l.pairedStates) + ' paired', 'secondary'),
        rep.actions != null ? badge('replay ' + rep.reproduced + '/' + rep.actions, rep.reproduced === rep.actions ? 'success' : 'warning') : null,
        l.patchedHardcoded != null ? badge(l.patchedHardcoded + ' hardcoded patched', 'secondary') : null,
        l.error ? badge('error', 'danger') : null);
      const body = el('div');
      if (l.error) body.appendChild(el('div', { class: 'alert alert-danger py-2' }, l.error));
      if (l.hardcodedFallback) body.appendChild(el('div', { class: 'alert alert-warning py-2' }, l.hardcodedFallback));
      if (rep.divergences && rep.divergences.length) {
        const ul = el('ul', { class: 'small mb-2' });
        for (const d of rep.divergences.slice(0, 12)) ul.appendChild(el('li', null, d));
        if (rep.divergences.length > 12) ul.appendChild(el('li', { class: 'text-muted' }, '… ' + (rep.divergences.length - 12) + ' more'));
        body.appendChild(el('details', null, el('summary', { class: 'small text-muted state-toggle' }, 'Replay divergences (' + rep.divergences.length + ')'), ul));
      }
      const groups = groupIpfs(l.ipfs || []);
      if (!groups.length) {
        body.appendChild(el('p', { class: 'text-success mb-0' }, 'No presentation failures detected.'));
      } else {
        for (const g of groups) {
          const det = el('details', { class: 'mb-2' });
          det.appendChild(el('summary', { class: 'state-toggle' }, el('strong', null, 'Screen ' + g.stateId), ' · ' + g.ipfs.length + ' node' + (g.ipfs.length === 1 ? '' : 's') + ' with changed relations'));
          const holder = el('div', { class: 'mt-2' }, el('p', { class: 'text-muted small' }, 'Loading screens…'));
          det.appendChild(holder);
          det.addEventListener('toggle', () => { if (det.open && !det.dataset.loaded) { det.dataset.loaded = '1'; renderScreenPair(holder, c, dflt, g); } });
          body.appendChild(det);
        }
      }
      root.appendChild(el('div', { class: 'card' }, el('div', { class: 'card-header py-2' }, head), el('div', { class: 'card-body py-2' }, body)));
    }
  }

  function groupIpfs(ipfs) {
    const byState = new Map();
    for (const i of ipfs) {
      const id = String(i.stateID);
      if (!byState.has(id)) byState.set(id, { stateId: id, ipfs: [] });
      byState.get(id).ipfs.push(i);
    }
    return [...byState.values()].sort((a, b) => Number(a.stateId) - Number(b.stateId));
  }

  async function loadResult(lang, isSource) {
    const key = lang;
    if (state.results[key] !== undefined) return state.results[key];
    const folders = isSource ? ['trnsResults', 'noTrnsResults'] : ['noTrnsResults', 'trnsResults'];
    let data = null, folder = null;
    for (const f of folders) {
      data = await fetchJson(resultsUrl(f + '/' + lang + '/result.json'), true);
      if (data) { folder = f; break; }
    }
    const parsed = data ? parseResult(data, folder, lang) : null;
    state.results[key] = parsed;
    return parsed;
  }

  /** States of a result.json as {id -> {id, activity, shot, nodes[], bySource}} plus the screen size. */
  function parseResult(data, folder, lang) {
    const states = {};
    const bySource = {};
    const raw = data.states || {};
    const list = Array.isArray(raw) ? raw : Object.values(raw);
    for (const s of list) {
      const nodes = parseNodes(s.rawXML || '');
      const st = { id: String(s.id), activity: s.activityName || '', shot: s.screenShot ? resultsUrl(folder + '/' + lang + '/screenshots/' + basename(s.screenShot)) : null, nodes, sourceStateId: s.sourceStateId != null ? String(s.sourceStateId) : null };
      states[st.id] = st;
      if (st.sourceStateId != null && !bySource[st.sourceStateId]) bySource[st.sourceStateId] = st;
    }
    return { states, bySource };
  }

  /** The nodes of a uiautomator dump in document order (the same order ITDroid numbers them). */
  function parseNodes(xml) {
    const out = [];
    if (!xml) return out;
    let doc;
    try { doc = new DOMParser().parseFromString(xml, 'text/xml'); } catch (e) { return out; }
    const nodes = doc.getElementsByTagName('node');
    for (let i = 0; i < nodes.length; i++) {
      const n = nodes[i];
      const m = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(n.getAttribute('bounds') || '');
      out.push({ index: i, bounds: m ? [+m[1], +m[2], +m[3], +m[4]] : null, text: n.getAttribute('text') || '', desc: n.getAttribute('content-desc') || '',
        id: (n.getAttribute('resource-id') || '').split('/').pop(), cls: (n.getAttribute('class') || '').split('.').pop() });
    }
    return out;
  }

  function screenSize(nodes) {
    let w = 0, h = 0;
    for (const n of nodes) if (n.bounds) { w = Math.max(w, n.bounds[2]); h = Math.max(h, n.bounds[3]); }
    return [w || 1080, h || 2400];
  }

  function nodeLabel(n) {
    if (!n) return '?';
    const t = n.text || n.desc || n.id || '';
    return n.cls + (t ? ' "' + t + '"' : '');
  }

  function shotView(st, caption, ipfIndexes, relIndexes) {
    if (!st) return el('div', { class: 'shot' }, el('div', { class: 'shot-caption' }, caption + ': screen not captured'));
    const [w, h] = screenSize(st.nodes);
    const wrap = el('div', { class: 'shot' });
    if (st.shot) wrap.appendChild(el('img', { src: st.shot, alt: caption }));
    else wrap.appendChild(el('div', { class: 'p-3 text-muted small' }, 'no screenshot'));
    const draw = (idx, cls) => {
      const n = st.nodes[idx];
      if (!n || !n.bounds) return;
      const [x1, y1, x2, y2] = n.bounds;
      wrap.appendChild(el('div', { class: 'box ' + cls, title: '#' + idx + ' ' + nodeLabel(n),
        style: 'left:' + (100 * x1 / w) + '%;top:' + (100 * y1 / h) + '%;width:' + (100 * (x2 - x1) / w) + '%;height:' + (100 * (y2 - y1) / h) + '%' }));
    };
    for (const i of relIndexes) draw(i, 'rel');
    for (const i of ipfIndexes) draw(i, 'ipf');
    wrap.appendChild(el('div', { class: 'shot-caption' }, caption + (st.activity ? ' · ' + st.activity.replace(/}$/, '') : '')));
    return wrap;
  }

  async function renderScreenPair(holder, lang, dflt, group) {
    holder.innerHTML = '';
    let src = null, tgt = null;
    try {
      src = await loadResult(dflt, true);
      tgt = await loadResult(lang, false);
    } catch (e) {
      holder.appendChild(el('p', { class: 'text-muted small' }, 'Could not load the exploration results: ' + e.message));
    }
    const srcState = src ? src.states[group.stateId] : null;
    const tgtState = tgt ? (tgt.bySource[group.stateId] || tgt.states[group.stateId]) : null;
    const ipfIdx = group.ipfs.map(i => Number(i.nodeID));
    const relIdx = [];
    for (const i of group.ipfs) for (const r of (i.relations || [])) if (!ipfIdx.includes(Number(r.relNode))) relIdx.push(Number(r.relNode));
    holder.appendChild(el('div', { class: 'shot-pair mb-2' },
      shotView(srcState, 'Source (' + dflt + ')', ipfIdx, relIdx),
      shotView(tgtState, 'Translated (' + lang + ')', ipfIdx, relIdx)));
    holder.appendChild(el('p', { class: 'text-muted small mb-1' }, 'Red: nodes with changed relations. Dashed blue: the nodes they relate to. The translated screen outlines the nodes at the same positions (ITDroid pairs nodes positionally).'));
    const rows = [];
    for (const i of group.ipfs) {
      const n = srcState ? srcState.nodes[Number(i.nodeID)] : null;
      const rel = (i.relations || []).map(r => {
        const other = srcState ? srcState.nodes[Number(r.relNode)] : null;
        const parts = [];
        if (r.removed) parts.push('lost ' + r.removed.replace(/;$/, '').replace(/;/g, ', '));
        if (r.added) parts.push('gained ' + r.added.replace(/;$/, '').replace(/;/g, ', '));
        return el('div', null, el('span', { class: 'text-muted' }, '→ #' + r.relNode + ' '), el('span', { class: 'node-text', title: nodeLabel(other) }, nodeLabel(other)), ': ' + parts.join('; '));
      });
      rows.push(['#' + i.nodeID, el('span', { class: 'node-text', title: nodeLabel(n) }, nodeLabel(n)), el('div', null, ...rel)]);
    }
    holder.appendChild(table(['Node', 'Element (source)', 'Changed relations'], rows));
  }

  // ------------------------------------------------------------------ hardcoded strings

  function renderHardcoded(root) {
    const inv = state.inventory;
    const r = state.report;
    root.appendChild(el('p', { class: 'text-muted small' },
      'String literals found in the app code (const-string in the smali) that look like user-facing text. ITDroid translates the ones marked as translatable ' +
      'and patches them into each language build; the rest are data (identifiers, SQL, URLs, formats) and are kept verbatim.'));
    if (!inv || !Array.isArray(inv.entries)) {
      root.appendChild(el('p', null, 'Hardcoded strings detected: ' + num(r.hardcoded) + '. The inventory file (strings_inventory.json) is not available, see hcs.txt in the artifacts.'));
      return;
    }
    const hc = inv.entries.filter(e => e.kind === 'hardcoded');
    const { targets } = languages();
    const langsWith = targets.filter(c => hc.some(e => e.translations && e.translations[c] != null));
    const translatable = hc.filter(e => e.translatable);
    const totals = el('div', { class: 'row' });
    totals.appendChild(el('div', { class: 'col-md-4' }, stat('Hardcoded literals', num(hc.length), '')));
    totals.appendChild(el('div', { class: 'col-md-4' }, stat('Translated into the language builds', num(translatable.length), '')));
    totals.appendChild(el('div', { class: 'col-md-4' }, stat('Kept verbatim (data)', num(hc.length - translatable.length), '')));
    root.appendChild(totals);
    const headers = ['Text', 'Where', 'Translated'].concat(langsWith);
    const rows = hc.map(e => [el('span', { class: 'node-text', title: e.text }, e.text), el('span', { class: 'small text-muted' }, e.origin || ''),
      e.translatable ? badge('yes', 'success') : el('span', { class: 'small' }, badge('no', 'secondary'), e.skipReason || '')]
      .concat(langsWith.map(c => (e.translations && e.translations[c] != null) ? e.translations[c] : '')));
    root.appendChild(card('Literals', table(headers, rows)));
  }

  // ------------------------------------------------------------------ performance

  function renderPerformance(root) {
    const p = state.report.performance;
    const meta = state.meta;
    if (!p && !meta) {
      root.appendChild(el('p', { class: 'text-muted' }, 'This report has no performance section (it was produced by an older ITDroid).'));
      return;
    }
    if (p) {
      const phases = p.phases || {};
      const names = Object.keys(phases);
      const max = Math.max(1, ...names.map(n => phases[n]));
      const rows = names.map(n => [n, el('div', { class: 'phase-bar', style: 'width:' + Math.max(1, 100 * phases[n] / max) + '%' }), sec(phases[n]), pct(phases[n], p.totalSec)]);
      const ph = table(['Phase', '', 'Time', 'Share'], rows, [2, 3]);
      ph.querySelector('tbody').querySelectorAll('tr').forEach(tr => tr.classList.add('phase-row'));
      const row = el('div', { class: 'row' });
      row.appendChild(el('div', { class: 'col-lg-7' }, card('Phases (total ' + sec(p.totalSec) + ')', ph)));
      const llm = p.llm || {};
      const jvm = p.jvm || {};
      const host = p.host || {};
      const col = el('div', { class: 'col-lg-5' });
      col.appendChild(card('Translation model', kvTable([
        ['Requests', num(llm.requests)],
        ['Time waiting for translations', sec(llm.wallSeconds)],
        ['Sum of request latencies', sec(llm.requestSeconds)],
        ['Mean latency per request', isNum(llm.requests) && llm.requests > 0 ? (llm.requestSeconds / llm.requests).toFixed(2) + ' s' : '–'],
        ['Texts sent', num(llm.textsSent)],
        ['Texts reused from a previous run', num(llm.textsReused)],
        ['Texts kept in the source language', num(llm.textsKeptInSource)]])));
      col.appendChild(card('Machine', kvTable([
        ['ITDroid process CPU time', sec(jvm.cpuSeconds)],
        ['ITDroid peak heap', mb(jvm.peakHeapMB)],
        ['Host CPU load (mean / peak)', isNum(host.cpuLoadMeanPct) ? host.cpuLoadMeanPct + '% / ' + host.cpuLoadPeakPct + '%' : '–'],
        ['Host memory in use (mean / peak)', mb(host.memoryUsedMeanMB) + ' / ' + mb(host.memoryUsedPeakMB)],
        ['Samples', isNum(jvm.samples) ? jvm.samples + ' every ' + jvm.sampleIntervalSec + ' s' : '–']])));
      col.appendChild(el('p', { class: 'text-muted small' }, p.note || ''));
      row.appendChild(col);
      root.appendChild(row);
    }
    if (meta) {
      const res = meta.resources || {};
      const tm = meta.timing || {};
      root.appendChild(card('Evaluation harness telemetry (meta.json)', kvTable([
        ['Status', meta.status + (meta.note ? ' · ' + meta.note : '')],
        ['Wall time', sec(meta.wallSec)],
        ['Process tree RAM (peak / mean)', mb(res.ramPeakMB) + ' / ' + mb(res.ramMeanMB)],
        ['Process tree CPU (mean cores / peak cores)', isNum(res.cpuCoresMean) ? res.cpuCoresMean + ' / ' + res.cpuCoresPeak : '–'],
        ['LLM server RAM peak', mb(res.llmRamPeakMB)],
        ['LLM requests / seconds', isNum(tm.llmRequests) ? tm.llmRequests + ' / ' + sec(tm.llmSecApprox) : '–'],
        ['Exploration share of the run', isNum(tm.explorationShare) ? Math.round(100 * tm.explorationShare) + '%' : '–']])));
    }
  }

  // ------------------------------------------------------------------ environment

  function renderEnvironment(root) {
    const env = state.report.environment;
    const pre = state.report.preflight || {};
    if (!env) {
      root.appendChild(el('p', { class: 'text-muted' }, 'This report has no environment section (it was produced by an older ITDroid).'));
      if (pre.device) root.appendChild(card('Device (preflight)', kvTable(Object.entries(pre.device).map(([k, v]) => [k, Array.isArray(v) ? v.join(', ') : v]))));
      return;
    }
    const row = el('div', { class: 'row' });
    const section = (title, obj) => obj ? card(title, kvTable(Object.entries(obj).map(([k, v]) => [k, v && typeof v === 'object' ? (Array.isArray(v) ? v.join(', ') : Object.entries(v).map(([a, b]) => a + ' = ' + b).join(', ')) : String(v)]))) : null;
    row.appendChild(el('div', { class: 'col-lg-6' }, section('Host', env.host), section('Device', env.device)));
    row.appendChild(el('div', { class: 'col-lg-6' }, section('Translation engine', env.llm), section('Configuration', env.config)));
    root.appendChild(row);
    root.appendChild(el('p', { class: 'text-muted small' }, 'ITDroid ' + (env.itdroidVersion || '')));
  }

  // ------------------------------------------------------------------ artifacts

  function renderArtifacts(root) {
    const r = state.report;
    const { dflt, targets, codes } = languages();
    const link = (rel, label) => el('li', null, el('a', { href: resultsUrl(rel), target: '_blank' }, label || rel));
    const ul = el('ul', { class: 'mb-0' });
    ul.appendChild(link('report.json', 'report.json (everything shown here)'));
    ul.appendChild(link('strings_inventory.json', 'strings_inventory.json (every string with its origin and translations)'));
    ul.appendChild(link('ipfs.csv', 'ipfs.csv (one row per IPF)'));
    ul.appendChild(link('hcs.txt', 'hcs.txt (hardcoded strings by class)'));
    for (const c of targets) ul.appendChild(link('hardcoded_' + c + '.csv', 'hardcoded_' + c + '.csv (how each hardcoded string was translated)'));
    root.appendChild(card('Reports', ul));
    const apks = el('ul', { class: 'mb-0' });
    apks.appendChild(link(r.appName + '-aligned-debugSigned.apk', 'Default build (source language)'));
    for (const c of codes) apks.appendChild(link(r.appName + '-' + c + '-aligned-debugSigned.apk', c === dflt ? 'Source build (' + c + ')' : 'Translated build (' + c + ')'));
    root.appendChild(card('APKs built by ITDroid', apks));
    const ex = el('ul', { class: 'mb-0' });
    if (dflt) ex.appendChild(link('trnsResults/' + dflt + '/exploration_script.json', 'Recorded exploration path (' + dflt + ')'));
    for (const c of codes) {
      ex.appendChild(link((c === dflt ? 'trnsResults/' : 'noTrnsResults/') + c + '/result.json', 'Exploration result (' + c + ')'));
      if (c !== dflt) ex.appendChild(link('noTrnsResults/' + c + '/replay_report.json', 'Replay report (' + c + ')'));
    }
    root.appendChild(card('Explorations', ex));
    root.appendChild(el('p', { class: 'text-muted small' }, 'Languages the app already shipped are explored under trnsResults/, the ones ITDroid translated under noTrnsResults/.'));
  }

  // ------------------------------------------------------------------ boot

  $('#filter').addEventListener('input', renderList);
  $('#reload').addEventListener('click', loadIndex);
  window.addEventListener('hashchange', () => {
    const hash = decodeURIComponent(location.hash.replace(/^#/, ''));
    const [dir, tab] = hash.split('/');
    if (dir && dir !== state.dir && state.apps.some(a => a.dir === dir)) selectApp(dir, tab || 'overview');
  });
  loadIndex();
})();
