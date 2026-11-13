/*
 * ITDroid web report. Served by `java -jar target/ITDroid-1.0.0.jar --report [resultsFolder]`:
 * GET /api/results lists the evaluations of the folder, /api/files/<dir> the files of one of them,
 * and /results/<dir>/... serves those files.
 * No framework, no network access: everything comes from the local results folder.
 */
(function () {
  'use strict';

  const state = { apps: [], dir: null, app: null, report: null, inventory: null, meta: null, files: null, results: {}, tab: 'overview', open: null };

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
  const fileSize = v => !isNum(v) || v < 0 ? '' : v >= 1048576 ? (v / 1048576).toFixed(1) + ' MB' : v >= 1024 ? Math.round(v / 1024) + ' KB' : v + ' B';
  const date = iso => { if (!iso) return '–'; const d = new Date(iso); return isNaN(d) ? String(iso) : d.toLocaleString(); };
  const pct = (a, b) => (isNum(a) && isNum(b) && b > 0) ? Math.round(100 * a / b) + '%' : '–';
  const basename = p => p ? String(p).split(/[\\/]/).pop() : '';
  const plural = (n, one, many) => n + ' ' + (n === 1 ? one : (many || one + 's'));

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
    if (status === 'partial' || status === 'timeout' || status === 'blocked' || status === 'interrupted') return 'warning';
    if (status === 'running') return 'info';
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
      el('div', { class: 'card-header py-2' }, title instanceof Node ? title : el('strong', null, title)),
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

  /**
   * A table too long to draw whole: one page of rows at a time, with its pager. Whoever owns the
   * search box and the filters narrows it with setFilter, which answers how many rows are left.
   * columns: [{ title, cell: item => Node | string | null, numeric }]
   */
  function pagedTable(items, columns, emptyText, pageSize) {
    const size = pageSize || 50;
    let shown = items;
    let page = 0;
    const body = el('div');
    const info = el('span', { class: 'text-muted small mr-2' });
    const prev = el('button', { class: 'btn btn-sm btn-outline-secondary mr-1', type: 'button' }, '‹ Previous');
    const next = el('button', { class: 'btn btn-sm btn-outline-secondary', type: 'button' }, 'Next ›');
    const pager = el('div', { class: 'd-flex align-items-center justify-content-end mt-2' }, info, prev, next);
    const numeric = columns.map((c, i) => c.numeric ? i : -1).filter(i => i >= 0);
    function draw() {
      const pages = Math.max(1, Math.ceil(shown.length / size));
      page = Math.min(Math.max(0, page), pages - 1);
      const from = page * size;
      const slice = shown.slice(from, from + size);
      body.innerHTML = '';
      if (!shown.length) body.appendChild(el('p', { class: 'text-muted small mb-0' }, emptyText || 'Nothing to show.'));
      else body.appendChild(table(columns.map(c => c.title), slice.map(item => columns.map(c => c.cell(item))), numeric));
      info.textContent = !shown.length ? '' : 'Rows ' + (from + 1) + '–' + (from + slice.length) + ' of ' + shown.length
        + (shown.length !== items.length ? ' (' + items.length + ' before filtering)' : '');
      prev.disabled = page === 0;
      next.disabled = page >= pages - 1;
      prev.classList.toggle('d-none', pages <= 1);
      next.classList.toggle('d-none', pages <= 1);
    }
    prev.addEventListener('click', () => { page--; draw(); });
    next.addEventListener('click', () => { page++; draw(); });
    draw();
    return {
      node: el('div', null, body, pager),
      setFilter(test) { shown = test ? items.filter(test) : items; page = 0; draw(); return shown.length; }
    };
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
    // The name of the folder, not where it is on this machine: a screenshot of the page shows it.
    $('#rootInfo').textContent = basename(String(index.root || '').replace(/[\\/]+$/, ''));
    $('#rootInfo').setAttribute('title', index.root || '');
    renderList();
    const link = readHash();
    if (link.dir && state.apps.some(a => a.dir === link.dir)) {
      state.open = link.open;
      selectApp(link.dir, link.tab);
    }
  }

  /** #<evaluation>/<tab>, and for the languages tab optionally /<language>/<screen> to open that screen. */
  function readHash() {
    let parts;
    try {
      parts = location.hash.replace(/^#/, '').split('/').map(decodeURIComponent);
    } catch (e) {
      parts = [];   // an address that is not a valid link opens nothing
    }
    return { dir: parts[0] || null, tab: parts[1] || 'overview', open: parts[2] ? { lang: parts[2], stateId: parts[3] || null } : null };
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
        title: a.statusNote || '',
        onclick: ev => { ev.preventDefault(); state.open = null; selectApp(a.dir, 'overview'); } },
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
    state.tab = TABS.some(t => t[0] === tab) ? tab : 'overview';
    state.results = {};
    location.hash = encodeURIComponent(dir) + '/' + state.tab;
    renderList();
    $('#welcome').classList.add('d-none');
    const panel = $('#app');
    panel.classList.remove('d-none');
    $('#appHeader').innerHTML = '';
    $('#tabContent').innerHTML = '<p class="text-muted">Loading…</p>';
    try {
      const [report, inventory, meta, files] = await Promise.all([
        fetchJson(resultsUrl('report.json')),
        fetchJson(resultsUrl('strings_inventory.json'), true),
        fetchJson(resultsUrl('meta.json'), true),
        fetchJson('/api/files/' + encodeURIComponent(dir), true)]);
      state.report = report;
      state.inventory = inventory;
      state.meta = meta;
      state.files = files;
    } catch (e) {
      $('#tabContent').innerHTML = '';
      $('#tabContent').appendChild(el('div', { class: 'alert alert-danger' }, 'Could not read the report: ' + e.message));
      return;
    }
    renderHeader();
    renderTabs();
    showTab(state.tab);
  }

  /**
   * The status of the run. The server works it out: a run that was killed could not write that it
   * stopped, its report still says "running", and the server shows it as interrupted.
   */
  function runStatus() {
    const r = state.report;
    const it = r.itdroid || {};
    const own = it.status || (r.error ? 'aborted' : 'finished');
    const app = state.app;
    return app && app.status && app.status !== 'unreadable' ? { status: app.status, note: app.statusNote || null } : { status: own, note: null };
  }

  function renderHeader() {
    const r = state.report;
    const it = r.itdroid || {};
    const { status, note } = runStatus();
    const h = $('#appHeader');
    h.innerHTML = '';
    h.appendChild(el('h3', null, r.appName || state.dir));
    h.appendChild(badge(status, statusKind(status)));
    if (state.meta && state.meta.status) h.appendChild(badge('harness: ' + state.meta.status, statusKind(state.meta.status)));
    h.appendChild(el('span', { class: 'text-muted small' }, (r.apkName || '') + (it.finishedAt ? ' · finished ' + date(it.finishedAt) : '') + (isNum(it.durationSec) ? ' · ' + sec(it.durationSec) : '')));
    if (r.error) {
      h.appendChild(el('div', { class: 'alert alert-danger w-100 mt-2 mb-0 py-2' }, el('strong', null, 'Run error: '), r.error));
    }
    if (status === 'interrupted') {
      h.appendChild(el('div', { class: 'alert alert-warning w-100 mt-2 mb-0 py-2' }, el('strong', null, 'Interrupted run: '),
        'it stopped without finishing' + (note ? ' (' + note + ')' : '') + '. What is shown is what it had written until then' + (it.startedAt ? ' (it started ' + date(it.startedAt) + ')' : '') + '.'));
    } else if (status === 'running') {
      h.appendChild(el('div', { class: 'alert alert-info w-100 mt-2 mb-0 py-2' }, 'This run is still going' + (it.startedAt ? ' (it started ' + date(it.startedAt) + ')' : '') + '. Press Reload to see what it has written since.'));
    }
  }

  const TABS = [
    ['overview', 'Overview'], ['languages', 'Languages & IPFs'], ['hardcoded', 'Hardcoded strings'], ['strings', 'Non-internationalized strings'],
    ['performance', 'Performance'], ['environment', 'Environment'], ['artifacts', 'Artifacts']];

  function renderTabs() {
    const ul = $('#tabs');
    ul.innerHTML = '';
    for (const [id, label] of TABS) {
      ul.appendChild(el('li', { class: 'nav-item' },
        el('a', { class: 'nav-link' + (id === state.tab ? ' active' : ''), href: '#', 'data-tab': id,
          onclick: ev => { ev.preventDefault(); state.open = null; showTab(id); } }, label)));
    }
  }

  function showTab(id) {
    state.tab = id;
    const open = id === 'languages' && state.open ? '/' + encodeURIComponent(state.open.lang) + (state.open.stateId ? '/' + encodeURIComponent(state.open.stateId) : '') : '';
    location.hash = encodeURIComponent(state.dir) + '/' + id + open;
    for (const a of document.querySelectorAll('#tabs .nav-link')) {
      a.classList.toggle('active', a.getAttribute('data-tab') === id);
    }
    const content = $('#tabContent');
    content.innerHTML = '';
    const render = { overview: renderOverview, languages: renderLanguages, hardcoded: renderHardcoded, strings: renderStrings,
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

  /**
   * The folder that holds the exploration of a language: trnsResults when the app shipped the
   * language, noTrnsResults when ITDroid translated it. The report says it; for a report that does
   * not, the server looked on the disk. Null when neither knows.
   */
  function resultsFolder(lang) {
    const { lr, dflt } = languages();
    if (lang === dflt) return 'trnsResults';
    if (lr[lang] && lr[lang].resultsFolder) return lr[lang].resultsFolder;
    const listed = state.app && Array.isArray(state.app.languages) ? state.app.languages.find(l => l.code === lang) : null;
    return listed && listed.folder ? listed.folder : null;
  }

  /** True when the app already had the language. A report that does not say it is read from where the language was explored. */
  function shippedByApp(lang) {
    const l = (state.report.langsReport || {})[lang] || {};
    return l.shippedByApp != null ? !!l.shippedByApp : resultsFolder(lang) === 'trnsResults';
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
    const { status, note } = runStatus();

    const total = targets.reduce((s, c) => s + (isNum(lr[c].amIPFs) ? lr[c].amIPFs : 0), 0);
    const row = el('div', { class: 'row' });
    row.appendChild(el('div', { class: 'col-lg-6' }, card('Application', kvTable([
      ['Package', r.appName],
      ['APK', r.apkName + (isNum(r.apkSizeKB) ? ' (' + mb(r.apkSizeKB / 1024) + ')' : '')],
      ['APK SHA-256', r.apkSha256 ? el('span', { class: 'small text-monospace', title: r.apkSha256 }, r.apkSha256.substring(0, 16) + '…') : null],
      ['Version', app.versionName ? app.versionName + (app.versionCode ? ' (' + app.versionCode + ')' : '') : null],
      ['SDK', app.minSdk != null ? 'min ' + app.minSdk + (app.targetSdk != null ? ', target ' + app.targetSdk : '') : null],
      ['Native code', pre.apkAbis ? pre.apkAbis : (pre.apkAbis === '' ? 'none (pure Java/Kotlin)' : null)],
      ['Source language', r.dfltLang],
      ['Launch test', pre.launchTest],
      ['Results folder', state.dir]]))));
    row.appendChild(el('div', { class: 'col-lg-6' }, card('Run', kvTable([
      ['Status', status + (note ? ' (' + note + ')' : '')],
      ['Started', date(it.startedAt)],
      ['Finished', status === 'interrupted' && !it.finishedAt ? 'never: the run was interrupted' : date(it.finishedAt)],
      ['Duration', sec(it.durationSec)],
      ['Device', [dev.manufacturer, dev.model].filter(Boolean).join(' ') + (dev.sdk != null ? ' · Android ' + (dev.androidVersion || '') + ' (API ' + dev.sdk + ')' : '') + (dev.serial ? ' · ' + dev.serial : '')],
      ['Emulator', r.emulatorName],
      ['Translation model', llm.model ? llm.model + (llm.style ? ' (' + llm.style + ')' : '') : null],
      ['Adjusted to build', Array.isArray(r.buildAdjustments) && r.buildAdjustments.length ? r.buildAdjustments.join('; ') : null],
      ['ITDroid', it.version || env.itdroidVersion]]))));
    root.appendChild(row);

    const totals = el('div', { class: 'row' });
    totals.appendChild(el('div', { class: 'col-md-4' }, stat('IPFs (all languages)', num(total), total > 0 ? 'text-danger' : 'text-success')));
    totals.appendChild(el('div', { class: 'col-md-4' }, stat('Hardcoded strings', num(r.hardcoded), hardcodedBreakdown(inv))));
    totals.appendChild(el('div', { class: 'col-md-4' }, stat('Screens explored (' + (dflt || 'source') + ')', num(lr[dflt] ? lr[dflt].amStates : null), lr[dflt] ? num(lr[dflt].amTrans) + ' transitions' : '')));
    root.appendChild(totals);

    // The breakdown of the findings exists in reports of the element-by-element comparison.
    const broken = targets.some(c => lr[c].ipfSummary);
    const rows = [];
    for (const c of targets) {
      const l = lr[c], rep = l.replay || {}, s = l.ipfSummary;
      const outcome = l.error ? el('span', { class: 'text-danger' }, l.error) : (l.hardcodedFallback ? el('span', { class: 'text-warning' }, 'rebuilt without hardcoded patches') : 'ok');
      rows.push([el('span', null, badge(c, 'info'), l.lang || c), l.amStates, l.pairedStates, (l.missingDfltStates || 0) + (l.missingLangStates || 0),
        rep.actions != null ? rep.reproduced + '/' + rep.actions + ' (' + pct(rep.reproduced, rep.actions) + ')' : '–',
        isNum(l.amIPFs) ? l.amIPFs : (l.error ? '–' : 0)]
        .concat(broken ? [s ? s.nodesChanged : '–', s ? s.nodesAffected : '–', s ? (s.componentsLost + s.componentsAdded) : '–',
          s && s.mirrorExpected ? (s.byType || {}).missingMirroring : '–'] : [], [outcome]));
    }
    const headers = ['Language', 'Screens', 'Paired', 'Unpaired', 'Replay', 'IPFs'].concat(
      broken ? ['Moved or resized', 'Neighbour changed', 'Missing or extra', 'Not mirrored'] : [], ['Outcome']);
    root.appendChild(card('Per language', table(headers, rows, broken ? [1, 2, 3, 5, 6, 7, 8, 9] : [1, 2, 3, 5])));
    if (inv.uniqueTranslatableTexts != null) {
      root.appendChild(card('String inventory', kvTable([
        ['Resource strings', num(inv.resources) + ' (' + num(inv.resourcesTranslatable) + ' translatable)'],
        ['Hardcoded strings', num(inv.hardcoded) + ' (' + num(inv.hardcodedTranslatable) + ' translatable)'],
        ['Hardcoded strings by use', hardcodedByUse(inv)],
        ['Hardcoded translation policy', inv.hardcodedPolicy],
        ['Code literals moved to resources', isNum(inv.hardcodedCodeExtracted) ? num(inv.hardcodedCodeExtracted) + ' (' + num(inv.hardcodedPlacesExtracted) + ' places)' + (r.codeExtraction && r.codeExtraction.reverted ? ', put back: ' + r.codeExtraction.reverted : '') : null],
        ['Code literals patched per language', isNum(inv.hardcodedPlacesToPatch) ? num(inv.hardcodedPlacesToPatch) + ' places' : null],
        ['XML literals moved to resources', isNum(inv.hardcodedXmlExtracted) ? num(inv.hardcodedXmlExtracted) : null],
        ['Unique texts sent to the translator', num(inv.uniqueTranslatableTexts)]])));
    }
  }

  // One breakdown of the hardcoded strings, shared by the overview and the hardcoded tab. Reports made
  // before the breakdown existed only have the totals.
  function hardcodedBreakdown(inv) {
    if (!inv) return '';
    if (isNum(inv.hardcodedCode)) {
      return inv.hardcodedCode + ' in code, ' + inv.hardcodedXml + ' in XML, in ' + inv.hardcodedOccurrences + ' places · ' + inv.hardcodedTranslatable + ' translated';
    }
    return isNum(inv.hardcodedTranslatable) ? inv.hardcodedTranslatable + ' look like UI text' : '';
  }

  function hardcodedByUse(inv) {
    const by = inv && inv.hardcodedByConsumer;
    if (!by) return null;
    return Object.keys(by).sort((a, b) => by[b] - by[a]).map(k => k + ' ' + by[k]).join(' · ');
  }

  function stat(label, value, sub) {
    return el('div', { class: 'card' }, el('div', { class: 'card-body py-2' },
      el('div', { class: 'text-muted small' }, label),
      el('div', { class: 'h3 mb-0 ' + (typeof sub === 'string' && sub.startsWith('text-') ? sub : '') }, value),
      typeof sub === 'string' && !sub.startsWith('text-') && sub ? el('div', { class: 'text-muted small' }, sub) : null));
  }

  // ------------------------------------------------------------------ languages & IPFs

  /** The kinds of change of the comparison (ipfs[].types), in the order they are shown, and what happened to the element itself (ipfs[].geometry). */
  const IPF_TYPES = { positionLost: 'position lost', positionAdded: 'position gained', alignmentLost: 'alignment lost', alignmentAdded: 'alignment gained',
    overlapAdded: 'overlap', overlapRemoved: 'overlap gone', missingMirroring: 'not mirrored', componentLost: 'missing element', componentAdded: 'extra element' };
  const IPF_TYPE_HELP = {
    positionLost: 'The element is no longer left of, right of, above, below or inside another one, as it was in the source.',
    positionAdded: 'The element is now left of, right of, above, below or inside another one, and it was not in the source.',
    alignmentLost: 'The element shared an edge with another one in the source and no longer does. Not counted when either is a text.',
    alignmentAdded: 'The element shares an edge with another one and did not in the source. Not counted when either is a text.',
    overlapAdded: 'The element steps on another one and did not in the source.',
    overlapRemoved: 'The element stepped on another one in the source and no longer does.',
    missingMirroring: 'Right-to-left language: the element and a sibling next to it kept their sides instead of swapping them.',
    componentLost: 'The source screen has the element and the translated screen does not.',
    componentAdded: 'The translated screen has the element and the source screen does not.' };
  const IPF_GEOMETRY = { same: 'it stayed in place', mirrored: 'it was mirrored', moved: 'it moved', resized: 'it changed size', 'moved+resized': 'it moved and changed size' };

  const hasType = (ipf, type) => Array.isArray(ipf.types) && ipf.types.includes(type);

  function renderLanguages(root) {
    const { lr, dflt, targets } = languages();
    if (!targets.length) {
      root.appendChild(el('p', { class: 'text-muted' }, 'No target language was evaluated.'));
      return;
    }
    root.appendChild(el('p', { class: 'text-muted small' },
      'An IPF is an element of a screen whose place with respect to the other elements changed in the translated version ' +
      '(a relation lost or gained: LEFT, RIGHT, ABOVE, BELOW, alignment, overlap), or one that only one of the two screens has. ' +
      'In a right-to-left language the layout is expected to be mirrored, so what is reported there is what did not swap sides. ' +
      'The screens are paired with the source exploration (' + (dflt || '') + ') by replayed state; expand a screen to see both versions with the affected elements outlined.'));
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
        l.extractedCodeLiterals ? badge(l.extractedCodeLiterals + ' hardcoded read from resources', 'secondary') : null,
        shippedByApp(c) ? badge('shipped by the app', 'info') : null,
        l.error ? badge('error', 'danger') : null);
      const body = el('div');
      if (l.error) body.appendChild(el('div', { class: 'alert alert-danger py-2' }, l.error));
      if (l.hardcodedFallback) body.appendChild(el('div', { class: 'alert alert-warning py-2' }, l.hardcodedFallback + (isNum(l.revertedHardcoded) ? ' (' + l.revertedHardcoded + ' patched places reverted)' : '')));
      if (Array.isArray(l.crashes) && l.crashes.length) body.appendChild(el('div', { class: 'alert alert-danger py-2' }, 'The app crashed during the replay: ' + l.crashes.join(' | ')));
      if (rep.divergences && rep.divergences.length) {
        const ul = el('ul', { class: 'small mb-2' });
        for (const d of rep.divergences.slice(0, 12)) ul.appendChild(el('li', null, d));
        if (rep.divergences.length > 12) ul.appendChild(el('li', { class: 'text-muted' }, '… ' + (rep.divergences.length - 12) + ' more'));
        body.appendChild(el('details', null, el('summary', { class: 'small text-muted state-toggle' }, 'Replay divergences (' + rep.divergences.length + ')'), ul));
      }
      if (Array.isArray(l.statesNotCompared) && l.statesNotCompared.length) {
        const ul = el('ul', { class: 'small mb-2' });
        for (const s of l.statesNotCompared) ul.appendChild(el('li', null, 'Screen ' + s.stateID + ' ' + s.reason));
        body.appendChild(el('details', null, el('summary', { class: 'small text-muted state-toggle' }, 'Screens reached but not compared (' + l.statesNotCompared.length + ')'), ul));
      }
      const screens = screensView(c, l, dflt, lr[dflt] ? lr[dflt].amStates : null);
      // One kind of change at a time: the summary of the language picks it, the screens follow.
      if (l.ipfSummary) body.appendChild(ipfSummaryView(l, screens.setType));
      body.appendChild(screens.node);
      root.appendChild(el('div', { class: 'card' }, el('div', { class: 'card-header py-2' }, head), el('div', { class: 'card-body py-2' }, body)));
    }
  }

  /** Languages written from right to left, as ITDroid knows them (Helper.languageIsRTL). */
  const RTL_LANGUAGES = ['ar', 'arc', 'ckb', 'dv', 'fa', 'ha', 'he', 'iw', 'khw', 'ks', 'ps', 'ur', 'yi'];
  const isRtl = code => RTL_LANGUAGES.includes(String(code || '').toLowerCase().split(/[-_]/)[0]);

  /**
   * The screens of one language. By default the ones with findings, straight from the report. On
   * request every screen of the source exploration, read from the stored explorations: the ones
   * compared without findings, the ones reached but not compared (with the reason) and the ones
   * the replay never reached. Each opens with the two screenshots and their visual difference.
   */
  function screensView(lang, l, dflt, sourceScreens) {
    const ipfs = l.ipfs || [];
    // a right-to-left language against a left-to-right source, or the other way round
    const mirrorExpected = l.ipfSummary ? !!l.ipfSummary.mirrorExpected : isRtl(lang) !== isRtl(dflt);
    const wantsOther = state.open && state.open.lang === lang && state.open.stateId != null
      && !ipfs.some(i => String(i.stateID) === String(state.open.stateId));
    let type = null;
    let all = !!wantsOther;   // a link to a screen without findings needs the whole list
    const list = el('div');
    const onlyFindings = el('button', { class: 'btn btn-sm btn-outline-secondary mr-1', type: 'button' }, 'with findings ', el('span', { class: 'badge badge-light' }, String(groupIpfs(ipfs).length)));
    const everyScreen = el('button', { class: 'btn btn-sm btn-outline-secondary', type: 'button', title: 'Also the screens without findings, the ones not compared and the ones the replay did not reach' },
      'every screen ', el('span', { class: 'badge badge-light' }, isNum(sourceScreens) ? String(sourceScreens) : '…'));
    const bar = el('div', { class: 'd-flex flex-wrap align-items-center mb-2' }, el('span', { class: 'small text-muted mr-2' }, 'Screens:'), onlyFindings, everyScreen);
    const mark = () => { onlyFindings.classList.toggle('active', !all); everyScreen.classList.toggle('active', all); };
    onlyFindings.addEventListener('click', () => { all = false; mark(); draw(); });
    everyScreen.addEventListener('click', () => { all = true; mark(); draw(); });

    const screen = (group, status, kind, note) => {
      const det = el('details', { class: 'mb-2' });
      const notMirrored = group.ipfs.filter(i => hasType(i, 'missingMirroring')).length;
      det.appendChild(el('summary', { class: 'state-toggle' }, el('strong', null, 'Screen ' + group.stateId), ' · ',
        kind ? badge(status, kind) : status + ' ', notMirrored ? badge(notMirrored + ' not mirrored', 'danger') : null));
      const holder = el('div', { class: 'mt-2' }, el('p', { class: 'text-muted small' }, 'Loading screens…'));
      det.appendChild(holder);
      const load = () => { if (!det.dataset.loaded) { det.dataset.loaded = '1'; renderScreenPair(holder, lang, dflt, group, mirrorExpected, note); } };
      det.addEventListener('toggle', () => { if (det.open) load(); });
      if (state.open && state.open.lang === lang && String(state.open.stateId) === String(group.stateId)) {
        det.open = true;
        load();
      }
      return det;
    };

    let drawing = 0;
    async function draw() {
      const turn = ++drawing;
      list.innerHTML = '';
      const groups = groupIpfs(type ? ipfs.filter(i => hasType(i, type)) : ipfs);
      if (type) list.appendChild(el('p', { class: 'small mb-2' }, 'Showing only: ', el('strong', null, IPF_TYPES[type] || type), '. ', IPF_TYPE_HELP[type] || ''));
      if (!all || type) {
        if (!ipfs.length && !l.error) list.appendChild(el('p', { class: 'text-success mb-2' }, 'No presentation failures detected.'));
        for (const g of groups) list.appendChild(screen(g, plural(g.ipfs.length, 'element') + ' with a finding'));
        return;
      }
      list.appendChild(el('p', { class: 'text-muted small' }, 'Loading the explorations…'));
      let src = null, tgt = null;
      try {
        src = await loadResult(dflt, true);
        tgt = await loadResult(lang, false);
      } catch (e) {
        // shown below as "not stored"
      }
      if (turn !== drawing) return;   // another choice was made while the explorations loaded
      list.innerHTML = '';
      if (!src) {
        list.appendChild(el('p', { class: 'text-muted small' }, 'The exploration of the source language is not stored with this evaluation, so only the screens with findings can be listed.'));
        for (const g of groups) list.appendChild(screen(g, plural(g.ipfs.length, 'element') + ' with a finding'));
        return;
      }
      const says = Array.isArray(l.statesNotCompared);   // reports of the element-by-element comparison say which screens were left out
      const ids = Object.keys(src.states).sort((a, b) => Number(a) - Number(b));
      everyScreen.querySelector('span').textContent = String(ids.length);
      for (const id of ids) {
        const group = groups.find(g => g.stateId === id) || { stateId: id, ipfs: [] };
        const skipped = says ? l.statesNotCompared.find(s => String(s.stateID) === id) : null;
        const reached = tgt && (tgt.bySource[id] || (!Object.keys(tgt.bySource).length && tgt.states[id]));
        if (group.ipfs.length) list.appendChild(screen(group, plural(group.ipfs.length, 'element') + ' with a finding'));
        else if (skipped) list.appendChild(screen(group, 'not compared', 'warning', 'This screen was reached but not compared: it ' + skipped.reason + '.'));
        else if (!reached) list.appendChild(screen(group, 'not reached', 'secondary', l.error ? 'This language was not explored: ' + l.error : 'The replay did not reach this screen in this language, so there is nothing to compare it with.'));
        else if (says) list.appendChild(screen(group, 'no findings', 'success', 'This screen was compared and no element changed its place with respect to the others.'));
        else list.appendChild(screen(group, 'no findings reported', 'secondary', 'The report has no finding for this screen. It does not say whether the screen was compared (it was written by an older ITDroid).'));
      }
    }
    mark();
    draw();
    return { node: el('div', null, bar, list), setType(t) { type = t; draw(); } };
  }

  /**
   * The findings of a language by kind: how many elements were compared, how many of them moved or
   * changed size and how many only have a neighbour that did, what is missing or extra, and one
   * button per kind of change that narrows the screens below to it.
   */
  function ipfSummaryView(l, onType) {
    const s = l.ipfSummary;
    const by = s.byType || {};
    const box = el('div', { class: 'ipf-summary mb-3' });
    const share = n => isNum(s.nodesCompared) && s.nodesCompared > 0 ? ' (' + pct(n, s.nodesCompared) + ')' : '';
    box.appendChild(el('div', { class: 'row' },
      el('div', { class: 'col-6 col-md' }, stat('Elements compared', num(s.nodesCompared), '')),
      el('div', { class: 'col-6 col-md' }, stat('Moved or resized', num(s.nodesChanged), 'the element itself' + share(s.nodesChanged))),
      el('div', { class: 'col-6 col-md' }, stat('Neighbour changed', num(s.nodesAffected), 'the element stayed as it was' + share(s.nodesAffected))),
      el('div', { class: 'col-6 col-md' }, stat('Missing', num(s.componentsLost), 'only in the source screen')),
      el('div', { class: 'col-6 col-md' }, stat('Extra', num(s.componentsAdded), 'only in the translated screen'))));
    const buttons = el('div', { class: 'd-flex flex-wrap align-items-center mb-2' }, el('span', { class: 'small text-muted mr-2' }, 'Findings by kind of change:'));
    const all = [];
    const press = (button, type) => {
      for (const b of all) b.classList.remove('active');
      button.classList.add('active');
      onType(type);
    };
    const add = (label, type, count, danger) => {
      const b = el('button', { class: 'btn btn-sm mr-1 mb-1 ' + (danger ? 'btn-outline-danger' : 'btn-outline-secondary'), type: 'button', title: type ? IPF_TYPE_HELP[type] : 'Every finding' },
        label + ' ', el('span', { class: 'badge badge-light' }, String(count)));
      b.addEventListener('click', () => press(b, type));
      all.push(b);
      buttons.appendChild(b);
      return b;
    };
    add('all', null, isNum(l.amIPFs) ? l.amIPFs : 0).classList.add('active');
    for (const type of Object.keys(IPF_TYPES)) {
      if (by[type] > 0) add(IPF_TYPES[type], type, by[type], type === 'missingMirroring');
    }
    box.appendChild(buttons);
    box.appendChild(el('p', { class: 'small text-muted mb-1' }, 'A finding can carry several kinds of change, so the kinds add up to more than the findings.'));
    if (s.mirrorExpected) {
      const checked = s.siblingPairsChecked, failed = s.siblingPairsNotMirrored;
      box.appendChild(el('div', { class: 'alert ' + (failed > 0 ? 'alert-warning' : 'alert-success') + ' py-2 mb-0 small' },
        el('strong', null, 'Right-to-left language: '),
        'the layout is expected to be mirrored. Of ' + num(checked) + ' pairs of sibling elements placed side by side in the source, '
        + (failed > 0 ? num(failed) + ' (' + pct(failed, checked) + ') kept their sides.' : 'all swapped sides.')
        + (isNum(checked) && checked === 0 ? ' No screen had two siblings side by side to check.' : '')));
    }
    return box;
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
    const known = resultsFolder(lang);
    const folders = known ? [known] : (isSource ? ['trnsResults', 'noTrnsResults'] : ['noTrnsResults', 'trnsResults']);
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
      // screenShot is a path from the folder of the result.json; older results carry an absolute
      // path of the machine that made them, of which only the file name still means something.
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

  /**
   * A screenshot with boxes over it. ipfIndexes: the elements with a finding; relIndexes: the
   * elements they relate to; mirrorIndexes: elements that were expected to be mirrored and were
   * not, for which the place they would have in the mirrored screen is drawn too; onlyIndexes:
   * elements the other screen does not have.
   */
  function shotView(st, caption, ipfIndexes, relIndexes, mirrorIndexes, onlyIndexes) {
    if (!st) return el('div', { class: 'shot' }, el('div', { class: 'shot-caption' }, caption + ': screen not captured'));
    const [w, h] = screenSize(st.nodes);
    const wrap = el('div', { class: 'shot' });
    if (st.shot) wrap.appendChild(el('img', { src: st.shot, alt: caption }));
    else wrap.appendChild(el('div', { class: 'p-3 text-muted small' }, 'no screenshot'));
    const box = (bounds, cls, title) => {
      const [x1, y1, x2, y2] = bounds;
      wrap.appendChild(el('div', { class: 'box ' + cls, title,
        style: 'left:' + (100 * x1 / w) + '%;top:' + (100 * y1 / h) + '%;width:' + (100 * (x2 - x1) / w) + '%;height:' + (100 * (y2 - y1) / h) + '%' }));
    };
    const draw = (idx, cls) => {
      const n = st.nodes[idx];
      if (n && n.bounds) box(n.bounds, cls, '#' + idx + ' ' + nodeLabel(n));
    };
    for (const i of relIndexes) draw(i, 'rel');
    for (const i of (mirrorIndexes || [])) {
      const n = st.nodes[i];
      if (n && n.bounds) box(mirroredBounds(n.bounds, w), 'mirror', 'Estimated position of #' + i + ' ' + nodeLabel(n) + ' in a mirrored screen');
    }
    for (const i of ipfIndexes) draw(i, 'ipf');
    for (const i of (onlyIndexes || [])) draw(i, 'only');
    wrap.appendChild(el('div', { class: 'shot-caption' }, caption + (st.activity ? ' · ' + st.activity.replace(/}$/, '') : '')));
    return wrap;
  }

  /** Where an element would be if the screen were mirrored: as far from the right edge as it is from the left one. */
  function mirroredBounds(bounds, screenWidth) {
    const [x1, y1, x2, y2] = bounds;
    return [screenWidth - x2, y1, screenWidth - x1, y2];
  }

  /** Channels of a pixel differ by more than this (of 255) for the pixel to count as different: below it, it is noise of the rendering. */
  const DIFF_THRESHOLD = 32;

  /**
   * The visual difference between two screenshots, drawn on a canvas: the source screen faded to
   * grey, and in magenta every pixel that is not the same in the translated one. For a language
   * written in the other direction the translated screenshot is flipped first, so a layout that
   * mirrored well lines up with the source and what stands out is what did not.
   */
  function diffView(sourceShot, translatedShot, flipped) {
    const title = 'Visual difference' + (flipped ? ' (translated screen flipped)' : '');
    const wrap = el('div', { class: 'shot' });
    const caption = el('div', { class: 'shot-caption' }, title);
    if (typeof Image === 'undefined' || !sourceShot || !translatedShot) {
      caption.textContent = title + ': it needs the two screenshots';
      wrap.appendChild(caption);
      return wrap;
    }
    const canvas = el('canvas');
    wrap.appendChild(canvas);
    wrap.appendChild(caption);
    const a = new Image(), b = new Image();
    let loaded = 0;
    const compare = () => {
      if (++loaded < 2) return;
      try {
        const w = 360, h = Math.max(1, Math.round(w * a.naturalHeight / Math.max(1, a.naturalWidth)));
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(a, 0, 0, w, h);
        const source = ctx.getImageData(0, 0, w, h).data;
        ctx.clearRect(0, 0, w, h);
        if (flipped) { ctx.save(); ctx.translate(w, 0); ctx.scale(-1, 1); }
        ctx.drawImage(b, 0, 0, w, h);
        if (flipped) ctx.restore();
        const translated = ctx.getImageData(0, 0, w, h).data;
        const out = ctx.createImageData(w, h);
        let different = 0;
        for (let i = 0; i < source.length; i += 4) {
          const d = Math.max(Math.abs(source[i] - translated[i]), Math.abs(source[i + 1] - translated[i + 1]), Math.abs(source[i + 2] - translated[i + 2]));
          if (d > DIFF_THRESHOLD) {
            different++;
            out.data[i] = 214; out.data[i + 1] = 36; out.data[i + 2] = 159;
          } else {
            const grey = 0.3 * source[i] + 0.59 * source[i + 1] + 0.11 * source[i + 2];
            out.data[i] = out.data[i + 1] = out.data[i + 2] = 255 - (255 - grey) * 0.35;
          }
          out.data[i + 3] = 255;
        }
        ctx.putImageData(out, 0, 0);
        caption.textContent = title + ': ' + (100 * different / (w * h)).toFixed(1) + '% of the screen is not the same';
      } catch (e) {
        caption.textContent = title + ': could not be computed (' + e.message + ')';
      }
    };
    a.onload = compare;
    b.onload = compare;
    a.onerror = b.onerror = () => { caption.textContent = title + ': a screenshot could not be loaded'; };
    a.src = sourceShot;
    b.src = translatedShot;
    return wrap;
  }

  /**
   * One screen: the source and the translated screenshots with the findings outlined, the estimated
   * mirrored position when it applies, the visual difference and the table of findings. note is
   * what to say of a screen with no findings (it was compared and is clean, it was not compared
   * and why, it was not reached).
   */
  async function renderScreenPair(holder, lang, dflt, group, mirrorExpected, note) {
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
    // An element has a position in the source screen (nodeID) and one in the translated screen
    // (langNodeID); -1 when that screen does not have it. Reports written before elements were
    // paired one by one only carry nodeID, which was the same position in both screens.
    const tgtPos = i => i.langNodeID != null ? Number(i.langNodeID) : Number(i.nodeID);
    const tgtRelPos = r => r.langRelNode != null ? Number(r.langRelNode) : Number(r.relNode);
    const isLost = i => i.kind === 'componentLost', isAdded = i => i.kind === 'componentAdded';
    const srcIpf = group.ipfs.filter(i => !isLost(i)).map(i => Number(i.nodeID)).filter(n => n >= 0);
    const tgtIpf = group.ipfs.filter(i => !isAdded(i)).map(tgtPos).filter(n => n >= 0);
    // what only one of the two screens has: outlined in that screen, in its own colour
    const srcOnly = group.ipfs.filter(isLost).map(i => Number(i.nodeID)).filter(n => n >= 0);
    const tgtOnly = group.ipfs.filter(isAdded).map(tgtPos).filter(n => n >= 0);
    const srcRel = [], tgtRel = [];
    for (const i of group.ipfs) for (const r of (i.relations || [])) {
      if (!srcIpf.includes(Number(r.relNode))) srcRel.push(Number(r.relNode));
      if (!tgtIpf.includes(tgtRelPos(r))) tgtRel.push(tgtRelPos(r));
    }
    const notMirrored = mirrorExpected ? group.ipfs.filter(i => hasType(i, 'missingMirroring')).map(tgtPos).filter(n => n >= 0) : [];
    const shots = el('div', { class: 'shot-pair mb-2' },
      shotView(srcState, 'Source (' + dflt + ')', srcIpf, srcRel, null, srcOnly),
      shotView(tgtState, 'Translated (' + lang + ')', tgtIpf, tgtRel, null, tgtOnly));
    if (notMirrored.length) shots.appendChild(shotView(tgtState, 'Estimated position (' + lang + ')', notMirrored, [], notMirrored));
    if (srcState && tgtState) shots.appendChild(diffView(srcState.shot, tgtState.shot, mirrorExpected));
    holder.appendChild(shots);
    if (note) holder.appendChild(el('p', { class: 'small mb-1' }, note));
    const hasOnly = srcOnly.length + tgtOnly.length > 0;
    holder.appendChild(el('p', { class: 'text-muted small mb-1' },
      group.ipfs.length ? el('span', { class: 'legend ipf' }) : null,
      group.ipfs.length ? 'the elements with a finding, each outlined where it is in that screen. ' : null,
      srcRel.length + tgtRel.length ? el('span', { class: 'legend rel' }) : null,
      srcRel.length + tgtRel.length ? 'the elements they relate to. ' : null,
      hasOnly ? el('span', { class: 'legend only' }) : null,
      hasOnly ? 'an element only that screen has: missing in the translated screen when it is outlined in the source, extra when it is outlined in the translated one. ' : null,
      notMirrored.length ? el('span', { class: 'legend mirror' }) : null,
      notMirrored.length ? 'in the estimated position image, where each element that was not mirrored would be if the screen were mirrored: as far from the right edge as it is now from the left one. It is an estimate, the layout decides the real place. ' : null,
      srcState && tgtState ? el('span', { class: 'legend diff' }) : null,
      srcState && tgtState ? 'in the visual difference, every pixel that is not the same in the two screenshots, over the source screen in grey'
        + (mirrorExpected ? '; the translated screenshot is flipped first, so a layout that mirrored well lines up with the source.' : '. A translated text always differs; a block out of place is what to look for.') : null));
    if (!group.ipfs.length) return;
    const list = s => s.replace(/;$/, '').replace(/;/g, ', ');
    const rows = [];
    for (const i of group.ipfs) {
      const lost = i.kind === 'componentLost', added = i.kind === 'componentAdded';
      const n = added ? (tgtState ? tgtState.nodes[tgtPos(i)] : null) : (srcState ? srcState.nodes[Number(i.nodeID)] : null);
      const inside = isNum(i.score) && i.score > 1 ? ' (with the ' + (i.score - 1) + ' element' + (i.score === 2 ? '' : 's') + ' inside it)' : '';
      let what;
      if (lost) what = 'The translated screen does not have it' + inside + '.';
      else if (added) what = 'The source screen does not have it' + inside + '.';
      else {
        what = el('div', null, ...(i.relations || []).map(r => {
          const other = srcState ? srcState.nodes[Number(r.relNode)] : null;
          const parts = [];
          if (r.removed) parts.push('lost ' + list(r.removed));
          if (r.added) parts.push('gained ' + list(r.added));
          if (r.missing) parts.push('not mirrored, expected ' + list(r.missing));
          return el('div', null, el('span', { class: 'text-muted' }, '→ #' + r.relNode + ' '), el('span', { class: 'node-text', title: nodeLabel(other) }, nodeLabel(other)), ': ' + parts.join('; '));
        }));
      }
      const kinds = el('div', null,
        hasType(i, 'missingMirroring') ? badge('Missing mirroring', 'danger') : null,
        (i.types || []).filter(t => t !== 'missingMirroring').map(t => IPF_TYPES[t] || t).join(', '),
        i.geometry && IPF_GEOMETRY[i.geometry] ? el('div', { class: 'small text-muted' }, IPF_GEOMETRY[i.geometry]) : null);
      rows.push([added ? '– / #' + tgtPos(i) : '#' + i.nodeID + (lost ? ' / –' : ''),
        el('span', { class: 'node-text', title: nodeLabel(n) }, nodeLabel(n)), kinds, what]);
    }
    holder.appendChild(table(['Node', 'Element', 'What changed', 'Detail'], rows));
  }

  // ------------------------------------------------------------------ hardcoded strings

  /**
   * The hardcoded strings in the groups of the original report: taken out of the code into a
   * resource, taken out of the XML into a resource, and left where they were. This version adds
   * the ones translated by patching the code of each language build, for the places a resource
   * could not serve. Every string is in one group, so the groups add up to the total.
   * Inventories written before each place was recorded only say whether a string was translated.
   */
  function hardcodedGroups(hc) {
    const detailed = hc.some(e => Array.isArray(e.places));
    const groups = { code: [], xml: [], patched: [], kept: [] };
    for (const e of hc) {
      const places = e.places || [];
      if (e.kind === 'hardcoded-xml') (e.extractedTo ? groups.xml : groups.kept).push(e);
      else if (places.some(p => p.extracted)) groups.code.push(e);
      else if (detailed ? places.some(p => p.translated) : e.translatable) groups.patched.push(e);
      else groups.kept.push(e);
    }
    return { groups, detailed };
  }

  const shortClass = name => name ? String(name).split('.').pop() : '';

  /** Where a string is: its first place, and the rest behind a toggle. With marks, each place says what was done there. */
  function placesCell(e, marks) {
    const places = e.places || [];
    if (!places.length) return el('span', { class: 'small text-muted' }, e.origin || '');
    const line = p => {
      const where = p.class ? shortClass(p.class) + (p.method ? '.' + p.method : '')
        : (p.element ? '<' + p.element + '> ' + (p.attribute || '') + (p.id ? ' ' + p.id : '') : '');
      const mark = !marks ? null : p.extracted ? badge('resource', 'success') : p.translated ? badge('patched', 'info') : badge('kept', 'secondary');
      return el('div', { class: 'small', title: [p.class, p.origin, p.via ? 'handed to ' + p.via : ''].filter(Boolean).join('\n') },
        mark, where, el('span', { class: 'text-muted' }, (where ? ' · ' : '') + (p.origin || '') + (p.consumer && p.consumer !== e.consumer ? ' · ' + p.consumer : '')));
    };
    if (places.length === 1) return line(places[0]);
    return el('div', null, line(places[0]),
      el('details', null, el('summary', { class: 'small text-muted state-toggle' }, '+' + plural(places.length - 1, 'more place')), ...places.slice(1).map(line)));
  }

  /** Everything a string can be found by, in lower case. */
  function searchText(e) {
    if (e._search == null) {
      const parts = [e.text, e.extractedTo, e.origin, e.consumer, e.skipReason];
      for (const p of (e.places || [])) parts.push(p.class, p.method, p.origin, p.via, p.element, p.attribute, p.id);
      for (const t of Object.values(e.translations || {})) parts.push(t);
      e._search = parts.filter(Boolean).join('\n').toLowerCase();
    }
    return e._search;
  }

  function renderHardcoded(root) {
    const inv = state.inventory;
    const r = state.report;
    const { lr, targets } = languages();
    root.appendChild(el('p', { class: 'text-muted small' },
      'Text written in the app instead of in its string resources: literals of the code (const-string in the smali) and of the XML (layouts, menus, preferences, manifest). ' +
      'ITDroid translates the ones the app shows. Where it can, it moves the text to a string resource, so one build serves every language; ' +
      'where it cannot, it patches the translation into the build of each language. Logs, exception messages and data (keys, SQL, identifiers, formats) are kept verbatim.'));
    if (!inv || !Array.isArray(inv.entries)) {
      root.appendChild(el('p', null, 'Hardcoded strings detected: ' + num(r.hardcoded) + '. The inventory file (strings_inventory.json) is not available, see hcs.txt in the artifacts.'));
      return;
    }
    const hc = inv.entries.filter(e => e.kind === 'hardcoded' || e.kind === 'hardcoded-xml');
    const inXml = hc.filter(e => e.kind === 'hardcoded-xml').length;
    const { groups, detailed } = hardcodedGroups(hc);
    const translated = groups.code.length + groups.xml.length + groups.patched.length;
    const langsWith = targets.filter(c => hc.some(e => e.translations && e.translations[c] != null));

    // What each language build really carries. A language whose build failed with the translated
    // strings was rebuilt without them: there the strings of the code are back in the source text.
    const applied = targets.map(c => {
      const l = lr[c];
      if (l.error) return { code: c, lang: l.lang || c, error: l.error };
      const reverted = !!l.hardcodedFallback;
      return { code: c, lang: l.lang || c, reverted, why: l.hardcodedFallback,
        fromCode: reverted ? 0 : (isNum(l.extractedCodeLiterals) ? l.extractedCodeLiterals : (detailed ? groups.code.length : null)),
        fromXml: isNum(l.extractedXmlLiterals) ? l.extractedXmlLiterals : (detailed ? groups.xml.length : null),
        patched: isNum(l.patchedHardcoded) ? l.patchedHardcoded : null,
        revertedPlaces: isNum(l.revertedHardcoded) ? l.revertedHardcoded : null };
    });
    const evaluated = applied.filter(a => !a.error);
    const anyReverted = evaluated.some(a => a.reverted);

    const totals = el('div', { class: 'row' });
    totals.appendChild(el('div', { class: 'col-md-4' }, stat('Hardcoded strings', num(hc.length), (hc.length - inXml) + ' in code, ' + inXml + ' in XML'
      + (inv.counts && isNum(inv.counts.hardcodedOccurrences) ? ', in ' + inv.counts.hardcodedOccurrences + ' places' : ''))));
    totals.appendChild(el('div', { class: 'col-md-4' }, stat('Translated', num(translated),
      detailed ? groups.code.length + ' read from resources (code), ' + groups.xml.length + ' from XML, ' + groups.patched.length + ' patched in each build'
        + (anyReverted ? ' · not in every language, see below' : '')
        : 'patched into the build of each language')));
    totals.appendChild(el('div', { class: 'col-md-4' }, stat('Kept verbatim', num(groups.kept.length), hardcodedByUse(inv.counts) || '')));
    root.appendChild(totals);

    if (r.codeExtraction && r.codeExtraction.reverted) {
      root.appendChild(el('div', { class: 'alert alert-warning py-2' }, 'The strings of the code could not be read from resources and were put back: ' + r.codeExtraction.reverted));
    }
    if (applied.length) {
      const rows = applied.map(a => a.error
        ? [el('span', null, badge(a.code, 'info'), a.lang), '–', '–', '–', el('span', { class: 'text-danger small' }, 'not evaluated: ' + a.error)]
        : [el('span', null, badge(a.code, 'info'), a.lang), a.fromCode, a.fromXml, a.patched,
          a.reverted ? el('span', { class: 'text-warning small' }, (a.revertedPlaces != null ? plural(a.revertedPlaces, 'place') + ' of the code reverted: ' : '') + a.why) : 'as planned']);
      root.appendChild(card('Applied in each language build', el('div', null,
        table(['Language', 'Code strings read from resources', 'XML strings read from resources', 'Places patched in the code', 'Outcome'], rows, [1, 2, 3]),
        el('p', { class: 'text-muted small mb-0 mt-2' }, 'A build that did not start, or crashed where the original app did not, is rebuilt with the translated resources only; its hardcoded strings of the code stay in the source language.'))));
    }

    // One search box and one filter for the four groups; each group has its own pages.
    const uses = {};
    for (const e of hc) if (e.consumer) uses[e.consumer] = (uses[e.consumer] || 0) + 1;
    const search = el('input', { class: 'form-control form-control-sm', type: 'search', placeholder: 'Search text, class, method, file, resource or translation', 'aria-label': 'Search hardcoded strings' });
    const useSelect = el('select', { class: 'form-control form-control-sm', 'aria-label': 'Filter by use' }, el('option', { value: '' }, 'Any use'),
      ...Object.keys(uses).sort((a, b) => uses[b] - uses[a]).map(u => el('option', { value: u }, u + ' (' + uses[u] + ')')));
    const found = el('span', { class: 'text-muted small ml-2' });
    root.appendChild(el('div', { class: 'form-row align-items-center mb-2' },
      el('div', { class: 'col-md-6' }, search), Object.keys(uses).length ? el('div', { class: 'col-md-3' }, useSelect) : null, el('div', { class: 'col-md-3' }, found)));

    const text = e => el('span', { class: 'node-text', title: e.text }, e.text);
    const resource = e => e.extractedTo ? el('code', { class: 'small text-nowrap' }, e.extractedTo) : '';
    const use = e => el('span', { class: 'small text-nowrap' }, e.consumer || '');
    const translations = langsWith.map(c => ({ title: c, cell: e => (e.translations && e.translations[c] != null) ? el('span', { dir: 'auto' }, e.translations[c]) : '' }));
    const why = e => el('span', { class: 'small why' }, e.skipReason || (e.consumer ? 'the app does not show it there (use: ' + e.consumer + ')' : 'not translated'));
    const sections = [
      ['code', 'Extracted from the code', 'Literals of the code that the app shows. The literal stays in the code and is handed to a small class ITDroid adds to the app, which returns the string resource of that text in the language the app runs in. One build serves every language.',
        [{ title: 'Text', cell: text }, { title: 'Resource', cell: resource }, { title: 'Class and method', cell: e => placesCell(e, true) }, { title: 'Use', cell: use }].concat(translations)],
      ['xml', 'Extracted from the XML', 'Text written in an attribute of a layout, a menu, a preference screen or the manifest (android:text, hint, title...). The attribute now points to a string resource, translated like the rest.',
        [{ title: 'Text', cell: text }, { title: 'Resource', cell: resource }, { title: 'File and attribute', cell: e => placesCell(e, false) }].concat(translations)],
      ['patched', detailed ? 'Patched in each language build' : 'Translated in each language build',
        detailed ? 'Literals the app shows that could not be read from a resource (their dex file has no room for one more reference). The translation is written into the code of the build of each language; the internationalized build keeps the source text.'
          : 'Literals that look like text of the interface, translated and written into the code of the build of each language. This report does not record where each one is used.',
        [{ title: 'Text', cell: text }, { title: 'Class and method', cell: e => placesCell(e, detailed) }].concat(detailed ? [{ title: 'Use', cell: use }] : [], translations)],
      ['kept', 'Not translated', 'Kept exactly as written, with the reason: what the code does with the text (a log, an exception, a key, a query), or what the text is (an identifier, a format, a constant).',
        [{ title: 'Text', cell: text }, { title: 'Class and method, or file', cell: e => placesCell(e, false) }].concat(detailed ? [{ title: 'Use', cell: use }] : [], [{ title: 'Why not', cell: why }])]];
    const tables = [];
    for (const [key, title, help, columns] of sections) {
      const items = groups[key];
      // No empty group for what does not apply: nothing is patched when every string is read from
      // a resource, and an inventory without places cannot tell the extracted ones apart.
      if (!items.length && (key === 'patched' || !detailed)) continue;
      const count = el('span', { class: 'badge badge-secondary ml-2' }, String(items.length));
      const paged = pagedTable(items, columns, 'No string in this group' + (items.length ? ' matches the search.' : '.'));
      tables.push({ items, paged, count });
      root.appendChild(card(el('span', null, el('strong', null, title), count), el('div', null, el('p', { class: 'text-muted small mb-2' }, help), paged.node)));
    }
    const apply = () => {
      const q = search.value.trim().toLowerCase();
      const u = useSelect.value;
      const test = !q && !u ? null : e => (!q || searchText(e).includes(q)) && (!u || e.consumer === u);
      let total = 0;
      for (const t of tables) {
        const left = t.paged.setFilter(test);
        total += left;
        t.count.textContent = test ? left + ' of ' + t.items.length : String(t.items.length);
      }
      found.textContent = test ? plural(total, 'string') + ' found' : '';
    };
    search.addEventListener('input', apply);
    useSelect.addEventListener('change', apply);
  }

  // ------------------------------------------------------------------ non-internationalized strings

  function renderStrings(root) {
    const r = state.report;
    const ni = r.nonInternationalized;
    const { lr, targets } = languages();
    root.appendChild(el('p', { class: 'text-muted small' },
      'The strings of the default language that a language lacks: the app would show them in the default language there. ' +
      'A language the app does not ship usually still has the strings its libraries bring translated; the ones of the app itself are missing, and ITDroid translates them. ' +
      'A language the app ships is evaluated as the developer left it, and what it lacks is what is still to be internationalized.'));
    if (!ni || !ni.languages) {
      root.appendChild(el('p', { class: 'text-muted' }, 'This report does not say which strings each language lacks (it was produced by an older ITDroid).'));
      return;
    }
    if (ni.defaultStringsFile === false) {
      root.appendChild(el('div', { class: 'alert alert-info py-2' }, 'The app has no default strings.xml: it has no string resources, only hardcoded strings.'));
    }
    const codes = Object.keys(ni.languages).sort();
    const rows = codes.map(c => {
      const v = ni.languages[c];
      const l = lr[c] || {};
      return [el('span', null, badge(c, 'info'), l.lang || c),
        v.shippedByApp ? badge('shipped by the app', 'success') : badge('translated by ITDroid', 'secondary'),
        v.stringsFile ? 'yes' : 'no', v.missing, isNum(ni.defaultStrings) ? pct(v.missing, ni.defaultStrings) : '–'];
    });
    root.appendChild(card('Per language', el('div', null,
      table(['Language', 'Translation', 'Has a strings.xml', 'Strings it lacks', 'Of the ' + num(ni.defaultStrings) + ' default strings'], rows, [3, 4]),
      el('p', { class: 'text-muted small mb-0 mt-2' }, 'A language counts as shipped by the app when it lacks at most alpha strings (alpha = ' + num(ni.alpha) + ' in this run). '
        + 'Default strings that are not text (a number, a reference, an identifier) are not counted.'))));

    // name -> the string of the inventory, to show what each missing name says and what ITDroid made of it
    const byName = {};
    if (state.inventory && Array.isArray(state.inventory.entries)) {
      for (const e of state.inventory.entries) if (e.name && !byName[e.name]) byName[e.name] = e;
    }
    for (const c of codes) {
      const v = ni.languages[c];
      const names = Array.isArray(v.missingNames) ? v.missingNames : [];
      if (!names.length) continue;
      const items = names.map(name => ({ name, entry: byName[name] || null }));
      const columns = [{ title: 'Name', cell: i => el('code', { class: 'small' }, i.name) },
        { title: 'Default text', cell: i => i.entry ? el('span', { class: 'node-text', title: i.entry.text }, i.entry.text) : '' },
        { title: v.shippedByApp ? 'In this language' : 'Translation by ITDroid',
          cell: i => i.entry && i.entry.translations && i.entry.translations[c] != null ? el('span', { dir: 'auto' }, i.entry.translations[c])
            : el('span', { class: 'small text-muted' }, i.entry && i.entry.translatable === false ? 'kept: ' + (i.entry.skipReason || 'not translatable') : (v.shippedByApp ? 'shown in the default language' : '')) }];
      const search = el('input', { class: 'form-control form-control-sm mb-2', type: 'search', placeholder: 'Search name or text', 'aria-label': 'Search strings of ' + c });
      const det = el('details', { class: 'mb-2' });
      det.appendChild(el('summary', { class: 'state-toggle' }, el('strong', null, ((lr[c] || {}).lang || c) + ' (' + c + ')'), ' · ' + plural(names.length, 'string') + ' it lacks'));
      const holder = el('div', { class: 'mt-2' });
      det.appendChild(holder);
      det.addEventListener('toggle', () => {
        if (!det.open || det.dataset.loaded) return;
        det.dataset.loaded = '1';
        const paged = pagedTable(items, columns, 'No string matches the search.');
        search.addEventListener('input', () => {
          const q = search.value.trim().toLowerCase();
          paged.setFilter(!q ? null : i => (i.name + '\n' + (i.entry ? i.entry.text + '\n' + ((i.entry.translations || {})[c] || '') : '')).toLowerCase().includes(q));
        });
        holder.appendChild(search);
        holder.appendChild(paged.node);
      });
      root.appendChild(det);
    }
  }

  // ------------------------------------------------------------------ performance

  function renderPerformance(root) {
    const p = state.report.performance;
    const meta = state.meta;
    if (!p && !meta) {
      root.appendChild(el('p', { class: 'text-muted' }, runStatus().status === 'interrupted'
        ? 'The run was interrupted before it wrote how long each phase took.'
        : 'This report has no performance section (it was produced by an older ITDroid, or the run has not finished).'));
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
        ['Texts kept in the source language', num(llm.textsKeptInSource)],
        ['Answers rejected (not a translation)', isNum(llm.answersRejected) ? num(llm.answersRejected) : null]])));
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
    root.appendChild(el('p', { class: 'text-muted small' }, 'ITDroid ' + (env.itdroidVersion || '') + '. The report keeps no user name and no path of the machine it ran on: files are named from the results folder.'));
  }

  // ------------------------------------------------------------------ artifacts

  /** What each file of an evaluation is. First match wins; $1 is the language of a per-language file. */
  const ARTIFACT_LABELS = [
    [/^report\.json$/, 'everything shown in this page'],
    [/^strings_inventory\.json$/, 'every string with its origin and its translations'],
    [/^ipfs\.csv$/, 'one row per IPF'],
    [/^hcs\.txt$/, 'hardcoded strings by class and method'],
    [/^hardcoded_(.+)\.csv$/, 'what was done with each hardcoded string in $1, place by place'],
    [/^meta\.json$/, 'telemetry of the evaluation harness']];

  function renderArtifacts(root) {
    const r = state.report;
    const { lr, dflt, codes } = languages();
    const listing = state.files && Array.isArray(state.files.files) && !state.files.error ? state.files : null;
    if (!listing) {
      root.appendChild(el('div', { class: 'alert alert-warning py-2' }, 'The server did not list the files of this evaluation' + (state.files && state.files.error ? ' (' + state.files.error + ')' : '') + '. The links below are the usual ones and some may not exist.'));
    }
    // With the listing, only what is on the disk is linked. Without it, the names a run usually writes.
    const usual = ['report.json', 'strings_inventory.json', 'ipfs.csv', 'hcs.txt'].concat(codes.filter(c => c !== dflt).map(c => 'hardcoded_' + c + '.csv'),
      [r.appName + '-aligned-debugSigned.apk'], codes.map(c => r.appName + '-' + c + '-aligned-debugSigned.apk'));
    for (const c of codes) {
      const folder = resultsFolder(c);
      if (!folder) continue;
      usual.push(folder + '/' + c + '/result.json');
      usual.push(folder + '/' + c + (c === dflt ? '/exploration_script.json' : '/replay_report.json'));
    }
    const files = listing ? listing.files.slice() : usual.map(path => ({ path }));
    const used = new Set();
    const take = test => { const out = files.filter(f => !used.has(f.path) && test(f.path)); for (const f of out) used.add(f.path); return out; };
    const link = (f, label) => el('li', null, el('a', { href: resultsUrl(f.path), target: '_blank' }, basename(f.path)),
      label ? ' · ' + label : '', isNum(f.size) ? el('span', { class: 'text-muted small' }, ' · ' + fileSize(f.size)) : null);
    const section = (title, items, note) => {
      if (!items.length && !note) return;
      const ul = el('ul', { class: 'mb-0' }, ...items);
      root.appendChild(card(title, el('div', null, items.length ? ul : null, note ? el('p', { class: 'text-muted small mb-0' + (items.length ? ' mt-2' : '') }, note) : null)));
    };

    const reports = [];
    for (const f of take(p => !p.includes('/') && ARTIFACT_LABELS.some(([re]) => re.test(p)))) {
      const [re, label] = ARTIFACT_LABELS.find(([test]) => test.test(f.path));
      reports.push(link(f, label.replace('$1', (re.exec(f.path)[1] || ''))));
    }
    section('Reports', reports);

    const i18n = r.internationalizedApk;
    const apks = [];
    for (const f of take(p => !p.includes('/') && /\.apk$/i.test(p))) {
      const name = basename(f.path);
      let label = '';
      if (name === r.appName + '-aligned-debugSigned.apk') {
        label = i18n && i18n.built ? 'internationalized build: one APK for the source language and ' + (i18n.languages || []).join(', ') : 'default build (source language)';
      } else {
        const code = codes.find(c => name === r.appName + '-' + c + '-aligned-debugSigned.apk');
        if (code) label = code === dflt ? 'source build (' + code + ')' : 'translated build (' + code + ')';
        else if (name === r.appName + '.apk') label = 'last build as apktool left it, before signing';
      }
      apks.push(link(f, label));
    }
    // why an APK one would look for is not there
    const config = (r.environment || {}).config || {};
    const apkNotes = [];
    if (i18n && i18n.built === false) apkNotes.push('The internationalized APK could not be built' + (i18n.sourceBuild ? '; the source build is ' + i18n.sourceBuild : '') + '.');
    if (config.keepLanguageApks === false) apkNotes.push('The APKs of the target languages were removed once each language was evaluated (output.keepLanguageApks=false).');
    section('APKs built by ITDroid', apks, apkNotes.join(' ') || null);

    // Explorations: one line per language, in the folder that language really is in.
    const explorations = [];
    const order = (dflt ? [dflt] : []).concat(codes.filter(c => c !== dflt).sort());
    for (const c of order) {
      const folder = resultsFolder(c);
      const l = lr[c] || {};
      const head = el('span', null, badge(c, 'info'), (l.lang || c) + (c === dflt ? ' · source language' : (shippedByApp(c) ? ' · shipped by the app' : ' · translated by ITDroid')));
      const prefix = folder ? folder + '/' + c + '/' : null;
      const mine = prefix ? take(p => p.startsWith(prefix)) : [];
      if (!mine.length) {
        explorations.push(el('li', null, head, el('span', { class: 'text-muted' }, ' · no exploration' + (l.error ? ': ' + l.error : ' was written'))));
        continue;
      }
      const names = { 'result.json': 'screens and transitions', 'exploration_script.json': 'recorded path', 'replay_report.json': 'what the replay reproduced' };
      const shots = listing && listing.screenshots ? listing.screenshots[prefix + 'screenshots'] : null;
      explorations.push(el('li', null, head, el('span', { class: 'text-muted small' }, ' · ' + prefix + (isNum(shots) ? ' · ' + plural(shots, 'screenshot') : '')),
        el('ul', null, ...mine.map(f => link(f, names[basename(f.path)] || '')))));
    }
    section('Explorations', explorations, 'Languages the app already shipped are explored under trnsResults/, the ones ITDroid translated under noTrnsResults/.');

    const rest = take(() => true);
    section('Other files', rest.map(f => el('li', null, el('a', { href: resultsUrl(f.path), target: '_blank' }, f.path), isNum(f.size) ? el('span', { class: 'text-muted small' }, ' · ' + fileSize(f.size)) : null)),
      listing && listing.truncated ? 'The folder has more files than are listed here.' : null);
  }

  // ------------------------------------------------------------------ boot

  $('#filter').addEventListener('input', renderList);
  $('#reload').addEventListener('click', loadIndex);   // reads the folder again and reopens what the address names
  window.addEventListener('hashchange', () => {
    const link = readHash();
    if (link.dir && link.dir !== state.dir && state.apps.some(a => a.dir === link.dir)) {
      state.open = link.open;
      selectApp(link.dir, link.tab);
    }
  });
  loadIndex();
})();
