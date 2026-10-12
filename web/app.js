/* Heat Web: rooms, chat and file transfer between nearby phones.
 * No server. Phones pair by swapping two QR codes (a WebRTC handshake) and then talk directly.
 * The phone that created the room is the leader: it approves who joins and relays group traffic.
 */
'use strict';
(() => {
  // Where the app is published. Used in QR codes made inside the Android app, so a
  // phone's own Camera app can open Heat in Safari. Change it if you host Heat elsewhere.
  const PUBLIC_URL = 'https://blankbook101.github.io/Heat/';
  const AND = window.HeatAndroid || null; // set by the Android app's WebView

  const CH = 16 * 1024;            // bytes per data-channel message
  const WINDOW = 1024 * 1024;      // unacknowledged bytes a sender may have in flight
  const ACK_EVERY = 128 * 1024;
  const MAX_ROOM = 6;              // people in a room, leader included
  const PING_MS = 4000, DEAD_MS = 16000, OFFER_WAIT = 120000;
  const TERMS_KEY = 'terms_v2';

  // ------------------------------------------------------------------ helpers
  const $ = (s, r = document) => r.querySelector(s);
  const el = (tag, cls, html) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; };
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const rid = (n) => { const a = new Uint8Array(n / 2); crypto.getRandomValues(a); return [...a].map((b) => b.toString(16).padStart(2, '0')).join(''); };
  const pad2 = (n) => String(n).padStart(2, '0');
  const stampOf = (d) => `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}_${pad2(d.getHours())}-${pad2(d.getMinutes())}-${pad2(d.getSeconds())}`;
  const stampName = (name, ts) => `${stampOf(new Date(ts))}_${name}`;
  const clock = (ts) => { const d = new Date(ts); return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`; };

  const fmtBytes = (n) => {
    if (n < 1024) return `${n} B`;
    const u = ['KB', 'MB', 'GB']; let i = -1;
    do { n /= 1024; i++; } while (n >= 1024 && i < 2);
    return `${n >= 100 ? n.toFixed(0) : n.toFixed(1)} ${u[i]}`;
  };
  const fmtDur = (s) => {
    s = Math.max(0, Math.round(s));
    if (s < 60) return `${s} s`;
    const m = Math.floor(s / 60);
    return m < 60 ? `${m} min ${s % 60} s` : `${Math.floor(m / 60)} h ${m % 60} min`;
  };
  const fmtSpeed = (b) => `${fmtBytes(b)}/s`;
  const safeName = (s) => String(s || 'file').replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').trim().slice(0, 120) || 'file';
  const cleanName = (s) => String(s || '').replace(/[\u0000-\u001f]/g, '').trim().slice(0, 16) || 'Someone';
  const okAv = (s) => (typeof s === 'string' && s.length < 12000 && /^data:image\/jpeg;base64,[A-Za-z0-9+/=]+$/.test(s)) ? s : '';
  const b64 = (u8) => { let s = ''; for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000)); return btoa(s); };

  const mem = {};
  const store = {
    get(k, d = null) {
      try { const v = localStorage.getItem('heat_' + k); if (v !== null) return JSON.parse(v); } catch (_) { /* ignore */ }
      return k in mem ? mem[k] : d;
    },
    set(k, v) { mem[k] = v; try { localStorage.setItem('heat_' + k, JSON.stringify(v)); } catch (_) { /* ignore */ } }
  };

  // ------------------------------------------------------------------ me
  const qp = new URLSearchParams(location.search);
  if (!store.get('id')) store.set('id', rid(8));
  if (!store.get('name')) {
    store.set('name', (qp.get('name') || '').trim().slice(0, 16) || `Heat-${100 + Math.floor(Math.random() * 900)}`);
  }
  if (qp.get('egg') === '1' && !store.get('egg')) store.set('egg', true);
  if (AND) { // inside the Android app, the app's own profile is the source of truth
    const nm = (qp.get('name') || '').replace(/[\u0000-\u001f]/g, '').trim().slice(0, 16);
    if (nm) store.set('name', nm);
    const egg = qp.get('egg') === '1';
    store.set('egg', egg);
    store.set('avmode', egg && qp.get('mode') === '2' ? 2 : 0);
    store.set('photo', '');
  }
  const me = {
    id: store.get('id'),
    get name() { return store.get('name'); }, set name(v) { store.set('name', v); },
    get egg() { return !!store.get('egg'); }, set egg(v) { store.set('egg', !!v); },
    get avMode() { return store.get('avmode', 0); }, set avMode(v) { store.set('avmode', v); },
    get photo() { return store.get('photo', ''); }, set photo(v) { store.set('photo', v); }
  };
  const meAsPeer = () => ({ id: me.id, name: me.name, gold: me.egg, mode: me.avMode, av: me.photo });

  const avatarHTML = (p, size) => {
    const st = `width:${size}px;height:${size}px;font-size:${Math.round(size * 0.42)}px`;
    if (p.mode === 2 && p.gold) return `<span class="av" style="${st}"><img src="assets/egg.webp" alt=""></span>`;
    if (p.mode === 1 && p.av) return `<span class="av" style="${st}"><img src="${esc(p.av)}" alt=""></span>`;
    return `<span class="av" style="${st}">${esc((p.name || '?').slice(0, 1).toUpperCase())}</span>`;
  };
  const nameHTML = (p) => (p.gold ? `<span class="gold">${esc(p.name)}</span>` : esc(p.name));

  // ------------------------------------------------------------------ toasts and dialogs
  function toast(text, bad) {
    const t = el('div', 'toast' + (bad ? ' bad' : ''));
    t.textContent = text;
    $('#toasts').append(t);
    setTimeout(() => t.remove(), 3600);
  }
  function buzz(ms = 40) { try { navigator.vibrate && navigator.vibrate(ms); } catch (_) { /* ignore */ } }

  let dlgChain = Promise.resolve();
  let dlgResolve = null;
  function dialog(o) {
    const run = () => new Promise((res) => {
      const box = $('#dlg');
      $('#dlgTitle').textContent = o.title || '';
      $('#dlgBody').textContent = o.body || '';
      $('#dlgBody').hidden = !o.body;
      const ex = $('#dlgExtra'); ex.innerHTML = '';
      if (o.extra) ex.append(o.extra);
      const btns = $('#dlgBtns'); btns.innerHTML = '';
      const done = (v) => { box.hidden = true; dlgResolve = null; res(v); };
      dlgResolve = done;
      (o.buttons || [{ label: 'OK', value: true, cls: 'pri' }]).forEach((b) => {
        const x = el('button', 'sm ' + (b.cls || ''));
        x.type = 'button'; x.textContent = b.label;
        x.onclick = () => done(b.value);
        btns.append(x);
      });
      box.hidden = false;
    });
    const p = dlgChain.then(run);
    dlgChain = p.catch(() => {});
    return p;
  }
  const closeDialog = (v) => { if (dlgResolve) dlgResolve(v); };

  // ------------------------------------------------------------------ screens, theme
  let current = null;
  function show(id) {
    for (const s of document.querySelectorAll('.screen')) s.hidden = s.id !== id;
    current = id;
    if (id === 's-home') renderHome();
  }
  function applyTheme() {
    const t = store.get('theme', 'auto');
    const root = document.documentElement;
    if (t === 'auto') root.removeAttribute('data-theme'); else root.setAttribute('data-theme', t);
    $('#themeBtn').textContent = t[0].toUpperCase() + t.slice(1);
    const dark = t === 'dark' || (t === 'auto' && matchMedia('(prefers-color-scheme: dark)').matches);
    $('meta[name=theme-color]').setAttribute('content', dark ? '#070d16' : '#f2f8fb');
  }
  const greeting = () => {
    const h = new Date().getHours();
    return h < 5 ? 'Good night' : h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : h < 21 ? 'Good evening' : 'Good night';
  };
  function renderHome() {
    const p = meAsPeer();
    $('#greet').textContent = `${greeting()},`;
    $('#meName').innerHTML = nameHTML(p);
    $('#meAv').innerHTML = avatarHTML(p, 40);
  }

  // ------------------------------------------------------------------ terms
  const TERMS = [
    ['Acceptance', 'By tapping "I agree" and using Heat, you accept these terms. If you don\'t agree, don\'t use the app.'],
    ['Age', 'You must be 18 or older, or have a parent or guardian\'s permission.'],
    ['As is', 'Heat is an experimental app provided "as is" and "as available". It was built quickly with AI assistance and may contain bugs, fail mid-transfer, lose or corrupt files, or stop working on your device. We make no promise that it is secure, reliable or fit for any purpose.'],
    ['Your responsibility', 'You alone are responsible for how you use Heat, what you send, what you accept, and what happens on your device. Only accept files from people you trust, because files from strangers can be harmful.'],
    ['Acceptable use', 'Don\'t use Heat for anything illegal, to harass anyone, to spread malware, or to share content you have no right to share. Anyone who misuses Heat or its code does so entirely at their own risk and liability.'],
    ['No liability', 'To the fullest extent the law allows, the developer is not liable for any loss, damage, claim or cost arising from your use or misuse of Heat, including lost data, device damage, third-party actions, or content sent or received. This covers direct, indirect and consequential losses.'],
    ['Indemnity', 'If your use of Heat leads to a claim against the developer, you agree to cover the resulting costs.'],
    ['Your name and photo are visible', 'Your username and profile picture (if you set one) are shown to everyone in a room you join or create. Don\'t use a name or picture you wouldn\'t want them to see.'],
    ['Rooms', 'Anyone you let into a room can send you files, and files you send to a room reach every member. The room leader\'s phone relays traffic between members. Only join rooms of people you trust. Room leaders are responsible for who they allow in.'],
    ['Privacy', 'Heat has no accounts and the developer collects no data. Phones connect directly to each other, with no Heat server in between. Messages stay in memory and are not stored. Files you receive stay in your browser\'s memory until you save them or close Heat.'],
    ['Changes', 'These terms may change. Continued use means you accept the updated version.'],
    ['Law', 'These terms are governed by the laws of India.'],
    ['Contact', 'lemonad143@gmail.com']
  ];
  function termsHTML() {
    return TERMS.map(([t, b], i) => `<div><h4>${i + 1}. ${esc(t)}</h4><p>${esc(b)}</p></div>`).join('');
  }

  // ------------------------------------------------------------------ pairing codec
  // A WebRTC handshake is squeezed into one short string so it fits in a QR code:
  //   HEAT1~<o|a>~<ice ufrag>~<ice pwd>~<dtls fingerprint hex>~<ip,port|ip,port...>
  const ICE = { iceServers: [] }; // no STUN: everything stays on the local network

  function pack(sdp, role) {
    const g = (re) => (sdp.match(re) || [])[1];
    const u = g(/a=ice-ufrag:(\S+)/), p = g(/a=ice-pwd:(\S+)/);
    const fp = (g(/a=fingerprint:sha-256 (\S+)/) || '').replace(/:/g, '').toLowerCase();
    const cands = [...new Set([...sdp.matchAll(/a=candidate:\S+ 1 udp \d+ (\S+) (\d+) typ host/g)].map((m) => `${m[1]},${m[2]}`))].slice(0, 4);
    if (!u || !p || fp.length !== 64) throw new Error('This browser could not make a code.');
    if (!cands.length) throw new Error('No network found. Connect to Wi-Fi or a hotspot first.');
    return ['HEAT1', role, u, p, fp, cands.join('|')].join('~');
  }
  function parseCode(code) {
    const [m, role, u, p, fp, c] = String(code).trim().split('~');
    if (m !== 'HEAT1' || !u || !p || !/^[0-9a-f]{64}$/.test(fp || '') || !c) throw new Error('That isn\'t a Heat code.');
    return { role, u, p, fp, c: c.split('|') };
  }
  function unpack(code) {
    const { role, u, p, fp, c } = parseCode(code);
    const fpf = fp.toUpperCase().match(/../g).join(':');
    const lines = [
      'v=0', 'o=- 4611731400430051336 2 IN IP4 127.0.0.1', 's=-', 't=0 0',
      'a=group:BUNDLE 0', 'a=msid-semantic: WMS',
      'm=application 9 UDP/DTLS/SCTP webrtc-datachannel', 'c=IN IP4 0.0.0.0',
      `a=ice-ufrag:${u}`, `a=ice-pwd:${p}`, `a=fingerprint:sha-256 ${fpf}`,
      `a=setup:${role === 'o' ? 'actpass' : 'active'}`, 'a=mid:0', 'a=sctp-port:5000', 'a=max-message-size:262144'
    ];
    c.forEach((x, i) => {
      const [ip, port] = x.split(',');
      lines.push(`a=candidate:${i + 1} 1 udp ${2113937151 - i} ${ip} ${port} typ host generation 0`);
    });
    lines.push('a=end-of-candidates');
    return lines.join('\r\n') + '\r\n';
  }
  // Accept a bare code or a link that carries one (https://.../#HEAT1~...).
  function extractCode(text) {
    let t = String(text || '');
    try { t = decodeURIComponent(t); } catch (_) { /* keep raw */ }
    const i = t.indexOf('HEAT1~');
    return i < 0 ? null : t.slice(i).split(/\s/)[0];
  }
  const payload = (code) => {
    const base = AND ? PUBLIC_URL : (/^https?:/.test(location.protocol) ? location.origin + location.pathname : PUBLIC_URL);
    return `${base}#${code}`;
  };

  async function gather(pc, ms = 2500) {
    if (pc.iceGatheringState === 'complete') return;
    await new Promise((res) => {
      const t = setTimeout(done, ms);
      function done() { clearTimeout(t); pc.removeEventListener('icegatheringstatechange', on); res(); }
      function on() { if (pc.iceGatheringState === 'complete') done(); }
      pc.addEventListener('icegatheringstatechange', on);
    });
  }

  // ------------------------------------------------------------------ QR show and scan
  function qrSVG(text) {
    const q = qrcode(0, 'L'); q.addData(text, 'Byte'); q.make();
    const n = q.getModuleCount(), m = 4; let d = '';
    for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (q.isDark(r, c)) d += `M${c + m} ${r + m}h1v1h-1z`;
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${n + 2 * m} ${n + 2 * m}" shape-rendering="crispEdges"><rect width="100%" height="100%" fill="#fff"/><path d="${d}" fill="#000"/></svg>`;
  }
  async function startScan(video, onText) {
    const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 } }, audio: false });
    video.srcObject = stream; video.setAttribute('playsinline', ''); video.muted = true;
    await video.play();
    const c = document.createElement('canvas'), x = c.getContext('2d', { willReadFrequently: true });
    let stop = false;
    const end = () => { stop = true; stream.getTracks().forEach((t) => t.stop()); video.srcObject = null; };
    (function tick() {
      if (stop) return;
      if (video.videoWidth) {
        const k = Math.min(1, 960 / Math.max(video.videoWidth, video.videoHeight));
        c.width = Math.round(video.videoWidth * k); c.height = Math.round(video.videoHeight * k);
        x.drawImage(video, 0, 0, c.width, c.height);
        const img = x.getImageData(0, 0, c.width, c.height);
        const r = jsQR(img.data, c.width, c.height, { inversionAttempts: 'dontInvert' });
        if (r && extractCode(r.data)) { end(); onText(r.data); return; }
      }
      setTimeout(() => requestAnimationFrame(tick), 90);
    })();
    return end;
  }
  async function copyText(t) {
    try { await navigator.clipboard.writeText(t); toast('Link copied'); return; } catch (_) { /* fall through */ }
    const ta = el('textarea'); ta.value = t; document.body.append(ta); ta.select();
    try { document.execCommand('copy'); toast('Link copied'); } catch (_) { toast('Couldn\'t copy', true); }
    ta.remove();
  }

  // ------------------------------------------------------------------ wolf loader
  const WOLF = `<svg class="wolf" viewBox="0 0 230 130" role="img" aria-label="Loading">
<defs>
<linearGradient id="wg" x1="0" x2="1"><stop offset="0" stop-color="#3ee6c4"/><stop offset=".55" stop-color="#8a7bff"/><stop offset="1" stop-color="#ff7ad9"/></linearGradient>
<linearGradient id="rg1" x1="0" x2="1"><stop offset="0" stop-color="#3ee6c4" stop-opacity="0"/><stop offset=".5" stop-color="#3ee6c4"/><stop offset="1" stop-color="#8a7bff" stop-opacity="0"/></linearGradient>
<linearGradient id="rg2" x1="0" x2="1"><stop offset="0" stop-color="#ff7ad9" stop-opacity="0"/><stop offset=".5" stop-color="#8a7bff"/><stop offset="1" stop-color="#3ee6c4" stop-opacity="0"/></linearGradient>
<filter id="gl" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="4"/></filter>
</defs>
<g class="rib1"><path d="M0 28C50 6 90 50 140 24S210 10 230 26" fill="none" stroke="url(#rg1)" stroke-width="9" stroke-linecap="round" filter="url(#gl)"/></g>
<g class="rib2"><path d="M0 46C60 24 100 68 150 40S210 30 230 44" fill="none" stroke="url(#rg2)" stroke-width="6" stroke-linecap="round" filter="url(#gl)"/></g>
<ellipse cx="112" cy="122" rx="70" ry="4" fill="#000" opacity=".18"/>
<g class="bob">
<path class="tail" d="M70 58C46 62 30 50 22 28 38 40 52 44 72 50Z" fill="url(#wg)"/>
<path class="legD" d="M76 74L70 96 62 112" stroke="url(#wg)" stroke-width="8" fill="none" stroke-linecap="round" stroke-linejoin="round" opacity=".6"/>
<path class="legB" d="M128 74L134 96 142 112" stroke="url(#wg)" stroke-width="8" fill="none" stroke-linecap="round" stroke-linejoin="round" opacity=".6"/>
<ellipse cx="102" cy="64" rx="44" ry="18" fill="url(#wg)"/>
<path d="M132 58L152 40 168 44 188 58 170 66 150 74Z" fill="url(#wg)"/>
<path d="M150 42L153 22 163 40Z" fill="url(#wg)"/><path d="M160 42L169 25 173 46Z" fill="url(#wg)"/>
<circle cx="165" cy="52" r="2.4" fill="#06121a"/>
<path class="legC" d="M76 74L82 96 74 112" stroke="url(#wg)" stroke-width="8" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
<path class="legA" d="M128 74L122 96 128 112" stroke="url(#wg)" stroke-width="8" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
</g></svg>`;
  let customWolf = '';
  { const im = new Image(); im.onload = () => { customWolf = im.src; }; im.src = 'assets/wolf.webp'; } // drop in your own art
  const wolfMarkup = () => (customWolf ? `<img class="wolfimg" src="${customWolf}" alt="Loading">` : WOLF);

  // ------------------------------------------------------------------ room state
  const room = { role: null, conns: new Map(), peers: new Map(), leaderId: null, log: [] };
  const pending = new Set();      // connections still pairing (no hello yet)
  const routes = new Map();       // leader only: transfer id -> { a, b } connections
  const xfers = new Map();        // transfer id -> record
  const received = [];            // files received this session
  const batchOK = new Set();
  const shownChips = new Set();
  let unread = 0;
  let tp = store.get('tp', 4e6);  // learned throughput, bytes per second

  const peerOf = (id) => room.peers.get(id) || (id === me.id ? meAsPeer() : { id, name: 'Someone', gold: false });
  const others = () => [...room.peers.values()].filter((p) => p.id !== me.id);
  const sendJSON = (conn, o) => {
    if (conn && conn.dc && conn.dc.readyState === 'open') { try { conn.dc.send(JSON.stringify(o)); return true; } catch (_) { /* closing */ } }
    return false;
  };
  const connFor = (peerId) => (room.role === 'leader' ? room.conns.get(peerId) : room.conns.values().next().value);

  function resetRoom() {
    for (const c of [...room.conns.values(), ...pending]) { try { c.pc.close(); } catch (_) { /* ignore */ } }
    room.role = null; room.conns.clear(); room.peers.clear(); room.leaderId = null; room.log = [];
    pending.clear(); routes.clear(); shownChips.clear();
    for (const t of xfers.values()) if (['waiting', 'sending', 'receiving', 'prompt'].includes(t.status)) { t.status = 'failed'; t.msg = 'Room closed'; if (t.onAccept) t.onAccept(false); if (t.wake) t.wake(); }
    for (const t of queueOffers.splice(0)) t.status = 'failed';
    closeDialog(false);
    unread = 0; document.title = 'Heat';
  }
  function leaveRoom() { resetRoom(); closePair(); show('s-home'); }

  // ------------------------------------------------------------------ connections
  function newPc() { return new RTCPeerConnection(ICE); }

  function wireConn(conn) {
    const dc = conn.dc;
    dc.binaryType = 'arraybuffer';
    dc.bufferedAmountLowThreshold = 256 * 1024;
    dc.onopen = () => onOpen(conn);
    dc.onmessage = (e) => onData(conn, e.data);
    dc.onclose = () => onGone(conn);
    dc.onerror = () => {};
    conn.pc.onconnectionstatechange = () => { const s = conn.pc.connectionState; if (s === 'failed' || s === 'closed') onGone(conn); };
    if (dc.readyState === 'open') onOpen(conn);
  }
  function sendHello(conn) {
    sendJSON(conn, { t: 'hello', id: me.id, name: me.name, gold: me.egg, mode: me.avMode, av: me.avMode === 1 ? me.photo : '', v: 1 });
  }
  // The first message after a channel opens can be lost, so keep sending hello until the
  // other side has said hello back.
  function onOpen(conn) {
    if (conn.opened) return;
    conn.opened = true; conn.last = Date.now();
    sendHello(conn);
    let n = 0;
    conn.helloTimer = setInterval(() => {
      if (conn.id || conn.gone || ++n > 24) { clearInterval(conn.helloTimer); return; }
      sendHello(conn);
    }, 500);
  }

  // Leader: make an offer for one new person.
  async function hostOffer() {
    const pc = newPc();
    const dc = pc.createDataChannel('heat');
    const conn = { pc, dc, host: true, id: null };
    pending.add(conn); wireConn(conn);
    await pc.setLocalDescription(await pc.createOffer());
    await gather(pc);
    return { conn, code: pack(pc.localDescription.sdp, 'o') };
  }
  async function acceptAnswer(conn, code) {
    if (parseCode(code).role !== 'a') throw new Error('That is a host code. Scan the reply code on their screen.');
    await conn.pc.setRemoteDescription({ type: 'answer', sdp: unpack(code) });
  }
  // Member: answer a leader's offer.
  async function joinAnswer(code) {
    if (parseCode(code).role !== 'o') throw new Error('That is a reply code. Scan the host\'s code instead.');
    if (room.role === 'member') throw new Error('Leave your current room first.');
    if (room.role === 'leader') { if (room.conns.size) throw new Error('Leave your room first.'); resetRoom(); }
    const pc = newPc();
    const conn = { pc, dc: null, host: false, id: null };
    pending.add(conn);
    pc.ondatachannel = (e) => { conn.dc = e.channel; wireConn(conn); };
    pc.onconnectionstatechange = () => { const s = pc.connectionState; if (s === 'failed' || s === 'closed') onGone(conn); };
    await pc.setRemoteDescription({ type: 'offer', sdp: unpack(code) });
    await pc.setLocalDescription(await pc.createAnswer());
    await gather(pc);
    return { conn, code: pack(pc.localDescription.sdp, 'a') };
  }

  function onData(conn, data) {
    conn.last = Date.now();
    if (typeof data !== 'string') return onBinary(conn, data);
    let m; try { m = JSON.parse(data); } catch (_) { return; }
    switch (m.t) {
      case 'hello': return onHello(conn, m);
      case 'members': return onMembers(m);
      case 'profile': return onProfile(conn, m);
      case 'msg': return onMsg(conn, m);
      case 'full': toast('That room is full', true); try { conn.pc.close(); } catch (_) { /* ignore */ } return;
      case 'ping': return;
      case 'offer': return onOfferFrame(conn, m);
      default: return onControl(conn, m);
    }
  }

  function onHello(conn, m) {
    if (conn.id) { sendHello(conn); return; } // duplicate: they may have missed ours
    const p = { id: String(m.id), name: cleanName(m.name), gold: !!m.gold, mode: m.mode | 0, av: okAv(m.av) };
    conn.id = p.id;
    if (conn.host) { // I am the leader
      room.role = 'leader';
      if (room.conns.size + 1 >= MAX_ROOM) { sendJSON(conn, { t: 'full' }); setTimeout(() => { try { conn.pc.close(); } catch (_) { /* ignore */ } }, 400); return; }
      const old = room.conns.get(p.id);
      if (old && old !== conn) { try { old.pc.close(); } catch (_) { /* ignore */ } }
      pending.delete(conn);
      room.conns.set(p.id, conn); room.peers.set(p.id, p);
      broadcastMembers();
      logSys(`${p.name} joined`);
    } else { // I am joining their room
      room.role = 'member'; room.leaderId = p.id;
      pending.delete(conn);
      room.conns.set(p.id, conn); room.peers.set(p.id, p);
      enterRoom();
      logSys(`You joined ${p.name}'s room`);
    }
    keepAwake(); buzz();
    renderPeople(); roomHeader();
    if (pairUI.ctx && pairUI.ctx.conn === conn) { closePair(); toast(conn.host ? `${p.name} joined` : `Joined ${p.name}'s room`); }
  }
  function broadcastMembers() {
    const list = [meAsPeer(), ...room.peers.values()].map((p) => ({ id: p.id, name: p.name, gold: p.gold, mode: p.mode, av: p.av }));
    for (const c of room.conns.values()) sendJSON(c, { t: 'members', list });
    renderPeople(); roomHeader();
  }
  function onMembers(m) {
    if (room.role !== 'member') return;
    const before = new Set(room.peers.keys());
    room.peers.clear();
    for (const x of m.list || []) {
      if (x.id === me.id) continue;
      room.peers.set(String(x.id), { id: String(x.id), name: cleanName(x.name), gold: !!x.gold, mode: x.mode | 0, av: okAv(x.av) });
    }
    for (const p of room.peers.values()) if (!before.has(p.id)) logSys(`${p.name} is here`);
    renderPeople(); roomHeader();
  }
  function onProfile(conn, m) {
    if (room.role !== 'leader' || !conn.id || !room.peers.has(conn.id)) return;
    room.peers.set(conn.id, { id: conn.id, name: cleanName(m.name), gold: !!m.gold, mode: m.mode | 0, av: okAv(m.av) });
    broadcastMembers();
  }
  function sendProfile() {
    if (room.role === 'leader') broadcastMembers();
    else if (room.role === 'member') for (const c of room.conns.values()) sendJSON(c, { t: 'profile', name: me.name, gold: me.egg, mode: me.avMode, av: me.avMode === 1 ? me.photo : '' });
  }

  function onGone(conn) {
    if (conn.gone) return;
    conn.gone = true; clearInterval(conn.helloTimer); pending.delete(conn);
    try { conn.pc.close(); } catch (_) { /* ignore */ }
    for (const [fid, r] of routes) if (r.a === conn || r.b === conn) { sendJSON(r.a === conn ? r.b : r.a, { t: 'cancel', fid }); routes.delete(fid); }
    for (const t of xfers.values()) if (t.conn === conn && ['waiting', 'sending', 'receiving', 'prompt'].includes(t.status)) {
      t.status = 'failed'; t.msg = 'Connection lost'; t.parts = [];
      if (t.bridge && AND) AND.abortSave(t.bridge);
      if (t.onAccept) t.onAccept(false); if (t.wake) t.wake();
      if (t.dir === 'in') closeDialog(false);
    }
    if (conn.id && room.role === 'leader' && room.conns.get(conn.id) === conn) {
      const p = room.peers.get(conn.id);
      room.conns.delete(conn.id); room.peers.delete(conn.id);
      logSys(`${p ? p.name : 'Someone'} left`);
      broadcastMembers();
    } else if (room.role === 'member' && room.conns.get(room.leaderId) === conn) {
      toast('The room was closed', true);
      resetRoom(); closePair(); show('s-home');
    }
    scheduleRender();
  }
  setInterval(() => {
    for (const c of [...room.conns.values(), ...pending]) {
      sendJSON(c, { t: 'ping' });
      if (c.id && Date.now() - c.last > DEAD_MS) onGone(c);
    }
  }, PING_MS);

  let wl = null;
  async function keepAwake() {
    try { if (navigator.wakeLock && !wl) { wl = await navigator.wakeLock.request('screen'); wl.addEventListener('release', () => { wl = null; }); } } catch (_) { /* optional */ }
  }
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) { unread = 0; document.title = 'Heat'; if (room.role) keepAwake(); }
  });

  // ------------------------------------------------------------------ chat
  function logMsg(e) {
    e.ts = e.ts || Date.now(); room.log.push(e); appendLog(e);
  }
  const logSys = (text) => logMsg({ kind: 'sys', text });
  function appendLog(e) {
    const box = $('#log');
    const near = box.scrollHeight - box.scrollTop - box.clientHeight < 80;
    let n;
    if (e.kind === 'sys') { n = el('div', 'sys'); n.textContent = e.text; }
    else {
      n = el('div', 'msg' + (e.kind === 'me' ? ' me' : ''));
      if (e.kind === 'them') { const f = el('span', 'from', nameHTML(peerOf(e.from))); n.append(f); }
      const t = el('span'); t.textContent = e.text; n.append(t);
      n.append(el('time', '', clock(e.ts)));
    }
    box.append(n);
    if (near || e.kind === 'me') box.scrollTop = box.scrollHeight;
  }
  function sendChat(text) {
    text = text.trim().slice(0, 2000);
    if (!text) return;
    if (!room.conns.size) { toast('No one else is in the room yet', true); return; }
    if (room.role === 'leader') for (const c of room.conns.values()) sendJSON(c, { t: 'msg', from: me.id, text });
    else for (const c of room.conns.values()) sendJSON(c, { t: 'msg', text });
    logMsg({ kind: 'me', text });
  }
  function onMsg(conn, m) {
    const from = room.role === 'leader' ? conn.id : String(m.from);
    const text = String(m.text || '').slice(0, 2000);
    if (!from || !text) return;
    if (room.role === 'leader') for (const c of room.conns.values()) if (c !== conn) sendJSON(c, { t: 'msg', from, text });
    logMsg({ kind: 'them', from, text });
    const p = peerOf(from);
    if (document.hidden) {
      unread++; document.title = `(${unread}) Heat`; buzz(80);
      try { if ('Notification' in window && Notification.permission === 'granted') new Notification(p.name, { body: text, icon: 'assets/icon-192.png' }); } catch (_) { /* ignore */ }
    }
  }

  // ------------------------------------------------------------------ file transfer
  const queueOffers = [];
  let prompting = false;
  let renderTimer = 0;
  const cards = new Map();
  const ACTIVE = ['waiting', 'sending', 'receiving', 'prompt'];
  const estimate = (bytes, n = 1) => (bytes * n) / Math.max(tp, 2e5);

  function newXfer(o) {
    const t = Object.assign({ status: 'waiting', done: 0, speed: 0, started: 0, lastT: 0, lastB: 0, parts: [], sent: 0, acked: 0, recv: 0, lastAck: 0, ts: Date.now() }, o);
    xfers.set(t.fid, t); scheduleRender(); return t;
  }
  function progress(t, bytes) {
    const now = performance.now();
    if (!t.started) { t.started = now; t.lastT = now; t.lastB = bytes; }
    t.done = bytes;
    if (now - t.lastT > 400) {
      const inst = (bytes - t.lastB) / ((now - t.lastT) / 1000);
      t.speed = t.speed ? 0.6 * t.speed + 0.4 * inst : inst;
      t.lastT = now; t.lastB = bytes;
    }
    scheduleRender();
  }
  const etaOf = (t) => (t.speed > 0 ? (t.size - t.done) / t.speed : -1);

  async function sendFiles(files, targets) {
    await Promise.all(targets.map((id) => sendBatch(id, files)));
  }
  async function sendBatch(peerId, files) {
    const batch = rid(8), total = files.reduce((a, f) => a + f.size, 0);
    for (let i = 0; i < files.length; i++) {
      const f = files[i];
      const t = newXfer({ fid: (crypto.getRandomValues(new Uint32Array(1))[0] || 1), dir: 'out', peerId, name: safeName(f.name), size: f.size, mime: f.type || '', file: f, batch, bi: i, bn: files.length, bt: total });
      try { await runSend(t); } catch (e) { if (ACTIVE.includes(t.status)) { t.status = 'failed'; t.msg = e && e.message ? e.message : 'Failed'; } }
      t.file = null; scheduleRender();
      if (t.status !== 'done') {
        if (t.status === 'failed') buzz(120);
        break;
      }
    }
  }
  async function runSend(t) {
    const conn = connFor(t.peerId);
    if (!conn) throw new Error('Not connected');
    t.conn = conn;
    const answer = new Promise((res) => { t.onAccept = res; });
    sendJSON(conn, { t: 'offer', fid: t.fid, to: t.peerId, from: me.id, name: t.name, size: t.size, mime: t.mime, batch: t.batch, bi: t.bi, bn: t.bn, bt: t.bt });
    const ok = await Promise.race([answer, sleep(OFFER_WAIT).then(() => 'timeout')]);
    if (ok !== true) { if (ok === 'timeout') { t.status = 'failed'; t.msg = 'No answer'; } return; }
    t.status = 'sending';
    const dc = conn.dc, BLOCK = 256 * 1024;
    const hdr = new Uint8Array(5); hdr[0] = 1; new DataView(hdr.buffer).setUint32(1, t.fid);
    for (let off = 0; off < t.size;) {
      const blk = new Uint8Array(await t.file.slice(off, Math.min(off + BLOCK, t.size)).arrayBuffer());
      for (let p = 0; p < blk.length; p += CH) {
        while (t.status === 'sending' && (t.sent - t.acked > WINDOW || dc.bufferedAmount > 1024 * 1024)) {
          await new Promise((r) => { t.wake = r; setTimeout(r, 150); });
          if (dc.readyState !== 'open') throw new Error('Connection lost');
        }
        if (t.status !== 'sending') return;
        const part = blk.subarray(p, Math.min(p + CH, blk.length));
        const msg = new Uint8Array(5 + part.length); msg.set(hdr); msg.set(part, 5);
        dc.send(msg); t.sent += part.length;
      }
      off += blk.length;
    }
    const fin = new Promise((res) => { t.onDone = res; });
    sendJSON(conn, { t: 'end', fid: t.fid });
    await Promise.race([fin, sleep(60000)]);
    if (t.status === 'sending') { t.status = 'failed'; t.msg = 'No confirmation'; }
  }

  function onOfferFrame(conn, m) {
    if (m.to === me.id) return onOffer(conn, m);
    if (room.role !== 'leader') return;
    const dest = room.conns.get(m.to);
    if (!dest || !conn.id) { sendJSON(conn, { t: 'decline', fid: m.fid, why: 'left' }); return; }
    m.from = conn.id;
    routes.set(m.fid, { a: conn, b: dest });
    sendJSON(dest, m);
  }
  function onOffer(conn, m) {
    const from = room.role === 'leader' ? conn.id : String(m.from);
    const t = newXfer({ fid: m.fid, dir: 'in', peerId: from, name: safeName(m.name), size: Math.max(0, +m.size || 0), mime: String(m.mime || ''), batch: String(m.batch), bi: m.bi | 0, bn: m.bn | 0, bt: +m.bt || 0, conn, status: 'prompt' });
    if (batchOK.has(`${from}:${t.batch}`)) return acceptIn(t);
    queueOffers.push(t); pumpOffers();
  }
  async function pumpOffers() {
    if (prompting) return;
    prompting = true;
    while (queueOffers.length) {
      const t = queueOffers.shift();
      if (t.status !== 'prompt') continue;
      const p = peerOf(t.peerId), many = t.bn > 1, bytes = many ? t.bt : t.size;
      const ans = await dialog({
        title: `${p.name} wants to send ${many ? `${t.bn} files` : 'a file'}`,
        body: `${many ? `${t.bn} files, ` : ''}${fmtBytes(bytes)} · about ${fmtDur(estimate(bytes))}${many ? '' : `\n${t.name}`}`,
        buttons: [{ label: 'Decline', value: false }, { label: 'Accept', value: true, cls: 'pri' }]
      });
      if (t.status !== 'prompt') continue;
      if (ans) { batchOK.add(`${t.peerId}:${t.batch}`); acceptIn(t); } else declineIn(t);
    }
    prompting = false;
  }
  function acceptIn(t) {
    t.status = 'receiving';
    if (AND && AND.beginSave) { const id = AND.beginSave(stampName(t.name, t.ts), t.mime || ''); if (id) t.bridge = id; }
    sendJSON(t.conn, { t: 'accept', fid: t.fid });
    scheduleRender();
  }
  function declineIn(t) { t.status = 'declined'; sendJSON(t.conn, { t: 'decline', fid: t.fid }); scheduleRender(); }

  function onBinary(conn, buf) {
    if (buf.byteLength < 5) return;
    const dv = new DataView(buf);
    if (dv.getUint8(0) !== 1) return;
    const fid = dv.getUint32(1), t = xfers.get(fid);
    if (t) {
      if (t.dir !== 'in' || t.status !== 'receiving') return;
      const u = new Uint8Array(buf, 5);
      if (t.bridge) AND.writeChunk(t.bridge, b64(u)); else t.parts.push(u);
      t.recv += u.length;
      if (t.recv - t.lastAck >= ACK_EVERY) { t.lastAck = t.recv; sendJSON(t.conn, { t: 'ack', fid, n: t.recv }); }
      progress(t, t.recv);
    } else if (room.role === 'leader') {
      const r = routes.get(fid);
      if (r) { const dest = r.a === conn ? r.b : r.a; try { dest.dc.send(buf); } catch (_) { /* closing */ } }
    }
  }
  function onControl(conn, m) {
    const t = xfers.get(m.fid);
    if (t) {
      switch (m.t) {
        case 'accept': if (t.dir === 'out' && t.onAccept) t.onAccept(true); break;
        case 'decline': if (t.dir === 'out') { t.status = 'declined'; if (t.onAccept) t.onAccept(false); } break;
        case 'ack': if (t.dir === 'out') { t.acked = Math.max(t.acked, +m.n || 0); progress(t, t.acked); if (t.wake) t.wake(); } break;
        case 'done': if (t.dir === 'out' && t.status === 'sending') { t.status = 'done'; t.done = t.size; learn(t); if (t.onDone) t.onDone(); toast(`Sent ${t.name}`); buzz(); } break;
        case 'end': if (t.dir === 'in' && t.status === 'receiving') finishIn(t); break;
        case 'cancel':
          if (ACTIVE.includes(t.status)) {
            t.status = 'cancelled'; t.parts = [];
            if (t.bridge && AND) AND.abortSave(t.bridge);
            if (t.onAccept) t.onAccept(false); if (t.wake) t.wake(); if (t.onDone) t.onDone();
            if (t.dir === 'in') closeDialog(false);
          }
          break;
        default: break;
      }
      scheduleRender();
      return;
    }
    if (room.role === 'leader' && m.fid != null) {
      const r = routes.get(m.fid);
      if (r) {
        sendJSON(r.a === conn ? r.b : r.a, m);
        if (m.t === 'done' || m.t === 'decline' || m.t === 'cancel') routes.delete(m.fid);
      }
    }
  }
  function learn(t) {
    if (t.size < 256 * 1024 || !t.started) return;
    const sec = (performance.now() - t.started) / 1000;
    if (sec > 0.3) { tp = 0.7 * tp + 0.3 * (t.size / sec); store.set('tp', tp); }
  }
  function finishIn(t) {
    if (t.recv !== t.size) { t.status = 'failed'; t.msg = 'File was incomplete'; t.parts = []; if (t.bridge && AND) AND.abortSave(t.bridge); sendJSON(t.conn, { t: 'cancel', fid: t.fid }); return; }
    let saved = false;
    if (t.bridge) {
      saved = !!AND.endSave(t.bridge);
      if (!saved) { t.status = 'failed'; t.msg = 'Couldn\'t save the file'; sendJSON(t.conn, { t: 'cancel', fid: t.fid }); return; }
    } else { t.blob = new Blob(t.parts, { type: t.mime || 'application/octet-stream' }); }
    t.parts = [];
    t.status = 'done'; t.done = t.size;
    sendJSON(t.conn, { t: 'ack', fid: t.fid, n: t.size });
    sendJSON(t.conn, { t: 'done', fid: t.fid });
    received.unshift({ id: rid(6), name: t.name, size: t.size, from: peerOf(t.peerId).name, ts: Date.now(), blob: t.blob || null, saved, burned: false });
    toast(saved ? `${t.name} saved to Downloads/Heat` : `Received ${t.name}`); buzz(80);
    if (document.hidden) { try { if ('Notification' in window && Notification.permission === 'granted') new Notification('File received', { body: t.name, icon: 'assets/icon-192.png' }); } catch (_) { /* ignore */ } }
  }
  function cancelXfer(t) {
    if (!ACTIVE.includes(t.status)) return;
    t.status = 'cancelled';
    sendJSON(t.conn || connFor(t.peerId), { t: 'cancel', fid: t.fid });
    if (t.bridge && AND) AND.abortSave(t.bridge);
    t.parts = [];
    if (t.onAccept) t.onAccept(false); if (t.wake) t.wake();
    if (t.dir === 'in') closeDialog(false);
    scheduleRender();
  }

  async function saveItem(it) {
    if (it.saved) { toast('Already in Downloads/Heat'); return; }
    if (!it.blob) return;
    const name = stampName(it.name, it.ts);
    const file = new File([it.blob], name, { type: it.blob.type });
    try {
      if (navigator.canShare && navigator.canShare({ files: [file] })) { await navigator.share({ files: [file] }); return; }
    } catch (e) { if (e && e.name === 'AbortError') return; }
    const url = URL.createObjectURL(it.blob);
    const a = el('a'); a.href = url; a.download = name; document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
  }

  // ------------------------------------------------------------------ room UI
  function scheduleRender() { if (!renderTimer) renderTimer = setTimeout(() => { renderTimer = 0; renderXfers(); }, 200); }

  function renderXfers() {
    const box = $('#xfers');
    const list = [...xfers.values()];
    for (const [fid, c] of cards) if (!xfers.has(fid)) { c.root.remove(); cards.delete(fid); }
    box.hidden = !list.length;
    for (const t of list) {
      let c = cards.get(t.fid);
      if (!c) {
        const root = el('div', 'xf', '<div class="t"><span class="dir"></span><b></b><span class="s sz"></span></div><div class="bar-p"><i></i></div><div class="s st"></div><div class="b"></div>');
        c = { root, bar: $('i', root), st: $('.st', root), btns: $('.b', root), key: '' };
        $('b', root).textContent = t.name; $('.dir', root).textContent = t.dir === 'out' ? '↑' : '↓'; $('.sz', root).textContent = fmtBytes(t.size);
        cards.set(t.fid, c); box.prepend(root);
      }
      const p = peerOf(t.peerId), who = `${t.dir === 'out' ? 'To' : 'From'} ${p.name}`;
      const pct = t.size ? Math.min(100, (t.done / t.size) * 100) : (t.status === 'done' ? 100 : 0);
      c.bar.style.width = `${pct}%`;
      let s;
      switch (t.status) {
        case 'prompt': s = 'Waiting for your answer'; break;
        case 'waiting': s = `Waiting for ${p.name} to accept…`; break;
        case 'sending': case 'receiving': {
          const eta = etaOf(t);
          s = `${fmtBytes(t.done)} of ${fmtBytes(t.size)} · ${t.speed ? fmtSpeed(t.speed) : '…'} · ${eta >= 0 ? `${fmtDur(eta)} left` : 'working out time left…'}`;
          break;
        }
        case 'done': s = t.dir === 'in' ? (t.bridge ? 'Saved to Downloads/Heat' : 'Received') : 'Sent'; break;
        case 'declined': s = 'Declined'; break;
        case 'cancelled': s = 'Cancelled'; break;
        default: s = t.msg || 'Failed';
      }
      c.st.textContent = `${who} · ${s}`;
      const key = t.status + (t.blob ? 'b' : '');
      if (key !== c.key) {
        c.key = key; c.btns.innerHTML = '';
        if (ACTIVE.includes(t.status)) {
          const b = el('button', 'sm danger', 'Cancel'); b.type = 'button'; b.onclick = () => cancelXfer(t); c.btns.append(b);
        } else {
          if (t.status === 'done' && t.dir === 'in' && t.blob) {
            const it = received.find((r) => r.blob === t.blob);
            const sv = el('button', 'sm pri', 'Save'); sv.type = 'button'; sv.onclick = () => it && saveItem(it); c.btns.append(sv);
          }
          const d = el('button', 'sm', 'Dismiss'); d.type = 'button'; d.onclick = () => { xfers.delete(t.fid); renderXfers(); }; c.btns.append(d);
        }
      }
    }
  }

  function renderPeople() {
    const box = $('#people'); box.innerHTML = '';
    const list = [meAsPeer(), ...others()];
    list.forEach((p) => {
      const isNew = !shownChips.has(p.id); shownChips.add(p.id);
      const lead = (room.role === 'leader' && p.id === me.id) || p.id === room.leaderId;
      const c = el('div', 'chip' + (isNew ? ' drop' : ''), `${avatarHTML(p, 34)}<span class="n">${nameHTML(p)}${p.id === me.id ? ' (you)' : ''}</span>${lead ? '<span class="lead">leader</span>' : ''}`);
      box.append(c);
    });
  }
  function roomHeader() {
    const leader = room.role === 'leader' ? meAsPeer() : peerOf(room.leaderId);
    $('#roomTitle').textContent = room.role ? `${leader.name}'s room` : 'Room';
    const n = 1 + others().length;
    $('#roomSub').textContent = `${n} ${n === 1 ? 'person' : 'people'}`;
    $('#addBtn').hidden = room.role !== 'leader' || n >= MAX_ROOM;
  }
  function enterRoom() {
    shownChips.clear();
    $('#log').innerHTML = '';
    room.log.forEach(appendLog);
    renderPeople(); roomHeader(); renderXfers();
    show('s-room');
  }
  function startRoom() {
    resetRoom();
    room.role = 'leader';
    logSys('Waiting for people to join. Tap + Add to show a code.');
    enterRoom();
    openPair('host');
    askNotify();
  }
  function askNotify() { try { if ('Notification' in window && Notification.permission === 'default') Notification.requestPermission(); } catch (_) { /* ignore */ } }

  async function onPick(fileList) {
    const files = [...fileList];
    $('#filePick').value = '';
    if (!files.length) return;
    const ppl = others();
    if (!ppl.length) { toast('No one else is in the room yet', true); return; }
    const total = files.reduce((a, f) => a + f.size, 0);
    let sel = null, info = null;
    const extra = el('div');
    if (ppl.length > 1) {
      sel = el('select'); sel.style.cssText = 'width:100%;padding:10px;border-radius:12px;border:1px solid var(--line);background:var(--surface)';
      sel.append(new Option('Everyone', '*'));
      ppl.forEach((p) => sel.append(new Option(p.name, p.id)));
      extra.append(sel);
    }
    info = el('p', 'muted'); info.style.marginTop = '8px'; extra.append(info);
    const upd = () => {
      const n = !sel || sel.value === '*' ? ppl.length : 1;
      info.textContent = `${files.length} ${files.length === 1 ? 'file' : 'files'} · ${fmtBytes(total)} · about ${fmtDur(estimate(total, n))}`;
    };
    if (sel) sel.onchange = upd;
    upd();
    const ok = await dialog({
      title: ppl.length === 1 ? `Send to ${ppl[0].name}?` : 'Send files',
      body: files.length === 1 ? files[0].name : '', extra,
      buttons: [{ label: 'Cancel', value: false }, { label: 'Send', value: true, cls: 'pri' }]
    });
    if (!ok) return;
    const targets = !sel || sel.value === '*' ? ppl.map((p) => p.id) : [sel.value];
    sendFiles(files, targets);
  }

  // ------------------------------------------------------------------ pairing sheet
  const pairUI = { ctx: null };
  function stopScanner(ctx) { ctx.scanToken = null; if (ctx.stopScan) { ctx.stopScan(); ctx.stopScan = null; } }
  function setPair(o) {
    $('#pairTitle').textContent = o.title || 'Connect';
    $('#pairHint').textContent = o.hint || ''; $('#pairHint').hidden = !o.hint;
    const w = $('#pairWolf'); w.hidden = !o.wolf; w.innerHTML = o.wolf ? wolfMarkup() : '';
    const q = $('#pairQr'); q.hidden = !o.qr; q.innerHTML = ''; q.dataset.code = '';
    if (o.qr) { q.innerHTML = qrSVG(o.qr); q.dataset.code = o.qr; }
    $('#pairScan').hidden = !o.scan;
    const e = $('#pairErr'); e.hidden = !o.err; e.textContent = o.err || '';
    $('#pairPaste').hidden = !o.paste;
    const b = $('#pairBtns'); b.innerHTML = '';
    (o.buttons || []).forEach((x) => { const n = el('button', 'sm ' + (x.cls || '')); n.type = 'button'; n.textContent = x.label; n.onclick = x.fn; b.append(n); });
  }
  function closePair() {
    const c = pairUI.ctx;
    $('#pair').hidden = true;
    if (!c) return;
    c.dead = true; stopScanner(c);
    clearTimeout(c.timer);
    if (c.conn && !c.conn.id) { pending.delete(c.conn); try { c.conn.pc.close(); } catch (_) { /* ignore */ } }
    pairUI.ctx = null;
  }
  function openPair(mode, code) {
    closePair();
    const ctx = { mode, conn: null, stopScan: null, dead: false, timer: 0 };
    pairUI.ctx = ctx; $('#pair').hidden = false;
    if (mode === 'host') hostStep1(ctx);
    else if (code) joinWithCode(ctx, code);
    else joinStart(ctx);
  }
  const fail = (ctx, e, retry) => {
    if (ctx.dead) return;
    stopScanner(ctx);
    if (ctx.conn && !ctx.conn.id) { pending.delete(ctx.conn); try { ctx.conn.pc.close(); } catch (_) { /* ignore */ } ctx.conn = null; }
    setPair({ title: 'Something went wrong', err: (e && e.message) || String(e), buttons: [{ label: 'Try again', cls: 'pri', fn: retry }, { label: 'Close', fn: closePair }] });
  };
  const watchdog = (ctx, retry) => {
    clearTimeout(ctx.timer);
    ctx.timer = setTimeout(() => fail(ctx, new Error('Couldn\'t connect. Check that both phones are on the same Wi-Fi, or that one is on the other\'s hotspot.'), retry), 30000);
  };
  async function scanInto(ctx, onCode, back) {
    $('#pairPaste').hidden = true;
    stopScanner(ctx);
    const token = ctx.scanToken = {};
    try {
      const end = await startScan($('#scanVideo'), (t) => { if (ctx.scanToken === token) { ctx.scanToken = null; ctx.stopScan = null; onCode(t); } });
      if (ctx.dead || ctx.scanToken !== token) { end(); return; } // the view changed while the camera was starting
      ctx.stopScan = end;
    } catch (_) {
      if (ctx.dead || ctx.scanToken !== token) return;
      setPair({ title: 'Camera not available', err: 'Allow camera access, or paste the link instead.', paste: true, buttons: back ? [{ label: 'Back', fn: back }] : [] });
      $('#pasteGo').onclick = () => onCode($('#pasteBox').value);
    }
  }
  function pasteMode(ctx, onCode, back) {
    stopScanner(ctx);
    setPair({ title: 'Paste the link', hint: 'Paste the link you were sent.', paste: true, buttons: back ? [{ label: 'Back', fn: back }] : [] });
    $('#pasteBox').value = '';
    $('#pasteGo').onclick = () => onCode($('#pasteBox').value);
  }

  async function hostStep1(ctx) {
    setPair({ title: 'Add someone', hint: 'Getting your code ready…', wolf: true });
    try {
      const { conn, code } = await hostOffer();
      if (ctx.dead) { pending.delete(conn); try { conn.pc.close(); } catch (_) { /* ignore */ } return; }
      ctx.conn = conn; ctx.code = code;
      showHostQr(ctx);
    } catch (e) { fail(ctx, e, () => hostStep1(ctx)); }
  }
  function showHostQr(ctx) {
    stopScanner(ctx);
    setPair({
      title: 'Step 1 of 2', hint: 'Ask them to open Heat and tap Join a room, then scan this code.', qr: payload(ctx.code),
      buttons: [{ label: 'Next: scan their reply', cls: 'pri', fn: () => hostStep2(ctx) }, { label: 'Copy link', fn: () => copyText(payload(ctx.code)) }, { label: 'Close', fn: closePair }]
    });
  }
  function hostStep2(ctx) {
    setPair({ title: 'Step 2 of 2', hint: 'Point the camera at the code on their screen.', scan: true, buttons: [{ label: 'Paste link instead', fn: () => pasteMode(ctx, onReply, () => showHostQr(ctx)) }, { label: 'Back', fn: () => showHostQr(ctx) }] });
    scanInto(ctx, onReply, () => showHostQr(ctx));
    async function onReply(text) {
      try {
        const code = extractCode(text);
        if (!code) throw new Error('That isn\'t a Heat code.');
        stopScanner(ctx);
        await acceptAnswer(ctx.conn, code);
        setPair({ title: 'Connecting…', hint: 'Linking the two phones.', wolf: true });
        watchdog(ctx, () => hostStep1(ctx));
      } catch (e) {
        stopScanner(ctx);
        setPair({ title: 'Not quite', err: e.message, buttons: [{ label: 'Scan again', cls: 'pri', fn: () => hostStep2(ctx) }, { label: 'Back', fn: () => showHostQr(ctx) }] });
      }
    }
  }
  function joinStart(ctx) {
    setPair({ title: 'Join a room', hint: 'Scan the code on the host\'s screen.', scan: true, buttons: [{ label: 'Paste link instead', fn: () => pasteMode(ctx, (t) => joinWithCode(ctx, t), () => joinStart(ctx)) }, { label: 'Close', fn: closePair }] });
    scanInto(ctx, (t) => joinWithCode(ctx, t), () => joinStart(ctx));
  }
  async function joinWithCode(ctx, text) {
    try {
      const code = extractCode(text);
      if (!code) throw new Error('That isn\'t a Heat code.');
      stopScanner(ctx);
      $('#pair').hidden = false;
      setPair({ title: 'Joining…', hint: 'Making your reply code.', wolf: true });
      const { conn, code: reply } = await joinAnswer(code);
      if (ctx.dead) { pending.delete(conn); try { conn.pc.close(); } catch (_) { /* ignore */ } return; }
      ctx.conn = conn;
      setPair({
        title: 'Step 2 of 2', hint: 'Now let the host scan this code. This screen closes when you are connected.', qr: payload(reply),
        buttons: [{ label: 'Copy link', fn: () => copyText(payload(reply)) }, { label: 'Close', fn: closePair }]
      });
      watchdog(ctx, () => joinStart(ctx));
    } catch (e) {
      if (ctx.dead) return;
      setPair({ title: 'Couldn\'t join', err: e.message, buttons: [{ label: 'Scan again', cls: 'pri', fn: () => joinStart(ctx) }, { label: 'Close', fn: closePair }] });
    }
  }

  // ------------------------------------------------------------------ burn
  let burnTab = 'files';
  const burnSel = new Set();
  function renderBurn() {
    $('#tabFiles').classList.toggle('on', burnTab === 'files');
    $('#tabBurned').classList.toggle('on', burnTab === 'burned');
    const list = received.filter((r) => r.burned === (burnTab === 'burned'));
    const box = $('#burnList'); box.innerHTML = '';
    if (!list.length) box.append(el('div', 'empty', burnTab === 'files' ? 'Nothing here yet. Files you receive show up in this list.' : 'Nothing has been burned.'));
    for (const it of list) {
      const row = el('label', 'bi');
      const cb = el('input'); cb.type = 'checkbox'; cb.checked = burnSel.has(it.id);
      cb.onchange = () => { cb.checked ? burnSel.add(it.id) : burnSel.delete(it.id); renderBurnFoot(); };
      const m = el('div', 'm'); m.append(el('b')); m.firstChild.textContent = it.name;
      m.append(el('span', '', `${esc(fmtBytes(it.size))} · from ${esc(it.from)} · ${esc(stampOf(new Date(it.ts)).replace('_', ' ').replace(/-(\d\d)-(\d\d)$/, ':$1:$2'))}`));
      row.append(cb, m);
      if (burnTab === 'files' && it.blob) { const s = el('button', 'sm', 'Save'); s.type = 'button'; s.onclick = (e) => { e.preventDefault(); saveItem(it); }; row.append(s); }
      box.append(row);
    }
    renderBurnFoot();
  }
  function renderBurnFoot() {
    const f = $('#burnFoot'); f.innerHTML = '';
    const n = burnSel.size;
    if (burnTab === 'files') {
      const b = el('button', 'big primary', `<b>Burn ${n ? `${n} selected` : ''}</b>`); b.disabled = !n; b.onclick = burnSelected; f.append(b);
    } else {
      const r = el('button', 'big primary', `<b>Restore ${n ? `${n} selected` : ''}</b>`); r.disabled = !n; r.onclick = restoreSelected;
      const d = el('button', 'big', '<b>Delete forever</b>'); d.disabled = !n; d.onclick = deleteSelected;
      f.append(r, d);
    }
  }
  async function burnSelected() {
    const ok = await dialog({ title: 'Would you like to burn these?', body: 'Once burned, they cannot be recovered.', buttons: [{ label: 'No', value: false }, { label: 'Yes, burn', value: true, cls: 'pri' }] });
    if (!ok) return;
    received.forEach((r) => { if (burnSel.has(r.id)) r.burned = true; });
    burnSel.clear(); renderBurn();
    await dialog({ title: 'Sike!', body: 'It can be recovered. Open Recently burned to restore it.' });
  }
  function restoreSelected() { received.forEach((r) => { if (burnSel.has(r.id)) r.burned = false; }); burnSel.clear(); renderBurn(); toast('Restored'); }
  async function deleteSelected() {
    const ok = await dialog({ title: 'Delete forever?', body: 'This time it really cannot be recovered.', buttons: [{ label: 'Cancel', value: false }, { label: 'Delete forever', value: true, cls: 'danger' }] });
    if (!ok) return;
    for (let i = received.length - 1; i >= 0; i--) if (burnSel.has(received[i].id)) received.splice(i, 1);
    burnSel.clear(); renderBurn();
  }

  // easter egg: tap the flame seven times
  let taps = [];
  function flameTap() {
    const n = Date.now(); taps = taps.filter((x) => n - x < 3500); taps.push(n);
    if (taps.length >= 7) { taps = []; showEgg(); }
  }
  function showEgg() {
    me.egg = true; $('#egg').hidden = false; sendProfile(); renderHome();
  }

  // ------------------------------------------------------------------ profile
  function renderProfile() {
    const p = meAsPeer();
    $('#profAv').innerHTML = avatarHTML(p, 128);
    const box = $('#avChoices'); box.innerHTML = '';
    const mk = (label, mode, enabled, who) => {
      const b = el('button', 'choice' + (me.avMode === mode ? ' on' : ''), `<span class="ring">${avatarHTML(who, 52)}</span>${label}`);
      b.type = 'button'; b.disabled = !enabled; b.onclick = () => { me.avMode = mode; sendProfile(); renderProfile(); renderHome(); };
      box.append(b);
    };
    mk('Initial', 0, true, { ...p, mode: 0 });
    mk('Photo', 1, !!me.photo, { ...p, mode: 1 });
    if (me.egg) mk('Highland', 2, true, { ...p, mode: 2 });
    $('#photoDel').hidden = !me.photo;
    $('#eggLine').hidden = !me.egg;
    $('#nameBox').value = me.name; $('#nameCount').textContent = `${me.name.length}/16`;
  }
  async function makePhoto(file) {
    const bmp = await createImageBitmap(file);
    const s = Math.min(bmp.width, bmp.height);
    const c = document.createElement('canvas'); c.width = c.height = 96;
    c.getContext('2d').drawImage(bmp, (bmp.width - s) / 2, (bmp.height - s) / 2, s, s, 0, 0, 96, 96);
    let q = 0.8, d;
    do { d = c.toDataURL('image/jpeg', q); q -= 0.1; } while (d.length > 9000 && q > 0.2);
    if (d.length > 9000) throw new Error('too big');
    return d;
  }

  // ------------------------------------------------------------------ wiring
  function wire() {
    $('#termsGate').innerHTML = termsHTML();
    $('#termsTick').onchange = (e) => { $('#termsGo').disabled = !e.target.checked; };
    $('#termsGo').onclick = () => { store.set(TERMS_KEY, true); start(); };
    $('#termsLink').onclick = () => {
      const box = el('div', 'terms'); box.innerHTML = termsHTML(); box.style.cssText = 'max-height:50vh;overflow:auto';
      dialog({ title: 'Terms of Use', extra: box, buttons: [{ label: 'Close', value: true, cls: 'pri' }] });
    };
    $('#themeBtn').onclick = () => { const o = ['auto', 'light', 'dark']; store.set('theme', o[(o.indexOf(store.get('theme', 'auto')) + 1) % 3]); applyTheme(); };
    $('#meBtn').onclick = () => { renderProfile(); show('s-profile'); };
    $('#brand').onclick = () => { if (AND) return; burnSel.clear(); burnTab = 'files'; renderBurn(); show('s-burn'); };
    $('#btnCreate').onclick = startRoom;
    $('#btnJoin').onclick = () => { askNotify(); openPair('join'); };
    $('#roomBack').onclick = async () => {
      const ok = await dialog({ title: 'Leave the room?', body: room.role === 'leader' && room.conns.size ? 'Everyone will be disconnected.' : '', buttons: [{ label: 'Stay', value: false }, { label: 'Leave', value: true, cls: 'pri' }] });
      if (ok) leaveRoom();
    };
    $('#addBtn').onclick = () => openPair('host');
    $('#pairClose').onclick = closePair;
    $('#composer').onsubmit = (e) => { e.preventDefault(); const b = $('#msgBox'); sendChat(b.value); b.value = ''; b.focus(); };
    $('#filePick').onchange = (e) => onPick(e.target.files);
    $('#burnBack').onclick = () => show('s-home');
    $('#flameBtn').onclick = flameTap;
    $('#tabFiles').onclick = () => { burnTab = 'files'; burnSel.clear(); renderBurn(); };
    $('#tabBurned').onclick = () => { burnTab = 'burned'; burnSel.clear(); renderBurn(); };
    $('#profBack').onclick = () => show('s-home');
    $('#profAv').onclick = () => $('#photoPick').click();
    $('#photoBtn').onclick = () => $('#photoPick').click();
    $('#photoDel').onclick = () => { me.photo = ''; if (me.avMode === 1) me.avMode = 0; sendProfile(); renderProfile(); renderHome(); };
    $('#photoPick').onchange = async (e) => {
      const f = e.target.files[0]; e.target.value = '';
      if (!f) return;
      try { me.photo = await makePhoto(f); me.avMode = 1; $('#photoErr').hidden = true; sendProfile(); renderProfile(); renderHome(); }
      catch (_) { $('#photoErr').hidden = false; }
    };
    $('#nameBox').oninput = (e) => { $('#nameCount').textContent = `${e.target.value.length}/16`; };
    $('#nameSave').onclick = () => {
      const v = $('#nameBox').value.replace(/[\u0000-\u001f]/g, '').trim().slice(0, 16);
      if (!v) { toast('Pick a name first', true); return; }
      me.name = v; sendProfile(); renderProfile(); renderHome(); toast('Name saved');
    };
    $('#eggEquip').onclick = () => { me.avMode = 2; $('#egg').hidden = true; sendProfile(); renderHome(); if (current === 's-profile') renderProfile(); };
    $('#eggLater').onclick = () => { $('#egg').hidden = true; if (current === 's-profile') renderProfile(); };
  }

  // Android's back button calls this. Returns true when Heat handled it.
  window.heatBack = () => {
    if (!$('#dlg').hidden) { closeDialog(false); return true; }
    if (!$('#egg').hidden) { $('#egg').hidden = true; return true; }
    if (!$('#pair').hidden) { closePair(); return true; }
    if (current === 's-room') { $('#roomBack').click(); return true; }
    if (current === 's-burn' || current === 's-profile') { show('s-home'); return true; }
    return false;
  };

  let joinFromLink = null;
  function start() {
    show('s-home');
    if (joinFromLink) { const c = joinFromLink; joinFromLink = null; openPair('join', c); }
  }

  function boot() {
    if (AND) document.body.classList.add('android');
    applyTheme(); wire();
    if (/iPhone|iPad|iPod/.test(navigator.userAgent) && !navigator.standalone && !AND) $('#tip').hidden = false;
    if (!AND && 'serviceWorker' in navigator && (location.protocol === 'https:' || location.hostname === 'localhost')) {
      navigator.serviceWorker.register('sw.js').catch(() => {});
    }
    joinFromLink = extractCode(location.hash.slice(1));
    if (location.hash) history.replaceState(null, '', location.pathname + location.search);
    matchMedia('(prefers-color-scheme: dark)').addEventListener && matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme);
    if (AND) store.set(TERMS_KEY, true); // the Android app already made you accept the terms
    if (!store.get(TERMS_KEY)) show('s-terms'); else start();
  }

  window.__heat = { room, xfers, received, me, routes }; // read-only handles for debugging
  boot();
})();
