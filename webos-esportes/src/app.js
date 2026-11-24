/*
 * Bad Wolf Esportes para TVs LG (webOS): só esporte (Eventos e Canais), app de testes de navegação e player.
 * Lê o mesmo catálogo pronto do servidor que o app Android (catálogo v2: badwolf.pages.dev/app/v2/) e só exibe.
 * Tela de 1920x1080 controlada pelo controle remoto (setas, OK, Voltar).
 * Escrito para TVs a partir do webOS 4 (2018); o build (esbuild) converte para o navegador delas.
 */
"use strict";

// ======================= configuração =======================

const CFG = window.BW_CONFIG || {};
const SERVIDOR = (CFG.servidor || "https://badwolf.pages.dev").replace(/\/+$/, "");
const TMDB_FUNDO = "https://image.tmdb.org/t/p/w1280/";
const VERSAO_APP = CFG.versao || "dev";
/** A interface é desenhada em 1536x864 e aumentada até a tela de 1920x1080 (ver #ui no CSS). */
const ESCALA = 1.25;

const KEY = {
  LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, OK: 13,
  BACK: [461, 27, 10009], BACKSPACE: 8,
  PLAY: 415, PAUSE: 19, PLAYPAUSE: 10252, STOP: 413, FF: 417, RW: 412,
  CH_UP: 33, CH_DOWN: 34,
};

// ======================= utilidades =======================

const $ = (sel, root) => (root || document).querySelector(sel);
const $$ = (sel, root) => Array.prototype.slice.call((root || document).querySelectorAll(sel));

/** h("div.card.foc", {onclick}, [filhos]) -> elemento */
function h(tag, attrs, kids) {
  const parts = tag.split(".");
  const el = document.createElement(parts[0] || "div");
  if (parts.length > 1) el.className = parts.slice(1).join(" ");
  if (attrs) {
    for (const k of Object.keys(attrs)) {
      const v = attrs[k];
      if (v == null || v === false) continue;
      if (k === "text") el.textContent = v;
      else if (k === "html") el.innerHTML = v;
      else if (k === "style" && typeof v === "object") Object.keys(v).forEach((s) => { el.style[s] = v[s]; });
      else if (k.indexOf("on") === 0) el[k] = v;
      else if (k === "bg") el.style.backgroundImage = v ? "url(\"" + String(v).replace(/"/g, "%22") + "\")" : "";
      else el.setAttribute(k, v);
    }
  }
  if (kids != null) (Array.isArray(kids) ? kids : [kids]).forEach((c) => {
    if (c == null || c === false) return;
    el.appendChild(typeof c === "string" || typeof c === "number" ? document.createTextNode(String(c)) : c);
  });
  return el;
}

/** Memória de saúde das fontes e servidores (mesma regra do :core, salva em localStorage). */
const Saude = {
  _m: null,
  _carregar() {
    if (this._m) return this._m;
    const raw = store("bw_saude") || {};
    const corte = Date.now() - 7 * 24 * 3600 * 1000;
    const limpo = {};
    Object.keys(raw).forEach((k) => { if (raw[k] >= corte) limpo[k] = raw[k]; });
    this._m = limpo;
    return limpo;
  },
  _salvar() { store("bw_saude", this._m); },
  host(u) {
    const m = /^https?:\/\/([^\/:?#]+)/i.exec(u || "");
    return m && m[1] ? m[1].toLowerCase() : null;
  },
  marcarOk(u) {
    const m = this._carregar(), now = Date.now(), h = this.host(u);
    m["ok:" + u] = now; delete m["fail:" + u];
    if (h) { m["hok:" + h] = now; delete m["hfail:" + h]; }
    this._salvar();
  },
  marcarFalha(u) {
    const m = this._carregar(), now = Date.now(), h = this.host(u);
    m["fail:" + u] = now;
    if (h) m["hfail:" + h] = now;
    this._salvar();
  },
  nota(u, now) {
    const m = this._carregar();
    const ok = m["ok:" + u] || 0, fail = m["fail:" + u] || 0;
    if (fail > ok && now - fail < 6 * 3600 * 1000) return 4;
    if (ok > 0 && now - ok < 7 * 24 * 3600 * 1000) return 0;
    const h = this.host(u);
    if (!h) return 2;
    const hok = m["hok:" + h] || 0, hfail = m["hfail:" + h] || 0;
    if (hfail > hok && now - hfail < 30 * 60 * 1000) return 3;
    if (hok > 0 && now - hok < 30 * 60 * 1000) return 1;
    return 2;
  },
  ordenar(fontes) {
    if (!fontes || fontes.length < 2) return fontes || [];
    const now = Date.now();
    return fontes.map((f, i) => ({ f, i, n: this.nota(f.u, now) }))
      .sort((a, b) => a.n - b.n || a.i - b.i)
      .map((x) => x.f);
  },
};

const pad2 = (n) => (n < 10 ? "0" : "") + n;
function hora(ms) { const d = new Date(ms); return pad2(d.getHours()) + ":" + pad2(d.getMinutes()); }
function relogio(seg) {
  seg = Math.max(0, Math.floor(seg || 0));
  const hh = Math.floor(seg / 3600), mm = Math.floor(seg / 60) % 60, ss = seg % 60;
  return (hh ? hh + ":" + pad2(mm) : pad2(mm)) + ":" + pad2(ss);
}
function semAcento(s) {
  return (s || "").normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();
}
function store(k, v) {
  try {
    if (v === undefined) { const s = localStorage.getItem("bw." + k); return s == null ? null : JSON.parse(s); }
    if (v === null) localStorage.removeItem("bw." + k); else localStorage.setItem("bw." + k, JSON.stringify(v));
  } catch (e) { /* sem espaço: segue sem guardar */ }
  return null;
}
function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }
function comTempo(p, ms, msg) {
  return Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error(msg || "tempo esgotado")), ms))]);
}
function toast(msg, ms) {
  const ov = $("#overlay");
  $$(".toast", ov).forEach((t) => t.remove());
  const t = h("div.toast", { text: msg });
  ov.appendChild(t);
  setTimeout(() => t.remove(), ms || 2500);
}

// ======================= cache no aparelho (IndexedDB) =======================

const Cache = {
  db: null,
  abrir() {
    return new Promise((res) => {
      try {
        const req = indexedDB.open("badwolf", 1);
        req.onupgradeneeded = () => req.result.createObjectStore("partes");
        req.onsuccess = () => { this.db = req.result; res(); };
        req.onerror = () => res();
      } catch (e) { res(); }
    });
  },
  ler(k) {
    return new Promise((res) => {
      if (!this.db) return res(null);
      try {
        const r = this.db.transaction("partes").objectStore("partes").get(k);
        r.onsuccess = () => res(r.result || null);
        r.onerror = () => res(null);
      } catch (e) { res(null); }
    });
  },
  gravar(k, v) {
    return new Promise((res) => {
      if (!this.db) return res();
      try {
        const tx = this.db.transaction("partes", "readwrite");
        tx.objectStore("partes").put(v, k);
        tx.oncomplete = () => res();
        tx.onerror = () => res();
      } catch (e) { res(); }
    });
  },
};

// ======================= catálogo (só canais e guia) =======================

const Cat = {
  manifesto: null,
  categorias: { canais: [] },
  canais: [], canalPorId: {},
  guia: null,
  transmissoes: [],
  token() { return store("token") || CFG.token || ""; },
  base() {
    const tk = this.token();
    return tk ? SERVIDOR + "/" + encodeURIComponent(tk) + "/app/v2/" : SERVIDOR + "/app/v2/";
  },

  async baixar(caminho, tempo) {
    const r = await comTempo(fetch(this.base() + caminho, { cache: "no-store" }), tempo || 30000, "o servidor não respondeu");
    if (r.status === 404) throw new Error("404");
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.text();
  },

  /** Parte do catálogo: do cache se o hash bate; senão baixa (3 tentativas) e guarda. */
  async parte(nome) {
    const hash = this.manifesto && this.manifesto.partes[nome];
    const c = await Cache.ler(nome);
    if (c && (c.hash === hash || !hash)) return JSON.parse(c.texto);
    let erro;
    for (let i = 0; i < 3; i++) {
      try {
        const texto = await this.baixar(nome + ".json");
        const dados = JSON.parse(texto);
        Cache.gravar(nome, { hash: hash, texto: texto });
        return dados;
      } catch (e) {
        erro = e;
        if (e.message === "404") break;
        await sleep(600 * (i + 1));
      }
    }
    if (c) return JSON.parse(c.texto); // sem internet: o que já tinha
    throw erro;
  },

  /** O app de esporte só precisa de três partes: categorias, canais e guia (abre bem mais rápido). */
  async carregar(progresso) {
    let m = null;
    try {
      m = JSON.parse(await this.baixar("manifesto.json", 20000));
      Cache.gravar("manifesto", { hash: m.versao, texto: JSON.stringify(m) });
    } catch (e) {
      if (e.message === "404") throw new Error("chave");
      const c = await Cache.ler("manifesto");
      if (!c) throw e;
      m = JSON.parse(c.texto);
    }
    this.manifesto = m;
    progresso(0, 3);
    const [cats, canais] = await Promise.all([this.parte("categorias"), this.parte("canais")]);
    progresso(2, 3);
    let guia = {};
    let transmissoes = [];
    await Promise.all([
      this.parte("guia").then((g) => { guia = g || {}; }).catch(() => {}),
      this.parte("transmissoes").then((t) => { transmissoes = Array.isArray(t) ? t : []; }).catch(() => {}),
    ]);
    progresso(3, 3);
    this.categorias = cats || this.categorias;
    this.canais = canais || [];
    this.guia = guia || {};
    this.transmissoes = transmissoes;
    this.canalPorId = {};
    this.canais.forEach((c) => { this.canalPorId[c.id] = c; });
    Esporte.preparar();
  },

  programas(canal) {
    const g = this.guia && this.guia[String(canal.id)];
    if (!g) return [];
    return g.map((p) => ({ ini: p[0] * 1000, fim: p[1] * 1000, t: p[2], d: p[3] }));
  },
  agora(canal, t) {
    const ps = this.programas(canal);
    for (let i = 0; i < ps.length; i++) if (ps[i].ini <= t && t < ps[i].fim) return ps[i];
    return null;
  },
};

/** Favoritos de canal (o app de esporte não tem filme/série; o player chama estas funções só para eles). */
const Usuario = {
  favs() { return store("favs") || []; },
  ehFav(k) { return this.favs().indexOf(k) >= 0; },
  trocarFav(k) {
    const f = this.favs(); const i = f.indexOf(k);
    if (i >= 0) f.splice(i, 1); else f.unshift(k);
    store("favs", f);
    return i < 0;
  },
  marcarVisto() {},
  salvarProgresso() {},
};

// ======================= esporte: categorias e eventos =======================

const Esporte = {
  /** Categorias de esporte do catálogo (pelo nome), na ordem do servidor. */
  RX_CAT: /sportv|premiere|espn|combate|ufc|esporte|campeonato|ppv|nba|nfl|liga/i,
  /** Canais abertos que passam jogo (entram numa categoria "TV aberta"). */
  RX_ABERTO: /^(globo sp|globo rj|band sp|record sp|sbt sp|redetv!?|tv cultura)$/i,
  cats: [], // [{id, nome, canais}]
  esportivos: [], // canais que entram nos eventos

  preparar() {
    const cats = (Cat.categorias.canais || []).filter((c) => c.id !== "principais" && this.RX_CAT.test(c.nome));
    this.cats = cats.map((c) => ({ id: c.id, nome: c.nome, canais: Cat.canais.filter((x) => x.c === c.id) })).filter((c) => c.canais.length);
    const abertos = Cat.canais.filter((x) => this.RX_ABERTO.test(x.b || x.n));
    if (abertos.length) this.cats.push({ id: "abertos", nome: "TV aberta", canais: abertos });
    const vistos = {};
    this.esportivos = [];
    this.cats.forEach((c) => c.canais.forEach((x) => { if (!vistos[x.id]) { vistos[x.id] = 1; this.esportivos.push(x); } }));
  },

  /** É transmissão esportiva? (jogo "A x B", luta, corrida, liga...) */
  ehEvento(t) {
    return /\s(x|vs\.?)\s/i.test(t) || /\b(ufc|nba|nfl|nhl|mlb|f[óo]rmula|f1|motogp|stock car|t[êe]nis|boxe|luta|futebol|copa|liga|campeonato|libertadores|sul-?americana|brasileir[ãa]o|champions|premier league|la liga|serie a|bundesliga|grand prix|gp )/i.test(t);
  },
  limpar(t) {
    return t.replace(/^\s*(vt|reprise)\s*[-:–]\s*/i, "")
      .replace(/^\s*ao vivo\s*[-:–]\s*/i, "") // "Ao vivo - Dallas x Pittsburgh"
      .replace(/^\s*dia\s+\d{1,2}\/\d{1,2}\s*[-:–]\s*/i, "") // "Dia 23/11 - Santos x Grêmio"
      .replace(/\s*[-–]\s*ao vivo\s*$/i, "").replace(/\s*\(ao vivo\)\s*$/i, "").trim();
  },

  /**
   * Eventos das próximas horas, montados pelo guia: o mesmo jogo em vários canais vira um evento só, com a
   * lista de onde assistir (cada canal com as qualidades dele). Ao vivo agora > mais tarde > reprises.
   */
  eventos(agora) {
    agora = agora || Date.now();
    const porChave = {};
    this.esportivos.forEach((canal) => {
      Cat.programas(canal).forEach((p) => {
        if (p.fim <= agora || p.ini > agora + 18 * 3600 * 1000 || !this.ehEvento(p.t)) return;
        const reprise = /^\s*(vt|reprise)\b/i.test(p.t) || /\breprise\b/i.test(p.t);
        const titulo = this.limpar(p.t);
        const m = /^([^:]{2,40}):\s*(.+)$/.exec(titulo);
        const liga = m ? m[1].trim() : "";
        const jogo = m ? m[2].trim() : titulo;
        // o mesmo jogo em canais diferentes: mesmo título e começo até 40 min de diferença
        // "A x B" e "B x A" são o mesmo jogo (cada canal escreve numa ordem)
        const lados = semAcento(jogo).split(/\s+(?:x|vs\.?)\s+/).map((t) => t.replace(/[^a-z0-9]+/g, " ").trim()).sort();
        const k = lados.join(" x ") + "|" + (reprise ? "r" : "v") + "|" + Math.round(p.ini / (40 * 60000));
        const ev = porChave[k] || (porChave[k] = { id: k, jogo, liga, ini: p.ini, fim: p.fim, reprise, canais: {} });
        ev.ini = Math.min(ev.ini, p.ini); ev.fim = Math.max(ev.fim, p.fim);
        const base = canal.b || canal.n;
        (ev.canais[base] || (ev.canais[base] = [])).push(canal);
      });
    });
    const ordemQ = { "4K": 3, FHD: 0, HD: 1, SD: 2 };
    const qual = (c) => { const m = / (4K|FHD|HD|SD)$/.exec(c.n); return m ? m[1] : ""; };
    return Object.keys(porChave).map((k) => {
      const ev = porChave[k];
      // onde assistir: cada canal (nome sem a qualidade) com as qualidades, FHD primeiro
      ev.onde = Object.keys(ev.canais).map((base) => ({
        base,
        logo: (ev.canais[base][0] || {}).l,
        opcoes: ev.canais[base].slice().sort((a, b) => (ordemQ[qual(a)] || 0) - (ordemQ[qual(b)] || 0)).map((c) => ({ canal: c, q: qual(c) })),
      }));
      ev.aoVivo = !ev.reprise && ev.ini <= agora && agora < ev.fim;
      return ev;
    }).sort((a, b) => (a.reprise - b.reprise) || (b.aoVivo - a.aoVivo) || (a.ini - b.ini));
  },
};

// ======================= foco (navegação pelo controle) =======================

/**
 * Navegação por zonas, com regra fixa (antes era "o mais perto na tela" e se perdia):
 * - cada lista é uma zona (data-zona) com um eixo (data-eixo = "v" lista em pé, "h" fileira);
 * - as setas do eixo andam item a item DENTRO da zona;
 * - as outras setas (ou o fim da lista) vão para a zona vizinha declarada (data-cima, data-baixo, data-esq,
 *   data-dir), no item em que você estava lá da última vez.
 * Fora de zona (caixas de pergunta, lista de fontes do player): posição na tela.
 */
const Foco = {
  atual: null,
  raiz() {
    const ov = $("#overlay");
    return $$(".foc", ov).some((e) => e.offsetParent !== null) ? ov : $("#ui");
  },
  definir(el) {
    if (!el) return;
    if (this.atual) this.atual.classList.remove("focus");
    this.atual = el;
    el.classList.add("focus");
    const zona = el.closest("[data-zona]");
    if (zona) zona._ultimo = el;
    const grupo = el.closest("[data-grupo]");
    if (grupo) grupo._ultimo = el;
    if (el.tagName === "INPUT") el.focus(); else if (document.activeElement && document.activeElement.tagName === "INPUT") document.activeElement.blur();
    mostrar(el);
    if (el._aoFocar) el._aoFocar();
    const t = Telas.topo();
    if (t && t.focou) t.focou(el);
  },
  itens(zona) {
    return $$(".foc", zona).filter((e) => e.offsetParent !== null && e.closest("[data-zona]") === zona);
  },
  /** Entra numa zona: no último item focado nela ou, se não houver, no que está mais perto na tela. */
  entrar(zona, de) {
    if (!zona) return false;
    if (zona._ultimo && document.contains(zona._ultimo) && zona._ultimo.offsetParent !== null) { this.definir(zona._ultimo); return true; }
    const itens = this.itens(zona);
    if (!itens.length) return false;
    // primeira vez: o primeiro item que aparece na tela (a lista pode estar rolada)
    const caixa = zona.getBoundingClientRect();
    const alvo = itens.filter((e) => e.getBoundingClientRect().top >= caixa.top - 2)[0] || itens[0];
    this.definir(alvo);
    return true;
  },
  mover(dir) {
    const cur = this.atual && document.contains(this.atual) ? this.atual : null;
    if (!cur) { const p = $(".foc", this.raiz()); if (p) this.definir(p); return !!p; }
    const zona = cur.closest("[data-zona]");
    if (!zona) return this.moverPelaTela(cur, dir);
    const eixo = zona.getAttribute("data-eixo") || "v";
    const passo = eixo === "v" ? { up: -1, down: 1 }[dir] : { left: -1, right: 1 }[dir];
    if (passo) {
      const itens = this.itens(zona);
      const i = itens.indexOf(cur) + passo;
      if (i >= 0 && i < itens.length) { this.definir(itens[i]); return true; }
    }
    const vizinha = zona.getAttribute({ up: "data-cima", down: "data-baixo", left: "data-esq", right: "data-dir" }[dir]);
    if (!vizinha) return false;
    const destino = $("[data-zona='" + vizinha + "']", zona.closest(".caixa") || this.raiz()) || $("[data-zona='" + vizinha + "']", this.raiz());
    return this.entrar(destino, cur);
  },
  /** Posição na tela (só fora de zona). */
  moverPelaTela(cur, dir) {
    const todos = $$(".foc", this.raiz()).filter((e) => e.offsetParent !== null && e !== cur);
    const a = cur.getBoundingClientRect();
    const ax = (a.left + a.right) / 2, ay = (a.top + a.bottom) / 2;
    let melhor = null, nota = Infinity;
    for (const e of todos) {
      const b = e.getBoundingClientRect();
      const bx = (b.left + b.right) / 2, by = (b.top + b.bottom) / 2;
      let principal, lateral;
      if (dir === "right") { if (bx <= ax + 1) continue; principal = bx - ax; lateral = Math.abs(by - ay); }
      else if (dir === "left") { if (bx >= ax - 1) continue; principal = ax - bx; lateral = Math.abs(by - ay); }
      else if (dir === "down") { if (by <= ay + 1) continue; principal = by - ay; lateral = Math.abs(bx - ax); }
      else { if (by >= ay - 1) continue; principal = ay - by; lateral = Math.abs(bx - ax); }
      const n = principal + lateral * 2;
      if (n < nota) { nota = n; melhor = e; }
    }
    if (!melhor) return false;
    this.definir(melhor);
    return true;
  },
};

/** Rola as caixas (data-rola="x" / "y") para o elemento focado aparecer inteiro. */
function mostrar(el) {
  let p = el.parentElement;
  while (p && p !== document.body) {
    const eixo = p.getAttribute && p.getAttribute("data-rola");
    if (eixo) {
      const caixa = p.getBoundingClientRect(), r = el.getBoundingClientRect();
      const folga = Number(p.getAttribute("data-folga") || 40);
      const f = folga * ESCALA;
      if (eixo === "y") {
        if (r.bottom > caixa.bottom - f) p.scrollTop += (r.bottom - caixa.bottom + f) / ESCALA;
        else if (r.top < caixa.top + f) p.scrollTop -= (caixa.top + f - r.top) / ESCALA;
      } else {
        if (r.right > caixa.right - f) p.scrollLeft += (r.right - caixa.right + f) / ESCALA;
        else if (r.left < caixa.left + f) p.scrollLeft -= (caixa.left + f - r.left) / ESCALA;
      }
    }
    p = p.parentElement;
  }
}

// ======================= telas (pilha para o Voltar) =======================

const Telas = {
  pilha: [],
  abrir(tela, substituir) {
    const atual = this.topo();
    if (atual) {
      atual._foco = Foco.atual;
      if (atual.esconder) atual.esconder();
      if (substituir) { this.pilha.pop(); this.tirar(atual); }
      else if (atual._caixa) atual._caixa.style.display = "none";
    }
    this.pilha.push(tela);
    const ui = $("#ui");
    ui.classList.remove("hidden");
    tela._caixa = h("div.caixa");
    ui.appendChild(tela._caixa);
    Foco.atual = null;
    const alvo = tela.montar(tela._caixa);
    Foco.definir(alvo || $(".foc", tela._caixa));
  },
  tirar(tela) {
    if (tela.sair) tela.sair();
    if (tela._caixa && tela._caixa.parentNode) tela._caixa.parentNode.removeChild(tela._caixa);
  },
  voltar() {
    const atual = this.topo();
    if (atual && atual.voltar && atual.voltar()) return;
    if (this.pilha.length <= 1) { perguntarSair(); return; }
    this.pilha.pop();
    this.tirar(atual);
    const ant = this.topo();
    ant._caixa.style.display = "";
    Foco.atual = null;
    const alvo = ant.voltou ? ant.voltou() : null;
    Foco.definir(alvo || (ant._foco && document.contains(ant._foco) ? ant._foco : $(".foc", ant._caixa)));
  },
  limpar() {
    this.pilha.slice().reverse().forEach((t) => this.tirar(t));
    this.pilha = [];
    $("#ui").innerHTML = "";
  },
  topo() { return this.pilha[this.pilha.length - 1]; },
};

function perguntarSair() {
  dialogo("Sair do Bad Wolf Esportes?", null, [
    { t: "Ficar", f: () => {} },
    { t: "Sair", f: () => { Player.parar(); try { window.close(); } catch (e) { /* ignore */ } } },
  ]);
}

/** Caixa de pergunta por cima de tudo; Voltar fecha. */
function dialogo(titulo, texto, botoes) {
  const ov = $("#overlay");
  const anterior = Foco.atual;
  const fechar = () => { caixa.remove(); Dialogo.aberto = null; if (anterior && document.contains(anterior)) Foco.definir(anterior); };
  const caixa = h("div.dialog", null, h("div.box", null, [
    h("h2", { text: titulo }), texto ? h("p", { text: texto }) : null,
    h("div.btns", { "data-zona": "dialogo", "data-eixo": "h" }, botoes.map((b) => { const el = h("div.btn.foc", { text: b.t }); el._ok = () => { fechar(); b.f(); }; return el; })),
  ]));
  ov.appendChild(caixa);
  Dialogo.aberto = fechar;
  Foco.atual = null;
  Foco.definir($(".foc", caixa));
}
const Dialogo = { aberto: null };

// ======================= Conta W4LLE + Pareamento por QR Code =======================

const Conta = {
  SUPABASE_URL: "https://hkiifyxossklpnsavujw.supabase.co",
  ANON: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImhraWlmeXhvc3NrbHBuc2F2dWp3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzcxNDc1MTAsImV4cCI6MjA5MjcyMzUxMH0.GFpAWo-8yJVALJIDoiPCfDGYyMgTkVubM8JjYzll5EA",
  PAGINA: "https://badwolf.pages.dev/entrar",
  ALFABETO: "23456789ABCDEFGHJKMNPQRSTVWXYZ",
  STORAGE_KEY: "bw_conta",

  ler() {
    try {
      const s = localStorage.getItem(this.STORAGE_KEY);
      if (!s) return null;
      const o = JSON.parse(s);
      return o && o.user_id && o.refresh ? o : null;
    } catch (e) { return null; }
  },
  salvar(o) {
    try {
      if (o) localStorage.setItem(this.STORAGE_KEY, JSON.stringify(o));
      else localStorage.removeItem(this.STORAGE_KEY);
    } catch (e) { /* ignore */ }
  },
  conectado() { return !!this.ler(); },
  usuario() {
    const s = this.ler();
    return s ? { id: s.user_id, nome: s.nome || "Conta W4LLE" } : null;
  },
  label() {
    const u = this.usuario();
    return u ? (u.nome || "Conta") : "Entrar";
  },

  novoCodigo() {
    let out = "";
    const ab = this.ALFABETO;
    if (window.crypto && window.crypto.getRandomValues) {
      const buf = new Uint8Array(8);
      window.crypto.getRandomValues(buf);
      for (let i = 0; i < 8; i++) out += ab[buf[i] % ab.length];
    } else {
      for (let i = 0; i < 8; i++) out += ab[Math.floor(Math.random() * ab.length)];
    }
    return out;
  },
  mostrarCodigo(c) { return c.slice(0, 4) + "-" + c.slice(4); },
  urlPareamento(c) { return this.PAGINA + "#" + c; },
  qrUrl(c) {
    return "https://api.qrserver.com/v1/create-qr-code/?size=280x280&margin=8&data=" + encodeURIComponent(this.urlPareamento(c));
  },

  _utf8(str) {
    if (typeof TextEncoder !== "undefined") return new TextEncoder().encode(str);
    const s = unescape(encodeURIComponent(str));
    const out = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
    return out;
  },
  _deUtf8(buf) {
    const bytes = new Uint8Array(buf);
    if (typeof TextDecoder !== "undefined") return new TextDecoder().decode(bytes);
    let s = "";
    for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return decodeURIComponent(escape(s));
  },
  _deB64(b64) {
    const bin = atob(String(b64).replace(/\s+/g, ""));
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  },
  _hex(bytes) {
    let s = "";
    for (let i = 0; i < bytes.length; i++) s += (bytes[i] < 16 ? "0" : "") + bytes[i].toString(16);
    return s;
  },
  _sha256Js(bytes) {
    const K = [
      0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
      0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
      0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
      0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
      0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
      0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
      0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
      0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2,
    ];
    const H = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19];
    const len = bytes.length, bitLen = len * 8;
    const padLen = ((len + 9 + 63) >> 6) << 6;
    const msg = new Uint8Array(padLen);
    msg.set(bytes);
    msg[len] = 0x80;
    const dv = new DataView(msg.buffer);
    dv.setUint32(padLen - 8, Math.floor(bitLen / 0x100000000), false);
    dv.setUint32(padLen - 4, bitLen >>> 0, false);
    const w = new Uint32Array(64);
    for (let off = 0; off < padLen; off += 64) {
      for (let i = 0; i < 16; i++) w[i] = dv.getUint32(off + i * 4, false);
      for (let i = 16; i < 64; i++) {
        const s0 = ((w[i - 15] >>> 7) | (w[i - 15] << 25)) ^ ((w[i - 15] >>> 18) | (w[i - 15] << 14)) ^ (w[i - 15] >>> 3);
        const s1 = ((w[i - 2] >>> 17) | (w[i - 2] << 15)) ^ ((w[i - 2] >>> 19) | (w[i - 2] << 13)) ^ (w[i - 2] >>> 10);
        w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
      }
      let a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h0 = H[7];
      for (let i = 0; i < 64; i++) {
        const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
        const ch = (e & f) ^ (~e & g);
        const t1 = (h0 + S1 + ch + K[i] + w[i]) >>> 0;
        const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
        const maj = (a & b) ^ (a & c) ^ (b & c);
        const t2 = (S0 + maj) >>> 0;
        h0 = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = b; b = a; a = (t1 + t2) >>> 0;
      }
      H[0] = (H[0] + a) >>> 0; H[1] = (H[1] + b) >>> 0; H[2] = (H[2] + c) >>> 0; H[3] = (H[3] + d) >>> 0;
      H[4] = (H[4] + e) >>> 0; H[5] = (H[5] + f) >>> 0; H[6] = (H[6] + g) >>> 0; H[7] = (H[7] + h0) >>> 0;
    }
    const out = new Uint8Array(32);
    const odv = new DataView(out.buffer);
    for (let i = 0; i < 8; i++) odv.setUint32(i * 4, H[i], false);
    return out;
  },
  async sha256(str) {
    const bytes = this._utf8(str);
    try {
      if (window.crypto && window.crypto.subtle && window.crypto.subtle.digest) {
        return new Uint8Array(await window.crypto.subtle.digest("SHA-256", bytes));
      }
    } catch (e) { /* fallback para HTTP / webOS sem subtle */ }
    return this._sha256Js(bytes);
  },
  async canalDo(codigo) {
    return "pair-" + this._hex(await this.sha256("badwolf-canal:" + codigo)).slice(0, 24);
  },
  async decifrar(codigo, ivB64, ctB64) {
    if (!window.crypto || !window.crypto.subtle) throw new Error("WebCrypto indisponível");
    const chaveBytes = await this.sha256("badwolf-chave:" + codigo);
    const key = await window.crypto.subtle.importKey("raw", chaveBytes, { name: "AES-GCM" }, false, ["decrypt"]);
    const plain = await window.crypto.subtle.decrypt(
      { name: "AES-GCM", iv: this._deB64(ivB64) },
      key,
      this._deB64(ctB64)
    );
    return this._deUtf8(plain);
  },

  async trocarRefresh(refresh, nomeDica) {
    const r = await comTempo(fetch(this.SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token", {
      method: "POST",
      headers: { apikey: this.ANON, "Content-Type": "application/json" },
      body: JSON.stringify({ refresh_token: refresh }),
    }), 15000, "tempo esgotado");
    if (!r.ok) return false;
    const o = await r.json();
    const access = o.access_token;
    const newRefresh = o.refresh_token;
    const userId = o.user && o.user.id;
    if (!access || !newRefresh || !userId) return false;
    let nome = nomeDica || (o.user && o.user.user_metadata && o.user.user_metadata.username) || "";
    try {
      const pr = await comTempo(fetch(this.SUPABASE_URL + "/rest/v1/profiles?select=username&user_id=eq." + encodeURIComponent(userId), {
        headers: { apikey: this.ANON, Authorization: "Bearer " + access },
      }), 8000);
      if (pr.ok) {
        const arr = await pr.json();
        if (Array.isArray(arr) && arr[0] && arr[0].username) nome = arr[0].username;
      }
    } catch (e) { /* usa dica */ }
    this.salvar({
      access,
      refresh: newRefresh,
      expira: Date.now() + (o.expires_in || 3600) * 1000,
      user_id: userId,
      nome: nome || (o.user && o.user.email ? o.user.email.split("@")[0] : "Conta W4LLE"),
    });
    return true;
  },

  sair() {
    const s = this.ler();
    this.salvar(null);
    if (s && s.access) {
      fetch(this.SUPABASE_URL + "/auth/v1/logout?scope=local", {
        method: "POST",
        headers: { apikey: this.ANON, Authorization: "Bearer " + s.access, "Content-Type": "application/json" },
        body: "{}",
      }).catch(() => {});
    }
  },

  /** Inicia pareamento em tempo real (retorna função para cancelar ao sair da tela). */
  iniciarPareamento(codigo, onEstado) {
    let fechado = false;
    let ws = null;
    let hb = null;
    let recon = null;
    let rotacao = null;
    let ref = 1;
    let processando = false;
    let topic = "";

    const enviar = (t, event, payload) => {
      if (!ws || ws.readyState !== 1) return;
      try { ws.send(JSON.stringify({ topic: t, event, payload, ref: String(ref++) })); } catch (e) { /* ignore */ }
    };
    const fechar = () => {
      fechado = true;
      clearInterval(hb);
      clearTimeout(recon);
      clearTimeout(rotacao);
      if (ws) { try { ws.close(1000); } catch (e) { /* ignore */ } ws = null; }
    };

    const conectar = () => {
      if (fechado) return;
      const url = this.SUPABASE_URL.replace(/^https:\/\//, "wss://") + "/realtime/v1/websocket?apikey=" + encodeURIComponent(this.ANON) + "&vsn=1.0.0";
      try { ws = new WebSocket(url); } catch (e) { onEstado("erro", "WebSocket indisponível nesta TV."); return; }
      ws.onopen = () => {
        if (fechado) { try { ws.close(); } catch (e) { /* ignore */ } return; }
        enviar(topic, "phx_join", { config: { broadcast: { self: false }, private: false } });
        clearInterval(hb);
        hb = setInterval(() => enviar("phoenix", "heartbeat", {}), 25000);
        onEstado("aguardando", "Aguardando conexão pelo celular…");
      };
      ws.onmessage = async (ev) => {
        if (fechado || processando) return;
        let m;
        try { m = JSON.parse(ev.data); } catch (e) { return; }
        if (m.event !== "broadcast" || !m.payload) return;
        const p = m.payload;
        if (p.event !== "sessao" || !p.payload || !p.payload.iv || !p.payload.ct) return;
        processando = true;
        onEstado("entrando", "Conectando sua conta…");
        let ok = false;
        try {
          const texto = await this.decifrar(codigo, p.payload.iv, p.payload.ct);
          const dados = JSON.parse(texto);
          if (dados && dados.refresh_token) {
            ok = await this.trocarRefresh(dados.refresh_token, dados.nome || null);
          }
        } catch (e) { ok = false; }
        enviar(topic, "broadcast", { type: "broadcast", event: "resposta", payload: { ok } });
        await sleep(800);
        if (fechado) return;
        if (ok) {
          fechar();
          onEstado("pronto", this.label());
        } else {
          processando = false;
          onEstado("erro", "Não foi possível entrar. Tente novamente.");
        }
      };
      ws.onclose = () => {
        clearInterval(hb);
        if (!fechado && !processando) {
          recon = setTimeout(conectar, 3000);
        }
      };
      ws.onerror = () => { /* onclose cuida da reconexão */ };
    };

    this.canalDo(codigo).then((canal) => {
      if (fechado) return;
      topic = "realtime:" + canal;
      conectar();
      rotacao = setTimeout(() => {
        if (!fechado && !processando) onEstado("expirado");
      }, 10 * 60 * 1000);
    }).catch(() => {
      if (!fechado) onEstado("erro", "Falha ao preparar o pareamento.");
    });

    return fechar;
  },
};

// ======================= casca (topo com as abas) =======================

const ABAS = [{ id: "canais", t: "Canais" }, { id: "eventos", t: "Eventos" }];

/** Topo com as abas. [abaixo] = zona da tela que recebe o foco quando você desce das abas. */
function casca(ui, abaAtual, abaixo) {
  const top = h("div.top");
  top.appendChild(h("img.logo", { src: "logo.png" }));
  top.appendChild(h("div.brand", { html: "BAD WOLF <b>ESPORTES</b>" }));
  const abas = h("div.abas", { "data-zona": "abas", "data-eixo": "h", "data-baixo": abaixo });
  ABAS.forEach((a) => {
    const el = h("div.tab.foc" + (a.id === abaAtual ? ".sel" : ""), { text: a.t, "data-aba": a.id });
    el._ok = () => { if (a.id !== abaAtual) trocarAba(a.id); else Foco.mover("down"); };
    if (a.id === abaAtual) abas._ultimo = el;
    abas.appendChild(el);
  });
  const on = Conta.conectado();
  const contaEl = h("div.tab.tab-conta.foc" + (on ? ".on" : "") + (abaAtual === "conta" ? ".sel" : ""), { "data-aba": "conta" }, [
    h("span.dot"),
    h("span", { text: Conta.label() }),
  ]);
  contaEl._ok = () => { if (abaAtual !== "conta") trocarAba("conta"); else Foco.mover("down"); };
  if (abaAtual === "conta") abas._ultimo = contaEl;
  abas.appendChild(contaEl);
  top.appendChild(abas);
  const rel = h("div.clock", { text: hora(Date.now()) });
  top.appendChild(rel);
  ui.appendChild(top);
  clearInterval(casca._t);
  casca._t = setInterval(() => { rel.textContent = hora(Date.now()); }, 15000);
  return top;
}

function trocarAba(id) {
  Telas.limpar();
  Telas.abrir(id === "eventos" ? TelaEventos() : id === "conta" ? TelaConta() : TelaCanais());
}

/** Voltar dentro de uma aba: vai para as abas; nas abas, Canais; em Canais, pergunta se quer sair. */
function voltarParaAbas(aba) {
  if (Foco.atual && Foco.atual.classList.contains("tab")) {
    if (aba !== "canais") { trocarAba("canais"); return true; }
    return false;
  }
  const t = $(".tab.sel");
  if (t) { Foco.definir(t); return true; }
  return false;
}

// ======================= TelaConta (Conta W4LLE + QR Pairing) =======================

function TelaConta() {
  let pararWs = null;
  let caixaUi = null;

  const limpar = () => {
    if (pararWs) { pararWs(); pararWs = null; }
  };

  const render = () => {
    limpar();
    caixaUi.innerHTML = "";
    casca(caixaUi, "conta", "conta-acoes");
    const tela = h("div.screen.conta");
    const card = h("div.conta-card");
    const u = Conta.usuario();
    let alvo = null;

    if (u) {
      const inicial = (u.nome || "W").trim().charAt(0).toUpperCase();
      const btnSair = h("div.btn.btn-danger.foc", { text: "Sair da conta" });
      btnSair._ok = () => {
        dialogo("Sair da conta W4LLE?", "Seus favoritos salvos nesta TV continuam mantidos.", [
          { t: "Cancelar", f: () => {} },
          { t: "Sair", f: () => { Conta.sair(); toast("Você saiu da conta"); render(); } },
        ]);
      };
      card.appendChild(h("div.conta-perfil", null, [
        h("div.conta-avatar", { text: inicial }),
        h("div.conta-infocol", null, [
          h("div.conta-badge.ok", { text: "● Conectado" }),
          h("h2", { text: u.nome }),
          h("div.conta-sub", { text: "Conta W4LLE conectada nesta TV LG." }),
          h("div.btns", { "data-zona": "conta-acoes", "data-eixo": "h", "data-cima": "abas" }, [btnSair]),
        ]),
      ]));
      alvo = btnSair;
    } else {
      const codigo = Conta.novoCodigo();
      const codeStr = Conta.mostrarCodigo(codigo);
      const qrBox = h("div.conta-qrbox");
      const qrImg = h("img", { src: Conta.qrUrl(codigo), alt: "QR Code " + codeStr });
      qrImg.onerror = () => {
        qrBox.innerHTML = "";
        qrBox.appendChild(h("div.qr-fallback", { text: "Acesse badwolf.pages.dev/entrar e digite o código abaixo" }));
      };
      qrBox.appendChild(qrImg);

      const statusText = h("span", { text: "Preparando conexão…" });
      const statusEl = h("div.conta-status", null, [h("span.pulse"), statusText]);

      const btnNovo = h("div.btn.foc", { text: "Gerar novo código" });
      btnNovo._ok = () => render();

      card.appendChild(h("div.conta-grid", null, [
        h("div.conta-qrcol", null, [
          qrBox,
          h("div.conta-code", { text: codeStr }),
          h("div.conta-url", { text: "badwolf.pages.dev/entrar" }),
        ]),
        h("div.conta-infocol", null, [
          h("div.conta-badge", { text: "Conta W4LLE · Opcional" }),
          h("h2", { text: "Conectar sua conta na TV" }),
          h("div.conta-sub", { text: "Entre ou crie sua conta grátis usando o celular. Sem digitar senha na TV." }),
          h("div.conta-steps", null, [
            h("div.conta-step", null, [h("span.conta-step-num", { text: "1" }), h("span", { html: "Aponte a câmera do celular para o <b>QR Code</b>" })]),
            h("div.conta-step", null, [h("span.conta-step-num", { text: "2" }), h("span", { html: "Entre ou <b>crie sua conta grátis</b> na página" })]),
            h("div.conta-step", null, [h("span.conta-step-num", { text: "3" }), h("span", { html: "A TV conecta automaticamente na hora" })]),
          ]),
          statusEl,
          h("div.btns", { "data-zona": "conta-acoes", "data-eixo": "h", "data-cima": "abas" }, [btnNovo]),
        ]),
      ]));

      pararWs = Conta.iniciarPareamento(codigo, (estado, msg) => {
        if (estado === "expirado") { render(); return; }
        statusEl.className = "conta-status" + (estado === "erro" ? " err" : estado === "pronto" ? " ok" : "");
        if (estado === "pronto") {
          statusText.textContent = "Conectado como " + msg + "!";
          toast("TV conectada como " + msg + "!");
          render();
        } else if (msg) {
          statusText.textContent = msg;
        }
      });
      alvo = btnNovo;
    }

    tela.appendChild(card);
    caixaUi.appendChild(tela);
    if (alvo) Foco.definir(alvo);
    return alvo;
  };

  return {
    montar(ui) {
      caixaUi = ui;
      return render();
    },
    esconder() { limpar(); },
    sair() { limpar(); },
    voltar() { return voltarParaAbas("conta"); },
  };
}

/** Linha de canal (lista do ao vivo e "onde assistir"). */
function linhaCanal(c, num, extra) {
  extra = extra || {};
  const prog = Cat.agora(c, Date.now());
  const el = h("div.ch.foc" + (Player.canalAtual && Player.canalAtual.id === c.id && Player.ativo ? ".playing" : ""), { "data-id": c.id }, [
    num != null ? h("div.num", { text: num }) : null,
    h("div.lg", { bg: c.l || null }, c.l ? null : (c.b || c.n).slice(0, 14)),
    h("div.info", null, [
      h("div.nm", null, [extra.nome || c.n, Usuario.ehFav("c:" + c.id) ? h("span.fav", { text: "★" }) : null]),
      h("div.now", { text: prog ? hora(prog.ini) + " – " + hora(prog.fim) + "   " + prog.t : "" }),
      prog ? h("div.prog", null, h("i", { style: { width: Math.round(100 * (Date.now() - prog.ini) / Math.max(1, prog.fim - prog.ini)) + "%" } })) : null,
    ]),
  ]);
  el._canal = c;
  el._longo = () => { const on = Usuario.trocarFav("c:" + c.id); toast(on ? "Canal favoritado" : "Tirado dos favoritos"); };
  return el;
}

// ======================= Placar (jogos de futebol com escudo e placar) =======================

/**
 * Jogos dos campeonatos importantes pela API pública de placares da ESPN (sem chave; não é oficial e pode
 * mudar). Traz times com escudo, placar, minuto/estado e o logo do campeonato. "Onde assistir" vem do nosso
 * guia: os canais de esporte cujo programa tem os dois times no título.
 */
const Placar = {
  API: "https://site.api.espn.com/apis/site/v2/sports/soccer/",
  LIGAS: [
    { id: "bra.1", nome: "Brasileirão" },
    { id: "conmebol.libertadores", nome: "Libertadores" },
    { id: "conmebol.sudamericana", nome: "Sul-Americana" },
    { id: "bra.copa_do_brazil", nome: "Copa do Brasil" },
    { id: "uefa.champions", nome: "Champions League" },
    { id: "eng.1", nome: "Premier League" },
    { id: "esp.1", nome: "La Liga" },
    { id: "ita.1", nome: "Serie A (Itália)" },
    { id: "ger.1", nome: "Bundesliga" },
    { id: "fra.1", nome: "Ligue 1" },
    { id: "uefa.europa", nome: "Europa League" },
    { id: "bra.2", nome: "Brasileirão Série B" },
  ],
  jogos: {}, // id -> jogo
  ultimaCompleta: 0,
  carregando: null,

  dataEspn(d) { return d.getFullYear() + pad2(d.getMonth() + 1) + pad2(d.getDate()); },

  /** Dias já buscados (yyyymmdd -> quando), para não pedir de novo a cada abertura da aba. */
  diasBuscados: {},

  /** Busca os jogos de alguns dias (0 = hoje, 1 = amanhã, -1 = ontem) em todos os campeonatos. */
  async buscarDias(deslocamentos) {
    const agora = Date.now();
    const datas = deslocamentos.map((n) => this.dataEspn(new Date(agora + n * 86400000)))
      .filter((d) => !this.diasBuscados[d] || agora - this.diasBuscados[d] > 30 * 60000);
    if (!datas.length) return;
    datas.forEach((d) => { this.diasBuscados[d] = agora; });
    await Promise.all(this.LIGAS.map((l) => this.buscarLiga(l, false, datas)));
  },

  async buscarLiga(liga, comHoje, datas) {
    const urls = datas ? [] : [this.API + liga.id + "/scoreboard"];
    if (comHoje) urls.push(this.API + liga.id + "/scoreboard?dates=" + this.dataEspn(new Date()));
    (datas || []).forEach((d) => urls.push(this.API + liga.id + "/scoreboard?dates=" + d));
    const respostas = await Promise.all(urls.map((u) => comTempo(fetch(u), 12000).then((r) => (r.ok ? r.json() : null)).catch(() => null)));
    respostas.forEach((d) => {
      if (!d) return;
      const logoLiga = d.leagues && d.leagues[0] && d.leagues[0].logos && d.leagues[0].logos[0] ? d.leagues[0].logos[0].href : null;
      (d.events || []).forEach((e) => {
        const c = e.competitions && e.competitions[0];
        if (!c) return;
        const time = (lado) => {
          const x = (c.competitors || []).filter((t) => t.homeAway === lado)[0] || {};
          const t = x.team || {};
          return { nome: t.shortDisplayName || t.displayName || "?", completo: t.displayName || t.shortDisplayName || "", logo: t.logo || null, placar: x.score };
        };
        const st = (e.status && e.status.type) || {};
        this.jogos[e.id] = {
          id: e.id, liga: liga.nome, ligaId: liga.id, logoLiga,
          ini: new Date(e.date).getTime(),
          casa: time("home"), fora: time("away"),
          estado: st.state, // pre | in | post
          detalhe: (e.status && e.status.displayClock && st.state === "in") ? e.status.displayClock : st.shortDetail || "",
          encerrado: !!st.completed,
          atualizado: Date.now(),
        };
      });
    });
  },

  /** Completa a cada 10 min; no meio tempo, só os campeonatos com jogo rolando ou começando. */
  async atualizar(forcar) {
    if (this.carregando) return this.carregando;
    const agora = Date.now();
    const completa = forcar || agora - this.ultimaCompleta > 10 * 60000;
    const ligas = completa ? this.LIGAS : this.LIGAS.filter((l) => Object.keys(this.jogos).some((id) => {
      const j = this.jogos[id];
      return j.ligaId === l.id && (j.estado === "in" || (j.estado === "pre" && j.ini - agora < 15 * 60000));
    }));
    this.carregando = Promise.all(ligas.map((l) => this.buscarLiga(l, completa))).then(() => {
      if (completa) this.ultimaCompleta = agora;
      this.carregando = null;
    });
    return this.carregando;
  },

  /**
   * Jogos de uma aba: "hoje" = ao vivo + os que ainda vão começar hoje; "proximos" = de amanhã até 7 dias;
   * "encerrados" = terminados nas últimas 48 h (o mais recente primeiro).
   */
  lista(aba) {
    const agora = Date.now();
    const fimDeHoje = new Date(); fimDeHoje.setHours(23, 59, 59, 999);
    return Object.keys(this.jogos).map((id) => this.jogos[id]).filter((j) => {
      if (aba === "encerrados") return j.estado === "post" && agora - j.ini < 48 * 3600 * 1000;
      if (aba === "proximos") return j.estado === "pre" && j.ini > fimDeHoje.getTime() && j.ini - agora < 7 * 86400000;
      return j.estado === "in" || (j.estado === "pre" && j.ini <= fimDeHoje.getTime() && j.ini > agora - 3 * 3600 * 1000);
    }).sort((a, b) => {
      const ordem = { in: 0, pre: 1, post: 2 };
      return (ordem[a.estado] - ordem[b.estado]) || (a.estado === "post" ? b.ini - a.ini : a.ini - b.ini) ||
        (this.LIGAS.findIndex((l) => l.id === a.ligaId) - this.LIGAS.findIndex((l) => l.id === b.ligaId));
    });
  },

  // ---- onde assistir: o guia dos canais de esporte ----
  PALAVRAS_FRACAS: { fc: 1, sc: 1, ec: 1, cf: 1, ac: 1, cr: 1, afc: 1, club: 1, clube: 1, de: 1, da: 1, do: 1, del: 1, la: 1, real: 1, sport: 1, sporting: 1, futebol: 1, city: 1, united: 1, red: 1, bull: 1, atletico: 0 },
  chaves(nome) {
    return semAcento(nome).replace(/[^a-z0-9 ]+/g, " ").split(/\s+/).filter((w) => w.length >= 4 && !this.PALAVRAS_FRACAS[w]);
  },
  /** Canais de esporte que passam o jogo (os dois times no título do programa, no horário do jogo). */
  onde(jogo) {
    const k1 = this.chaves(jogo.casa.completo + " " + jogo.casa.nome), k2 = this.chaves(jogo.fora.completo + " " + jogo.fora.nome);
    if (!k1.length || !k2.length) return [];
    const tem = (t, ks) => ks.some((k) => t.indexOf(k) >= 0);
    const porBase = {};
    Esporte.esportivos.forEach((canal) => {
      const p = Cat.programas(canal).filter((x) => x.ini <= jogo.ini + 40 * 60000 && x.fim >= jogo.ini - 10 * 60000 &&
        !/^\s*(vt|reprise)\b/i.test(x.t))[0];
      if (!p) return;
      const t = semAcento(p.t);
      if (tem(t, k1) && tem(t, k2)) {
        const base = canal.b || canal.n;
        (porBase[base] || (porBase[base] = [])).push(canal);
      }
    });
    const ordemQ = { FHD: 0, HD: 1, SD: 2, "4K": 3 };
    const qual = (c) => { const m = / (4K|FHD|HD|SD)$/.exec(c.n); return m ? m[1] : ""; };
    return Object.keys(porBase).map((base) => ({
      base,
      opcoes: porBase[base].sort((a, b) => (ordemQ[qual(a)] || 0) - (ordemQ[qual(b)] || 0)).map((c) => ({ canal: c, q: qual(c) })),
    }));
  },
  /** Transmissões oficiais (TV + streaming com logo) vindas de transmissoes.json (ge.globo). */
  transmissoes(jogo) {
    const lista = Cat.transmissoes || [];
    if (!lista.length || !jogo) return [];
    const k1 = this.chaves(jogo.casa.completo + " " + jogo.casa.nome);
    const k2 = this.chaves(jogo.fora.completo + " " + jogo.fora.nome);
    if (!k1.length || !k2.length) return [];
    const tem = (t, ks) => ks.some((k) => t.indexOf(k) >= 0);
    const diaMs = (s) => {
      const p = (s || "").split("-");
      return p.length === 3 ? new Date(+p[0], +p[1] - 1, +p[2], 12, 0, 0).getTime() : 0;
    };
    const achado = lista.find((it) => {
      if (Math.abs(diaMs(it.d) - jogo.ini) > 36 * 3600 * 1000) return false;
      const c = semAcento(it.c), f = semAcento(it.f);
      return (tem(c, k1) && tem(f, k2)) || (tem(c, k2) && tem(f, k1));
    });
    return achado && Array.isArray(achado.t) ? achado.t : [];
  },
  /** Canais IPTV da grade que combinam com as emissoras oficiais (quando o EPG não listou o jogo). */
  canaisPorEmissora(trans) {
    if (!trans || !trans.length) return [];
    const REGRAS = [
      { em: /prime\s*v[ií]deo|amazon/i, ch: /\b(prime\s*video|amazon\s*prime)\b/i },
      { em: /premiere/i, ch: /\bpremiere\b/i },
      { em: /sportv/i, ch: /\bsportv\b/i },
      { em: /\bespn\b/i, ch: /\bespn\b/i },
      { em: /\btnt\b/i, ch: /\btnt(\s*sports)?\b/i },
      { em: /caz[eé]/i, ch: /\bcaz[eé]/i },
      { em: /paramount/i, ch: /\bparamount/i },
      { em: /disney/i, ch: /\bdisney\b/i },
      { em: /hbo\s*max|\bmax\b/i, ch: /\b(hbo\s*max|max\s*\d+)\b/i },
      { em: /\bglobo\b/i, ch: /\bglobo\s*(sp|rj)\b/i },
      { em: /\brecord\b/i, ch: /\brecord\s*(sp|tv)\b/i },
      { em: /\bsbt\b/i, ch: /\bsbt\b/i },
      { em: /\bband\b/i, ch: /\bband\b/i },
    ];
    const porBase = {};
    const ordemQ = { FHD: 0, HD: 1, SD: 2, "4K": 3 };
    const qual = (c) => { const m = / (4K|FHD|HD|SD)$/.exec(c.n); return m ? m[1] : ""; };
    trans.forEach((tr) => {
      const r = REGRAS.find((x) => x.em.test(tr.n || ""));
      if (!r) return;
      (Cat.canais || []).forEach((canal) => {
        const base = canal.b || canal.n;
        if (r.ch.test(semAcento(base))) {
          (porBase[base] || (porBase[base] = [])).push(canal);
        }
      });
    });
    return Object.keys(porBase).slice(0, 6).map((base) => ({
      base,
      opcoes: porBase[base].sort((a, b) => (ordemQ[qual(a)] || 0) - (ordemQ[qual(b)] || 0)).slice(0, 2).map((c) => ({ canal: c, q: qual(c) })),
    }));
  },
};

// ======================= Eventos =======================

function TelaEventos() {
  let lista, listaInner, onde, ondeInner, cabeca, selecionado = null, aviso, filtros;
  const ABAS_EV = [{ id: "hoje", t: "Hoje" }, { id: "proximos", t: "Próximos" }, { id: "encerrados", t: "Encerrados" }];
  let abaEv = "hoje";
  const VAZIO = { hoje: "Nenhum jogo importante hoje.", proximos: "Nenhum jogo importante nos próximos dias.", encerrados: "Nenhum jogo encerrado nas últimas 48 h." };
  const placar = (j) => (j.estado === "pre" ? "x" : (j.casa.placar || "0") + "  x  " + (j.fora.placar || "0"));
  const estadoJogo = (j) => {
    if (j.estado === "in") return "AO VIVO · " + (j.detalhe || "");
    if (j.estado === "post") return "Encerrado";
    const d = new Date(j.ini), hoje = new Date();
    const dia = d.getDate() === hoje.getDate() ? "Hoje" : "Amanhã";
    return dia + " · " + hora(j.ini);
  };
  const quandoGuia = (ev) => (ev.reprise ? "Reprise · " + hora(ev.ini) : ev.aoVivo ? "AO VIVO · começou " + hora(ev.ini) : hora(ev.ini) + " · até " + hora(ev.fim));
  const escudo = (logo, nome) => h("div.escudo", { bg: logo || null }, logo ? null : (nome || "?").slice(0, 3).toUpperCase());

  /** Painel da direita: o jogo (escudos, placar) e onde assistir. */
  const desenharOnde = (item) => {
    selecionado = item;
    ondeInner.innerHTML = "";
    onde._ultimo = null;
    cabeca.innerHTML = "";
    if (!item) return;
    let opcoes;
    let trans = [];
    let sugeridos = false;
    if (item.jogo) {
      const j = item.jogo;
      cabeca.appendChild(h("div.liga", null, [j.logoLiga ? h("span.ligalogo", { bg: j.logoLiga }) : null, j.liga]));
      cabeca.appendChild(h("div.placar-grande", null, [
        h("div.time", null, [escudo(j.casa.logo, j.casa.nome), h("div.tnome", { text: j.casa.nome })]),
        h("div.placar-num", { text: j.estado === "pre" ? hora(j.ini) : (j.casa.placar || "0") + " x " + (j.fora.placar || "0") }),
        h("div.time", null, [escudo(j.fora.logo, j.fora.nome), h("div.tnome", { text: j.fora.nome })]),
      ]));
      cabeca.appendChild(h("div.sub" + (j.estado === "in" ? ".vivo" : ""), { text: estadoJogo(j) }));
      trans = Placar.transmissoes(j);
      if (trans.length) {
        cabeca.appendChild(h("div.trans-box", null, [
          h("div.trans-tit", { text: "Transmissão oficial" }),
          h("div.trans-lista", null, trans.map((tr) => h("div.trans-chip", null, [
            tr.i ? h("span.trans-logo", { bg: tr.i }) : null,
            h("span.trans-nome", { text: tr.n }),
          ]))),
        ]));
      }
      opcoes = Placar.onde(j);
      if (!opcoes.length && trans.length) {
        opcoes = Placar.canaisPorEmissora(trans);
        if (opcoes.length) sugeridos = true;
      }
    } else {
      const ev = item.ev;
      if (ev.liga) cabeca.appendChild(h("div.liga", { text: ev.liga }));
      cabeca.appendChild(h("h3", { text: ev.jogo }));
      cabeca.appendChild(h("div.sub", { text: quandoGuia(ev) }));
      opcoes = ev.onde;
    }
    if (!opcoes.length) {
      const longe = item.jogo && item.jogo.estado === "pre" && item.jogo.ini - Date.now() > 20 * 3600 * 1000;
      const msg = trans.length
        ? "Transmissão oficial exibida acima (sem canal correspondente no guia IPTV)."
        : longe ? "Onde assistir aparece aqui perto do jogo, quando o guia dos canais trouxer a programação do dia."
        : item.jogo && item.jogo.estado === "post" ? "Jogo encerrado."
        : "Não achei este jogo no guia dos canais. Procure na aba Canais.";
      cabeca.appendChild(h("div.hint", { text: msg }));
      return;
    }
    cabeca.appendChild(h("div.hint", { text: sugeridos ? "Canais relacionados · OK para abrir" : "Onde assistir · OK no canal para abrir" }));
    const fila = [];
    opcoes.forEach((o) => o.opcoes.forEach((op) => fila.push(op.canal)));
    opcoes.forEach((o) => {
      ondeInner.appendChild(h("div.onde-canal", { text: o.base }));
      o.opcoes.forEach((op) => {
        const el = linhaCanal(op.canal, null, { nome: o.base + (op.q ? "  " + op.q : "") });
        el._ok = () => Player.abrirCanal(fila, fila.indexOf(op.canal), null);
        ondeInner.appendChild(el);
      });
    });
  };

  const linhaJogo = (j) => {
    const trans = Placar.transmissoes(j);
    const emissoras = trans.map((tr) => tr.n).slice(0, 2).join(" · ");
    const el = h("div.evento.jogo.foc" + (j.estado === "in" ? ".vivo" : ""), { "data-chave": "j" + j.id }, [
      h("div.ev-hora", { text: j.estado === "in" ? (j.detalhe || "AO VIVO") : j.estado === "post" ? "FIM" : hora(j.ini) }),
      h("div.ev-times", null, [
        h("div.lado", null, [escudo(j.casa.logo, j.casa.nome), h("span", { text: j.casa.nome })]),
        h("div.ev-placar", { text: placar(j) }),
        h("div.lado.fora", null, [h("span", { text: j.fora.nome }), escudo(j.fora.logo, j.fora.nome)]),
      ]),
      h("div.ev-meta", null, [
        h("div.ev-liga", { text: j.liga }),
        emissoras ? h("div.ev-onde", { text: emissoras }) : null,
      ]),
    ]);
    el._item = { jogo: j };
    return el;
  };
  const linhaGuia = (ev) => {
    const el = h("div.evento.foc" + (ev.aoVivo ? ".vivo" : ""), { "data-chave": "g" + ev.id }, [
      h("div.ev-hora", { text: ev.aoVivo ? "AO VIVO" : hora(ev.ini) }),
      h("div.ev-info", null, [
        h("div.ev-jogo", { text: ev.jogo }),
        h("div.ev-sub", { text: [ev.liga, ev.onde.map((o) => o.base).join(", ")].filter(Boolean).join("  ·  ") }),
      ]),
    ]);
    el._item = { ev };
    return el;
  };

  const desenharLista = () => {
    const chaveFoco = Foco.atual && Foco.atual.getAttribute && Foco.atual.getAttribute("data-chave");
    // guarda ANTES de redesenhar: depois o item focado já saiu da tela
    const estavaNaLista = !!(Foco.atual && Foco.atual.closest("[data-zona='eventos']"));
    listaInner.innerHTML = "";
    const jogos = Placar.lista(abaEv);
    const secao = (t) => listaInner.appendChild(h("div.secao", { text: t }));
    if (abaEv === "hoje") {
      const vivos = jogos.filter((j) => j.estado === "in"), depois = jogos.filter((j) => j.estado === "pre");
      if (vivos.length) { secao("Ao vivo agora"); vivos.forEach((j) => listaInner.appendChild(linhaJogo(j))); }
      if (depois.length) { secao("Mais tarde"); depois.forEach((j) => listaInner.appendChild(linhaJogo(j))); }
    } else {
      // por dia: "Amanhã", "Quarta, 26/11"...
      const DIAS = ["Domingo", "Segunda", "Terça", "Quarta", "Quinta", "Sexta", "Sábado"];
      let dia = null;
      jogos.forEach((j) => {
        const d = new Date(j.ini), hoje = new Date();
        const ontem = new Date(Date.now() - 86400000), amanha = new Date(Date.now() + 86400000);
        const igual = (x, y) => x.getDate() === y.getDate() && x.getMonth() === y.getMonth();
        const nome = igual(d, hoje) ? "Hoje" : igual(d, amanha) ? "Amanhã" : igual(d, ontem) ? "Ontem" : DIAS[d.getDay()] + ", " + pad2(d.getDate()) + "/" + pad2(d.getMonth() + 1);
        if (nome !== dia) { dia = nome; secao(nome); }
        listaInner.appendChild(linhaJogo(j));
      });
    }
    aviso.textContent = jogos.length ? "" : VAZIO[abaEv];
    $$(".evento", listaInner).forEach((el) => {
      el._aoFocar = () => { clearTimeout(el._t); el._t = setTimeout(() => { if (Foco.atual === el) desenharOnde(el._item); }, 120); };
      el._ok = () => { if (!selecionado || selecionado !== el._item) desenharOnde(el._item); Foco.entrar(onde); };
    });
    // continua no mesmo jogo depois de atualizar o placar
    const volta = chaveFoco && $$(".evento", listaInner).filter((e) => e.getAttribute("data-chave") === chaveFoco)[0];
    if (estavaNaLista) {
      if (volta) Foco.definir(volta);
      else { lista._ultimo = null; if (!Foco.entrar(lista)) Foco.entrar(filtros); }
    }
    return $(".evento", listaInner);
  };

  let tela0 = null, t0 = 0;
  return tela0 = {
    montar(ui) {
      Fundo.cor("radial-gradient(1100px 700px at 1650px 330px, #0a2a33, #000)");
      casca(ui, "eventos", "filtros");
      const tela = h("div.screen");
      const cols = h("div.cols");
      const esquerda = h("div.evcol");
      filtros = h("div.evabas", { "data-zona": "filtros", "data-eixo": "h", "data-cima": "abas", "data-baixo": "eventos", "data-dir": "onde" });
      ABAS_EV.forEach((a) => {
        const el = h("div.season.foc" + (a.id === abaEv ? ".sel" : ""), { text: a.t });
        const abrir = () => {
          if (abaEv === a.id) return;
          abaEv = a.id;
          $$(".season", filtros).forEach((x) => x.classList.remove("sel"));
          el.classList.add("sel");
          lista.scrollTop = 0; lista._ultimo = null;
          const p = desenharLista();
          desenharOnde(p ? p._item : null);
          // próximos dias e ontem: busca só quando a aba abre
          const dias = a.id === "proximos" ? [1, 2, 3, 4, 5, 6] : a.id === "encerrados" ? [-1] : [];
          if (dias.length) {
            if (!Placar.lista(a.id).length) aviso.textContent = "Buscando os jogos…";
            Placar.buscarDias(dias).then(() => {
              if (abaEv !== a.id || Telas.topo() !== tela0) return;
              const q = desenharLista();
              if (!selecionado && q) desenharOnde(q._item);
            });
          }
        };
        el._aoFocar = () => { clearTimeout(el._t); el._t = setTimeout(() => { if (Foco.atual === el) abrir(); }, 250); };
        el._ok = () => { abrir(); Foco.entrar(lista); };
        if (a.id === abaEv) filtros._ultimo = el;
        filtros.appendChild(el);
      });
      lista = h("div.evlist", { "data-rola": "y", "data-folga": "80", "data-zona": "eventos", "data-eixo": "v", "data-cima": "filtros", "data-dir": "onde" });
      listaInner = h("div.evlist-inner");
      aviso = h("div.empty");
      lista.appendChild(aviso);
      lista.appendChild(listaInner);
      esquerda.appendChild(filtros); esquerda.appendChild(lista);
      const painel = h("div.evpanel");
      cabeca = h("div.evcabeca");
      onde = h("div.onde", { "data-rola": "y", "data-folga": "60", "data-zona": "onde", "data-eixo": "v", "data-esq": "eventos", "data-cima": "abas" });
      ondeInner = h("div.onde-inner");
      onde.appendChild(ondeInner);
      painel.appendChild(cabeca); painel.appendChild(onde);
      cols.appendChild(esquerda); cols.appendChild(painel);
      tela.appendChild(cols);
      ui.appendChild(tela);
      // primeiro o que já tem (placares guardados); os de agora chegam em seguida
      let pri = desenharLista();
      if (!Placar.lista("hoje").length) aviso.textContent = "Buscando os jogos de hoje…";
      desenharOnde(pri ? pri._item : null);
      t0 = Date.now();
      Placar.atualizar(true).then(() => {
        if (Telas.topo() !== tela0) return;
        const p = desenharLista();
        if (!selecionado && p) desenharOnde(p._item);
        // abriu a aba e os jogos chegaram logo depois: o foco desce para o primeiro jogo (se você não mexeu)
        if (p && Foco.atual && Foco.atual.classList.contains("tab") && Date.now() - t0 < 5000) Foco.definir(p);
      });
      // placar ao vivo: a cada 1 min (só os campeonatos com jogo rolando; a lista toda a cada 10 min)
      this._t = setInterval(() => {
        if (Telas.topo() !== tela0 || Player.modo === "cheia") return;
        Placar.atualizar(false).then(() => {
          if (Telas.topo() !== tela0) return;
          desenharLista();
          if (selecionado && selecionado.jogo) { const j = Placar.jogos[selecionado.jogo.id]; if (j && !(Foco.atual && Foco.atual.closest("[data-zona='onde']"))) desenharOnde({ jogo: j }); }
        });
      }, 60000);
      return pri || $(".tab.sel", ui);
    },
    voltar() {
      if (Foco.atual && Foco.atual.closest("[data-zona='onde']")) { Foco.entrar(lista); return true; }
      if (Foco.atual && Foco.atual.closest("[data-zona='eventos']")) { Foco.entrar(filtros); return true; }
      return voltarParaAbas("eventos");
    },
    sair() { clearInterval(this._t); },
  };
}

// ======================= Canais =======================

function TelaCanais() {
  const cats = [{ id: "favoritos", nome: "Favoritos", canais: null }].concat(Esporte.cats);
  let catSel = store("cat_esporte");
  if (!cats.some((c) => c.id === catSel)) catSel = (Esporte.cats[0] || {}).id;
  let canais = [], lista, listaInner, montados = 0, painel, focado = null, previa = null;
  const LOTE = 60;
  const canaisDe = (id) => {
    if (id === "favoritos") return Usuario.favs().map((k) => (k.indexOf("c:") === 0 ? Cat.canalPorId[+k.slice(2)] : null)).filter(Boolean);
    const c = cats.filter((x) => x.id === id)[0];
    return c ? c.canais : [];
  };
  const linha = (c, i) => {
    const el = linhaCanal(c, i + 1);
    el._aoFocar = () => { focado = c; desenharPainel(); };
    el._ok = () => {
      if (previa && previa.id === c.id) { Player.abrirCanal(canais, i, catSel); return; }
      previa = c;
      $$(".ch.playing", listaInner).forEach((x) => x.classList.remove("playing"));
      el.classList.add("playing");
      desenharPainel();
      Player.previa(c, $(".pbox", painel), canais, i);
    };
    return el;
  };
  const montarMais = () => {
    const fim = Math.min(canais.length, montados + LOTE);
    for (; montados < fim; montados++) listaInner.appendChild(linha(canais[montados], montados));
  };
  const mostrarCat = (id) => {
    catSel = id; store("cat_esporte", id);
    canais = canaisDe(id);
    montados = 0; listaInner.innerHTML = ""; lista.scrollTop = 0; lista._ultimo = null;
    if (!canais.length) listaInner.appendChild(h("div.empty", { text: id === "favoritos" ? "Segure OK num canal para favoritar." : "Nenhum canal aqui." }));
    montarMais();
  };
  const desenharPainel = () => {
    const c = previa || focado;
    const box = $(".pbox", painel);
    const resto = $(".pinfo", painel);
    if (!c) { resto.innerHTML = ""; return; }
    $(".lg", box).style.backgroundImage = c.l ? "url(\"" + c.l + "\")" : "";
    box.classList.toggle("video", !!previa && Player.ativo && Player.modo === "previa");
    resto.innerHTML = "";
    resto.appendChild(h("div.hint", { text: !previa ? "OK: ver a prévia  ·  segure OK: favoritar" : (focado && focado.id === previa.id ? "OK de novo: tela cheia" : "OK: trocar a prévia para este canal") }));
    const ps = Cat.programas(c), t = Date.now();
    const cur = ps.filter((p) => p.ini <= t && t < p.fim)[0];
    resto.appendChild(h("h3", { text: cur ? cur.t : c.n }));
    if (cur) {
      const falta = Math.max(0, Math.round((cur.fim - t) / 60000));
      resto.appendChild(h("div.sub", { text: c.n + "  ·  " + hora(cur.ini) + "–" + hora(cur.fim) + "  ·  falta " + (falta >= 60 ? Math.floor(falta / 60) + " h " + (falta % 60) + " min" : falta + " min") }));
      resto.appendChild(h("div.pline", null, h("i", { style: { width: Math.round(100 * (t - cur.ini) / Math.max(1, cur.fim - cur.ini)) + "%" } })));
      if (cur.d) resto.appendChild(h("div.desc", { text: cur.d }));
    } else resto.appendChild(h("div.sub", { text: "Sem guia de programação" }));
    const prox = ps.filter((p) => p.ini > t).slice(0, 3);
    if (prox.length) resto.appendChild(h("div.next", null, [h("b", { text: "A SEGUIR" })].concat(prox.map((p) => h("div", null, [h("span", { text: hora(p.ini) }), p.t])))));
  };
  return {
    montar(ui) {
      Fundo.cor("radial-gradient(1100px 700px at 1650px 330px, #0a2a33, #000)");
      casca(ui, "canais", "cats");
      const tela = h("div.screen");
      const cols = h("div.cols");
      const grupos = h("div.groups", { "data-rola": "y", "data-zona": "cats", "data-eixo": "v", "data-cima": "abas", "data-dir": "canais" });
      const gi = h("div.groups-inner");
      let focoCat = null;
      cats.forEach((c) => {
        const n = c.id === "favoritos" ? canaisDe("favoritos").length : c.canais.length;
        const el = h("div.group.foc" + (c.id === catSel ? ".sel" : ""), null, [h("span", { text: c.nome }), h("span.n", { text: n })]);
        el._aoFocar = () => {
          clearTimeout(el._t);
          el._t = setTimeout(() => {
            if (catSel === c.id || Foco.atual !== el) return;
            $$(".group", gi).forEach((g) => g.classList.remove("sel"));
            el.classList.add("sel");
            mostrarCat(c.id);
          }, 250);
        };
        el._ok = () => {
          if (catSel !== c.id) { $$(".group", gi).forEach((g) => g.classList.remove("sel")); el.classList.add("sel"); mostrarCat(c.id); }
          Foco.entrar(lista);
        };
        if (c.id === catSel) { focoCat = el; grupos._ultimo = el; }
        gi.appendChild(el);
      });
      grupos.appendChild(gi);
      lista = h("div.chlist", { "data-rola": "y", "data-folga": "80", "data-zona": "canais", "data-eixo": "v", "data-cima": "abas", "data-esq": "cats" });
      listaInner = h("div.chlist-inner");
      lista.appendChild(listaInner);
      painel = h("div.panel", null, [
        h("div.pbox", null, [h("div.lg"), h("div.live", { text: "AO VIVO" })]),
        h("div.pinfo"),
      ]);
      cols.appendChild(grupos); cols.appendChild(lista); cols.appendChild(painel);
      tela.appendChild(cols);
      ui.appendChild(tela);
      mostrarCat(catSel);
      return focoCat;
    },
    focou(el) {
      if (!el.classList.contains("ch")) return;
      const i = Array.prototype.indexOf.call(listaInner.children, el);
      if (i >= montados - 15) montarMais();
    },
    atualizarPainel() { desenharPainel(); },
    /** Voltou da tela cheia: a prévia continua no canal que estava tocando (trocado com ▲▼ ou não), sem recarregar. */
    voltou() {
      $$(".ch.playing", listaInner).forEach((x) => x.classList.remove("playing"));
      if (!(Player.canalAtual && Player.ativo)) { previa = null; desenharPainel(); return null; }
      previa = Player.canalAtual;
      const i = canais.indexOf(previa);
      while (i >= montados && montados < canais.length) montarMais();
      const el = $(".ch[data-id='" + previa.id + "']", listaInner);
      if (el) { el.classList.add("playing"); lista._ultimo = el; }
      focado = previa;
      desenharPainel();
      Player.mostrarPrevia($(".pbox", painel));
      return el;
    },
    esconder() { if (Player.modo === "previa") Player.esconderVideo(); },
    voltar() {
      if (Foco.atual && Foco.atual.classList.contains("ch")) {
        if (previa) { previa = null; Player.parar(); $$(".ch.playing", listaInner).forEach((x) => x.classList.remove("playing")); desenharPainel(); return true; }
        const g = $(".group.sel"); if (g) { Foco.definir(g); return true; }
      }
      if (Foco.atual && Foco.atual.classList.contains("group") && previa) { previa = null; Player.parar(); desenharPainel(); }
      return voltarParaAbas("canais");
    },
    sair() { Player.parar(); },
  };
}

/** Fundo da tela. */
const Fundo = {
  cor(c) { const bg = $("#bg"); bg.innerHTML = ""; bg.style.background = c || "#000"; },
};

// ======================= Player =======================

/** http://host/live/u/p/123.ts ou http://host/u/p/123 -> http://host/live/u/p/123.m3u8 (servidor Xtream). */
function xtreamHls(url) {
  const m = /^(https?:\/\/[^/]+)\/(.*?)(\?.*)?$/.exec(url);
  if (!m) return null;
  const segs = m[2].split("/"), q = m[3] || "";
  const last = segs[segs.length - 1];
  if (segs.length === 4 && segs[0] === "live" && /\.ts$/.test(last)) return m[1] + "/live/" + segs[1] + "/" + segs[2] + "/" + last.replace(/\.ts$/, "") + ".m3u8" + q;
  if (segs.length === 3 && /^\d+(\.ts)?$/.test(last)) return m[1] + "/live/" + segs[0] + "/" + segs[1] + "/" + last.replace(/\.ts$/, "") + ".m3u8" + q;
  return null;
}

const Player = {
  video: null, hls: null,
  ativo: false, modo: null, // "previa" | "cheia"
  aoVivo: false,
  canalAtual: null, canais: [], idx: 0, catLive: null,
  item: null, fila: [], filaIdx: 0, serieCard: null,
  tentativas: [], tentIdx: 0, tocouUmaVez: false,
  vigia: null, ultimoTempo: -1, paradoDesde: 0, reconexoes: 0,
  ui: null, esconderT: null,
  seekAlvo: null, seekT: null, seekInicio: 0, seekUltimo: 0,
  zapIdx: -1, zapT: null,
  salvarT: 0,

  iniciar() {
    this.video = $("#video");
    const v = this.video;
    const marcou = () => {
      const t = this.tentativas && this.tentativas[this.tentIdx];
      if (t && t.orig) Saude.marcarOk(t.orig);
    };
    v.addEventListener("playing", () => { this.tocouUmaVez = true; this.jaTocou = true; this.reconexoes = 0; this.carregando(false); this.ultimoTempo = -1; marcou(); });
    // "esperando dados" vem a cada oscilação: só mostra o carregando se o vídeo ficar parado mais de 1,5 s
    v.addEventListener("waiting", () => {
      clearTimeout(this._esperaT);
      const t0 = v.currentTime;
      this._esperaT = setTimeout(() => { if (this.ativo && v.currentTime === t0 && !v.paused) this.carregando(true); }, 1500);
    });
    let ultimo = -1;
    v.addEventListener("timeupdate", () => {
      if (v.currentTime !== ultimo) { ultimo = v.currentTime; clearTimeout(this._esperaT); if (this.tocouUmaVez) this.carregando(false); }
    });
    v.addEventListener("error", () => this.falhou("erro do vídeo " + (v.error ? v.error.code : "")));
    v.addEventListener("ended", () => this.terminou());
    v.addEventListener("timeupdate", () => this.tempo());
    // o player da própria TV nem sempre manda "playing" no .ts ao vivo: conta como aberto quando o vídeo anda
    const abriu = () => { if (this.ativo && !this.tocouUmaVez && (v.currentTime > 0 || this.quadros() > 0)) { this.tocouUmaVez = true; this.jaTocou = true; this.reconexoes = 0; this.carregando(false); marcou(); } };
    v.addEventListener("timeupdate", abriu);
    v.addEventListener("loadeddata", abriu);
    v.addEventListener("canplay", abriu);
  },

  // ---------- fontes ----------
  /** Tentativas de um canal/vídeo: ordenadas por saúde (localStorage) ANTES de cortar em 8; no ao vivo, também a versão .m3u8. */
  montarTentativas(fontes, aoVivo) {
    const base = fontes.filter((f) => !f.h).concat(fontes.filter((f) => f.h));
    const ordem = Saude.ordenar(base).slice(0, 8);
    const temJs = !!(window.Hls && window.Hls.isSupported());
    const out = [];
    ordem.forEach((f) => {
      const ehM3u8 = /\.m3u8(\?|$)/i.test(f.u);
      const m3u8 = ehM3u8 ? f.u : (aoVivo ? xtreamHls(f.u) : null);
      // 1) HLS pelo hls.js, com a lista sempre pedida no link original (o endereço redirecionado vence em
      //    segundos). Lista que chega de outra máquina do servidor fora de sincronia recomeça do zero (ver
      //    carregarUrl). Na LG o .ts direto no player da TV piscava a cada 8-15 s; por isso o HLS vem antes.
      if (m3u8 && temJs) out.push({ url: m3u8, orig: f.u, q: f.q, js: true, tipo: "HLS" });
      // 2) reserva: o link do jeito que veio, no player da TV
      if (!ehM3u8 || !temJs) out.push({ url: f.u, orig: f.u, q: f.q, tipo: ehM3u8 ? "HLS (TV)" : aoVivo ? "TS" : "" });
    });
    return out;
  },

  /** Carregador de listas do hls.js que sempre volta no link original do canal e renova token expirado em sub-playlists. */
  carregadorEstavel(original) {
    const Base = window.Hls.DefaultConfig.loader;
    // todos os endereços para onde o link original já redirecionou (o hls.js continua pedindo o primeiro)
    const redirecionados = {};
    return class extends Base {
      load(context, config, callbacks) {
        if (redirecionados[context.url]) context.url = original;
        const ok = callbacks.onSuccess;
        const err = callbacks.onError;
        const ctx = context;
        const self = this;
        let renovou = false;
        callbacks = Object.assign({}, callbacks, {
          onSuccess(resp, stats, c, net) {
            if (resp && resp.url && resp.url !== ctx.url) redirecionados[resp.url] = 1;
            ok(resp, stats, c, net);
          },
          onError(resp, c, net, stats) {
            const code = resp && resp.code;
            if (!renovou && ctx.url !== original && (code === 401 || code === 403 || code === 404 || code === 410)) {
              renovou = true;
              ctx.url = original;
              self.load(ctx, config, callbacks);
              return;
            }
            if (err) err(resp, c, net, stats);
          },
        });
        super.load(context, config, callbacks);
      }
    };
  },

  carregarUrl(t) {
    const url = t.url;
    const v = this.video;
    if (this.hls) { try { this.hls.destroy(); } catch (e) { /* ignore */ } this.hls = null; }
    v.removeAttribute("src");
    try { v.load(); } catch (e) { /* ignore */ }
    if (t.js) {
      const hls = new window.Hls({
        enableWorker: true, lowLatencyMode: false, backBufferLength: 30, maxBufferLength: 30,
        liveSyncDurationCount: 3, liveMaxLatencyDurationCount: 10, manifestLoadingMaxRetry: 4, levelLoadingMaxRetry: 6, fragLoadingMaxRetry: 6,
        pLoader: this.carregadorEstavel(url),
      });
      hls.on(window.Hls.Events.ERROR, (_, d) => {
        if (!d) return;
        const code = d.response && d.response.code ? d.response.code : 0;
        // lista que veio de outra máquina do servidor, fora de sincronia: recomeça do zero (sem misturar os pedaços)
        if (d.details === window.Hls.ErrorDetails.LEVEL_PARSING_ERROR && this.hls === hls) {
          const agora = Date.now();
          this._trocasLista = (this._trocasLista || []).filter((x) => agora - x < 60000).concat(agora);
          if (this._trocasLista.length <= 5) { console.log("player: lista de outro servidor, recomeçando"); this.carregarUrl(t); return; }
          this.falhou("lista instável", code);
          return;
        }
        if (!d.fatal) return;
        // erro de rede/mídia no meio: o hls.js tenta se recuperar antes de trocar de fonte
        if (d.type === window.Hls.ErrorTypes.MEDIA_ERROR && !this._recuperouMidia) { this._recuperouMidia = true; hls.recoverMediaError(); return; }
        this.falhou("hls " + d.details, code);
      });
      this._recuperouMidia = false;
      hls.loadSource(url);
      hls.attachMedia(v);
      this.hls = hls;
    } else {
      v.src = url;
    }
    const p = v.play();
    if (p && p.catch) p.catch(() => {});
  },

  tentar() {
    clearTimeout(this._abrirT);
    clearTimeout(this._reconT);
    if (this.tentIdx >= this.tentativas.length) { this.semFonte(); return; }
    const t = this.tentativas[this.tentIdx];
    this.tocouUmaVez = false;
    this.jaTocou = false;
    this._limitRetried = false;
    this.carregando(true);
    this.carregarUrl(t);
    const minha = this.tentIdx;
    // não abriu a tempo: próxima fonte
    this._abrirT = setTimeout(() => { if (this.tentIdx === minha && !this.tocouUmaVez) this.falhou("demorou"); }, this.aoVivo ? 12000 : 25000);
  },

  falhou(motivo, httpCode) {
    if (!this.ativo) return;
    clearTimeout(this._abrirT);
    clearTimeout(this._reconT);
    const t = this.tentativas[this.tentIdx];
    const maxRec = this.aoVivo ? 6 : 2;
    // HTTP 401/403 logo ao abrir ou reconectar: aguarda 2,5s para o painel liberar a conexão anterior (1 tela)
    if (this.aoVivo && (httpCode === 401 || httpCode === 403) && !this._limitRetried) {
      this._limitRetried = true;
      const minha = this.tentIdx;
      this.carregando(true);
      this._reconT = setTimeout(() => { if (this.ativo && this.tentIdx === minha) this.carregarUrl(t); }, 2500);
      return;
    }
    if (this.jaTocou && this.reconexoes < maxRec) {
      // estava tocando e caiu: reabre a mesma fonte com backoff progressivo (1s -> 2s -> 3s -> 4s -> 5s)
      this.reconexoes++;
      const espera = (httpCode === 401 || httpCode === 403) ? 2500 : Math.min(5000, this.reconexoes * 1000);
      const pos = this.aoVivo ? 0 : this.video.currentTime;
      const minha = this.tentIdx;
      this.tocouUmaVez = false;
      this.carregando(true);
      if (pos) this._retomar = pos;
      this._reconT = setTimeout(() => {
        if (!this.ativo || this.tentIdx !== minha) return;
        this.carregarUrl(t);
        this._abrirT = setTimeout(() => { if (this.tentIdx === minha && !this.tocouUmaVez) this.falhou("demorou"); }, this.aoVivo ? 12000 : 25000);
      }, espera);
      return;
    }
    console.log("player: fonte falhou (" + motivo + ")");
    if (t && t.orig) Saude.marcarFalha(t.orig);
    this.tentIdx++;
    this.reconexoes = 0;
    this.tentar();
  },

  // ---------- vigia: imagem parada ----------
  /** Quadros desenhados até agora (quando a TV informa): anda mesmo quando o relógio do vídeo não anda. */
  quadros() {
    const v = this.video;
    try {
      if (v.getVideoPlaybackQuality) return v.getVideoPlaybackQuality().totalVideoFrames;
      if (v.webkitDecodedFrameCount != null) return v.webkitDecodedFrameCount;
    } catch (e) { /* ignore */ }
    return -1;
  },
  ligarVigia() {
    clearInterval(this.vigia);
    this.ultimoTempo = -1; this.ultimosQuadros = -1; this.paradoDesde = Date.now(); this.estavelDesde = 0;
    this.vigia = setInterval(() => {
      const v = this.video;
      const t = v.currentTime, q = this.quadros();
      const andou = t !== this.ultimoTempo || (q >= 0 && q !== this.ultimosQuadros);
      this.ultimoTempo = t; this.ultimosQuadros = q;
      if (!this.ativo || v.paused || !this.tocouUmaVez || andou) {
        if (andou) {
          if (!this.estavelDesde) this.estavelDesde = Date.now();
          else if (this.reconexoes > 0 && Date.now() - this.estavelDesde >= 15000) this.reconexoes = 0;
        }
        this.paradoDesde = Date.now();
        return;
      }
      this.estavelDesde = 0;
      // relógio e quadros parados: só conta como travado se a TV também diz que está sem dados para tocar
      // (algumas TVs LG não atualizam o relógio no ao vivo e o vídeo segue normal)
      const semDados = v.readyState < 3;
      if (!semDados && q < 0) { this.paradoDesde = Date.now(); return; }
      if (Date.now() - this.paradoDesde > (this.aoVivo ? 12000 : 25000)) {
        this.paradoDesde = Date.now();
        console.log("player: imagem parada, reconectando");
        this.falhou("parado");
      }
    }, 2000);
  },

  // ---------- abrir ----------
  comecar(fontes, aoVivo) {
    this.parar(true);
    this.ativo = true;
    this.aoVivo = aoVivo;
    this.tentativas = this.montarTentativas(fontes, aoVivo);
    this.tentIdx = 0; this.reconexoes = 0;
    this.ligarVigia();
    this.tentar();
  },

  previa(canal, box, canais, idx) {
    this.canais = canais; this.idx = idx; this.canalAtual = canal; this.item = null;
    this.modo = "previa";
    this.comecar(canal.f || [], true);
    this.mostrarPrevia(box);
  },
  mostrarPrevia(box) {
    if (!box || !this.ativo) return;
    this.modo = "previa";
    const r = box.getBoundingClientRect();
    const v = this.video;
    v.classList.remove("full");
    v.style.left = r.left + "px"; v.style.top = r.top + "px"; v.style.width = r.width + "px"; v.style.height = r.height + "px";
    box.classList.add("video");
    v.muted = false;
  },
  esconderVideo() { const v = this.video; v.style.width = "0"; v.style.height = "0"; },

  abrirCanal(canais, idx, catLive) {
    const c = canais[idx];
    const mesmo = this.ativo && this.canalAtual && this.canalAtual.id === c.id;
    this.canais = canais; this.idx = idx; this.canalAtual = c; this.catLive = catLive; this.item = null;
    if (!mesmo) this.comecar(c.f || [], true);
    this.aoVivo = true;
    this.telaCheia();
  },
  abrirVod(item) {
    this.canalAtual = null; this.fila = [item]; this.filaIdx = 0; this.serieCard = null;
    this.tocarItem(item);
  },
  abrirSerie(card, fila, idx, pos) {
    this.canalAtual = null; this.fila = fila; this.filaIdx = idx; this.serieCard = card;
    this.tocarItem(Object.assign({}, fila[idx], { inicio: pos }));
  },
  tocarItem(item) {
    this.item = item;
    this.comecar(item.fontes || [], false);
    this._retomar = item.inicio || 0;
    this.telaCheia();
  },

  telaCheia() {
    this.modo = "cheia";
    const v = this.video;
    v.style.left = "0"; v.style.top = "0"; v.style.width = ""; v.style.height = "";
    v.classList.add("full");
    $("#ui").classList.add("hidden");
    Telas.abrir(TelaPlayer());
  },

  parar(trocando) {
    clearTimeout(this._abrirT); clearTimeout(this._reconT); clearInterval(this.vigia); clearTimeout(this.zapT); clearTimeout(this.seekT);
    this.salvar(true);
    if (this.hls) { try { this.hls.destroy(); } catch (e) { /* ignore */ } this.hls = null; }
    const v = this.video;
    if (v) { try { v.pause(); } catch (e) { /* ignore */ } v.removeAttribute("src"); try { v.load(); } catch (e) { /* ignore */ } }
    if (!trocando) { this.ativo = false; this.canalAtual = null; this.esconderVideo(); v && v.classList.remove("full"); this.modo = null; }
  },

  // ---------- eventos ----------
  tempo() {
    const v = this.video;
    if (this._retomar && v.duration && isFinite(v.duration)) {
      const p = this._retomar; this._retomar = 0;
      if (p < v.duration - 5) { try { v.currentTime = p; } catch (e) { /* ignore */ } }
    }
    if (this.ui && this.ui.atualizar) this.ui.atualizar();
    if (Date.now() - this.salvarT > 10000) this.salvar(false);
  },
  salvar(forcar) {
    const v = this.video, it = this.item;
    if (!it || !it.chave || this.aoVivo || !v || !v.duration || !isFinite(v.duration)) return;
    this.salvarT = Date.now();
    const pos = v.currentTime, dur = v.duration;
    if (pos < 10 && !forcar) return;
    if (pos > dur * 0.92) { Usuario.marcarVisto(it.chave); Usuario.salvarProgresso(it.chave, null); return; }
    if (pos > 10) Usuario.salvarProgresso(it.chave, { pos, dur, em: Date.now() });
  },
  terminou() {
    if (this.aoVivo) { this.falhou("fim da transmissão"); return; }
    if (this.item) { Usuario.marcarVisto(this.item.chave); Usuario.salvarProgresso(this.item.chave, null); }
    if (this.fila && this.filaIdx + 1 < this.fila.length) { this.filaIdx++; this.tocarItemNaMesmaTela(this.fila[this.filaIdx]); return; }
    Telas.voltar();
  },
  tocarItemNaMesmaTela(item) {
    this.item = item;
    this.comecar(item.fontes || [], false);
    if (this.ui) this.ui.trocou();
  },
  semFonte() {
    clearTimeout(this._abrirT);
    this.carregando(false);
    if (this.modo === "previa") { const t = Telas.topo(); toast("Este canal não abriu agora"); this.parar(); if (t && t.atualizarPainel) t.atualizarPainel(); return; }
    if (this.ui) this.ui.erro();
  },
  carregando(on) { if (this.ui && this.ui.carregando) this.ui.carregando(on); },

  // ---------- pulo (filme/série) ----------
  passoSeek() {
    const seg = (Date.now() - this.seekInicio) / 1000;
    return seg < 1.5 ? 10 : seg < 3 ? 30 : seg < 5 ? 60 : 300;
  },
  pular(dir) {
    const v = this.video;
    if (!v.duration || !isFinite(v.duration)) return;
    const agora = Date.now();
    if (this.seekAlvo == null || agora - this.seekUltimo > 700) { this.seekAlvo = v.currentTime; this.seekInicio = agora; }
    this.seekUltimo = agora;
    this.seekAlvo = Math.max(0, Math.min(v.duration - 3, this.seekAlvo + dir * this.passoSeek()));
    clearTimeout(this.seekT);
    this.seekT = setTimeout(() => {
      try { v.currentTime = this.seekAlvo; } catch (e) { /* ignore */ }
      this.seekAlvo = null;
      if (this.ui) this.ui.atualizar();
    }, 600);
    if (this.ui) { this.ui.mostrar(4000); this.ui.atualizar(); }
  },

  // ---------- troca de canal na tela cheia ----------
  zap(delta) {
    if (this.canais.length < 2) return;
    const base = this.zapIdx >= 0 ? this.zapIdx : this.idx;
    this.zapIdx = (base + delta + this.canais.length) % this.canais.length;
    if (this.ui) this.ui.zap(this.canais[this.zapIdx], this.zapIdx);
    clearTimeout(this.zapT);
    this.zapT = setTimeout(() => {
      const i = this.zapIdx; this.zapIdx = -1;
      if (i === this.idx) return;
      this.idx = i; this.canalAtual = this.canais[i];
      this.comecar(this.canalAtual.f || [], true);
      if (this.ui) this.ui.trocou();
    }, 700);
  },
};

function TelaPlayer() {
  const P = Player;
  const anterior = Telas.topo();
  let raiz, topo, baixo, centro, barra, pos, buf, seek, tAtual, tTotal, zapBox, errBox, fontesBox = null;
  const fecharFontes = () => { if (fontesBox && fontesBox.parentNode) fontesBox.parentNode.removeChild(fontesBox); fontesBox = null; Foco.atual = null; };
  /** Lista das fontes (e de como cada uma é aberta); OK troca na hora. */
  const abrirFontes = () => {
    fecharFontes();
    const lista = h("div.fontes-lista", { "data-rola": "y" });
    let atual = null;
    P.tentativas.forEach((t, i) => {
      const el = h("div.fonte.foc" + (i === P.tentIdx ? ".sel" : ""), null, [
        h("span", { text: (i === P.tentIdx ? "▶ " : "") + "Fonte " + (i + 1) }),
        h("span.det", { text: [t.tipo, t.q].filter(Boolean).join(" · ") }),
      ]);
      el._ok = () => { fecharFontes(); P.tentIdx = i; P.reconexoes = 0; P.tentar(); ui.textos(); ui.mostrar(4000); };
      if (i === P.tentIdx) atual = el;
      lista.appendChild(el);
    });
    fontesBox = h("div.fontes", null, [h("h3", { text: "Fontes" }), h("p", { text: "OK troca · Voltar fecha" }), lista]);
    raiz.appendChild(fontesBox);
    Foco.definir(atual || $(".fonte", fontesBox));
  };
  const ui = {
    mostrar(ms) {
      topo.classList.remove("hidden");
      if (!P.aoVivo) baixo.classList.remove("hidden");
      clearTimeout(P.esconderT);
      P.esconderT = setTimeout(() => { if (!P.video.paused || P.aoVivo) { topo.classList.add("hidden"); baixo.classList.add("hidden"); } }, ms || 5000);
    },
    esconder() { topo.classList.add("hidden"); baixo.classList.add("hidden"); },
    visivel() { return !topo.classList.contains("hidden"); },
    textos() {
      topo.innerHTML = "";
      if (P.aoVivo && P.canalAtual) {
        const c = P.canalAtual, prog = Cat.agora(c, Date.now());
        topo.appendChild(h("h2", { text: (P.idx + 1) + "  " + c.n }));
        topo.appendChild(h("div.sub", { text: prog ? hora(prog.ini) + " – " + hora(prog.fim) + "  ·  " + prog.t : "Ao vivo" }));
        const t = P.tentativas[P.tentIdx];
        const fonte = t ? "Fonte " + (P.tentIdx + 1) + " de " + P.tentativas.length + (t.tipo ? " · " + t.tipo : "") + (t.q ? " · " + t.q : "") : "";
        topo.appendChild(h("div.sub", { text: "▲▼ trocar de canal  ·  ◀▶ fontes  ·  Voltar: lista de canais" + (fonte ? "  ·  " + fonte : ""), style: { color: "#9a9a9a", fontSize: "20px" } }));
      } else if (P.item) {
        topo.appendChild(h("h2", { text: P.item.titulo }));
        if (P.item.sub) topo.appendChild(h("div.sub", { text: P.item.sub }));
      }
    },
    atualizar() {
      if (P.aoVivo) return;
      const v = P.video, d = v.duration;
      if (!d || !isFinite(d)) return;
      const cur = P.seekAlvo != null ? P.seekAlvo : v.currentTime;
      pos.style.width = (100 * v.currentTime / d) + "%";
      seek.style.left = (100 * cur / d) + "%";
      try { if (v.buffered.length) buf.style.width = (100 * v.buffered.end(v.buffered.length - 1) / d) + "%"; } catch (e) { /* ignore */ }
      tAtual.textContent = relogio(cur);
      tTotal.textContent = "-" + relogio(d - cur);
    },
    carregando(on) {
      centro.innerHTML = "";
      if (on) centro.appendChild(h("div", { style: { display: "inline-block" } }, h("div.spinner")));
    },
    zap(c, i) {
      zapBox.innerHTML = "";
      const prog = Cat.agora(c, Date.now());
      zapBox.appendChild(h("div.num", { text: i + 1 }));
      if (c.l) zapBox.appendChild(h("div.lg", { bg: c.l }));
      zapBox.appendChild(h("div", null, [h("div.nm", { text: c.n }), h("div.now", { text: prog ? prog.t : "" })]));
      zapBox.classList.remove("hidden");
      clearTimeout(zapBox._t);
      zapBox._t = setTimeout(() => zapBox.classList.add("hidden"), 3500);
    },
    trocou() { errBox.classList.add("hidden"); ui.textos(); ui.mostrar(4000); },
    erro() {
      ui.carregando(false);
      errBox.innerHTML = "";
      const btns = h("div.btns", { "data-grupo": "err" });
      const b1 = h("div.btn.foc", { text: "Tentar de novo" });
      b1._ok = () => { errBox.classList.add("hidden"); P.comecar(P.aoVivo ? P.canalAtual.f : P.item.fontes, P.aoVivo); Foco.atual = null; };
      const b2 = h("div.btn.foc", { text: "Voltar" });
      b2._ok = () => Telas.voltar();
      btns.appendChild(b1); btns.appendChild(b2);
      errBox.appendChild(h("h2", { text: "Este conteúdo não está abrindo agora" }));
      errBox.appendChild(h("p", { text: "Tente de novo ou escolha outro." }));
      errBox.appendChild(btns);
      errBox.classList.remove("hidden");
      Foco.definir(b1);
    },
  };
  return {
    montar() {
      // o player fica na camada de cima (a #ui some para o vídeo aparecer inteiro)
      $("#ui").classList.add("hidden");
      const ov = $("#overlay");
      raiz = h("div.pl.player-layer");
      topo = h("div.topinfo");
      barra = h("div.bar", null, [buf = h("div.buf"), pos = h("div.pos"), seek = h("div.seek")]);
      baixo = h("div.bottom", null, [barra, h("div.times", null, [tAtual = h("b", { text: "00:00" }), tTotal = h("span", { text: "" })]),
        h("div.keys", { text: "OK pausar  ·  ◀ ▶ voltar/avançar (segure para pular mais)  ·  ▼ fontes  ·  Voltar: sair" })]);
      centro = h("div.center");
      zapBox = h("div.zap.hidden");
      errBox = h("div.errcard.hidden");
      [topo, baixo, centro, zapBox, errBox].forEach((x) => raiz.appendChild(x));
      ov.appendChild(raiz);
      P.ui = ui;
      ui.textos();
      ui.mostrar(P.aoVivo ? 4000 : 5000);
      if (P.aoVivo) baixo.classList.add("hidden");
      if (P.ativo && P.tocouUmaVez) ui.carregando(false); else ui.carregando(true);
      return null;
    },
    tecla(e) {
      const k = e.keyCode;
      if (!errBox.classList.contains("hidden") || fontesBox) return false; // cartão de erro / fontes: navegação normal
      if (k === KEY.OK) {
        if (P.aoVivo) { ui.textos(); ui.mostrar(6000); return true; }
        const v = P.video;
        if (v.paused) { v.play(); ui.mostrar(3000); } else { v.pause(); ui.mostrar(600000); }
        return true;
      }
      if (k === KEY.PLAY) { P.video.play(); ui.mostrar(3000); return true; }
      if (k === KEY.PAUSE || k === KEY.PLAYPAUSE) { const v = P.video; if (v.paused) v.play(); else { v.pause(); ui.mostrar(600000); } return true; }
      if (k === KEY.STOP) { Telas.voltar(); return true; }
      if (P.aoVivo) {
        if (k === KEY.UP) { P.zap(-1); return true; }
        if (k === KEY.DOWN || k === KEY.CH_DOWN) { P.zap(k === KEY.DOWN ? 1 : -1); return true; }
        if (k === KEY.CH_UP) { P.zap(1); return true; }
        if (k === KEY.LEFT || k === KEY.RIGHT) { abrirFontes(); return true; }
        return false;
      }
      if (k === KEY.LEFT || k === KEY.RW) { P.pular(-1); return true; }
      if (k === KEY.RIGHT || k === KEY.FF) { P.pular(1); return true; }
      if (k === KEY.DOWN) { abrirFontes(); return true; }
      if (k === KEY.UP) { ui.mostrar(5000); return true; }
      if (k === KEY.CH_UP && P.fila.length > 1 && P.filaIdx + 1 < P.fila.length) { P.filaIdx++; P.tocarItemNaMesmaTela(P.fila[P.filaIdx]); return true; }
      if (k === KEY.CH_DOWN && P.filaIdx > 0) { P.filaIdx--; P.tocarItemNaMesmaTela(P.fila[P.filaIdx]); return true; }
      return false;
    },
    voltar() {
      if (fontesBox) { fecharFontes(); return true; }
      if (P.seekAlvo != null) { clearTimeout(P.seekT); P.seekAlvo = null; ui.atualizar(); return true; }
      if (!P.aoVivo && ui.visivel() && !P.video.paused) { ui.esconder(); return true; }
      return false;
    },
    sair() {
      P.ui = null;
      if (raiz && raiz.parentNode) raiz.parentNode.removeChild(raiz);
      $("#ui").classList.remove("hidden");
      // ao vivo aberto pela lista de canais: o vídeo volta para a prévia, sem recarregar
      if (P.aoVivo && P.ativo && anterior && anterior.atualizarPainel && Telas.pilha.indexOf(anterior) >= 0) { P.modo = "previa"; P.video.classList.remove("full"); P.esconderVideo(); }
      else P.parar();
    },
  };
}

// ======================= teclado / controle =======================

let okDesde = 0, okLongoFeito = false;

function aoTeclar(e) {
  const k = e.keyCode;
  const input = document.activeElement && document.activeElement.tagName === "INPUT";
  if (input && (k === KEY.LEFT || k === KEY.RIGHT || (k === KEY.BACKSPACE))) return; // editando o texto
  if (KEY.BACK.indexOf(k) >= 0 || (k === KEY.BACKSPACE && !input)) {
    e.preventDefault();
    if (Dialogo.aberto) { Dialogo.aberto(); return; }
    Telas.voltar();
    return;
  }
  if (Dialogo.aberto) {
    if (k === KEY.OK) { e.preventDefault(); if (Foco.atual && Foco.atual._ok) Foco.atual._ok(); return; }
    const dir = { 37: "left", 38: "up", 39: "right", 40: "down" }[k];
    if (dir) { e.preventDefault(); Foco.mover(dir); }
    return;
  }
  const tela = Telas.topo();
  if (tela && tela.tecla && tela.tecla(e)) { e.preventDefault(); return; }
  if (k === KEY.OK) {
    e.preventDefault();
    if (input) { document.activeElement.blur(); Foco.mover("down"); return; }
    // segurar OK (canal): Minha lista
    if (!e.repeat) { okDesde = Date.now(); okLongoFeito = false; return; }
    if (!okLongoFeito && Date.now() - okDesde > 800 && Foco.atual && Foco.atual._longo) { okLongoFeito = true; Foco.atual._longo(); }
    return;
  }
  const dir = { 37: "left", 38: "up", 39: "right", 40: "down" }[k];
  if (dir) {
    e.preventDefault();
    if (input && (dir === "up" || dir === "down")) document.activeElement.blur();
    Foco.mover(dir);
  }
}

function aoSoltar(e) {
  if (e.keyCode !== KEY.OK) return;
  if (okLongoFeito) { okLongoFeito = false; okDesde = 0; return; }
  if (okDesde && Foco.atual && Foco.atual._ok) { okDesde = 0; Foco.atual._ok(); }
}

// ======================= início do app =======================

async function iniciar() {
  Player.iniciar();
  document.addEventListener("keydown", aoTeclar, true);
  document.addEventListener("keyup", aoSoltar, true);
  // app voltou do segundo plano: o vídeo da prévia continua; o player de tela cheia segue
  document.addEventListener("visibilitychange", () => {
    if (document.hidden && Player.ativo) { try { Player.video.pause(); } catch (e) { /* ignore */ } }
    else if (!document.hidden && Player.ativo) { const p = Player.video.play(); if (p && p.catch) p.catch(() => {}); }
  });
  await Cache.abrir();
  if (!Cat.token() && !/^http:\/\/(127\.0\.0\.1|localhost|192\.168\.|10\.)/i.test(SERVIDOR)) { pedirChave(); return; }
  carregarCatalogo();
}

function splash(texto, botoes, comInput) {
  Telas.limpar();
  const ui = $("#ui");
  ui.classList.remove("hidden");
  const kids = [h("img", { src: "logo.png" }), h("h1", { html: "BAD WOLF <b>ESPORTES</b>" })];
  const p = h("p", { text: texto || "" });
  kids.push(p);
  let input = null;
  if (comInput) { input = h("input.foc", { type: "text", placeholder: "Chave de acesso" }); kids.push(input); }
  if (botoes) kids.push(h("div.btns", null, botoes.map((b) => { const el = h("div.btn.foc", { text: b.t }); el._ok = b.f; return el; })));
  else if (!comInput) kids.push(h("div.spinner", { style: { marginTop: "30px" } }));
  const s = h("div.splash", null, kids);
  ui.appendChild(s);
  Telas.pilha = [{ montar: () => null, voltar: () => { perguntarSair(); return true; }, _caixa: s }];
  Foco.atual = null;
  const f = $(".foc", s);
  if (f) Foco.definir(f);
  return { p, input };
}

function pedirChave(erro) {
  const s = splash(erro || "Digite a chave de acesso do Bad Wolf TV (a mesma usada como senha no modo Xtream).", [
    { t: "Entrar", f: () => { const v = s.input.value.trim(); if (!v) return; store("token", v); carregarCatalogo(); } },
  ], true);
  s.input._ok = () => s.input.focus();
}

async function carregarCatalogo() {
  const s = splash("Carregando o catálogo…");
  try {
    await Cat.carregar((feitas, total) => { s.p.textContent = "Carregando canais e jogos… " + Math.round(100 * feitas / total) + "%"; });
  } catch (e) {
    if (e.message === "chave") { store("token", null); pedirChave("Chave de acesso não aceita pelo servidor. Confira e digite de novo."); return; }
    splash("Não consegui carregar o catálogo (" + e.message + "). Confira a internet da TV.", [
      { t: "Tentar de novo", f: carregarCatalogo },
      { t: "Trocar a chave", f: () => { store("token", null); pedirChave(); } },
    ]);
    return;
  }
  trocarAba("canais");
  setTimeout(() => Atualizador.verificar(), 3000);
  // confere o catálogo de novo a cada 3 h, só se a tela estiver parada no menu (nada de recarregar no meio do filme)
  setInterval(() => {
    if (Player.ativo) return;
    const antes = Cat.manifesto && Cat.manifesto.versao;
    Cat.baixar("manifesto.json", 15000).then((t) => {
      const m = JSON.parse(t);
      if (m.versao !== antes) Cat.carregar(() => {}).then(() => toast("Guia e canais atualizados")).catch(() => {});
    }).catch(() => {});
  }, 60 * 60 * 1000); // o guia muda ao longo do dia: confere a cada 1 h
}

/**
 * Verificador unificado de atualizações via GitHub Releases (mesmo contrato do :core UpdaterCore).
 * Compatível com LG webOS (auto-instala se houver Homebrew Channel; senão avisa com link) e reutilizável em Tizen.
 */
const Atualizador = {
  LATEST: "https://api.github.com/repos/guisousaoliveira/minha-tv/releases/latest",
  ASSET: "BadWolfEsportes-LG.ipk",
  comparar(a, b) {
    const pa = String(a || "").replace(/^v/, "").split("-")[0].split(".").map((n) => parseInt(n, 10) || 0);
    const pb = String(b || "").replace(/^v/, "").split("-")[0].split(".").map((n) => parseInt(n, 10) || 0);
    const len = Math.max(pa.length, pb.length);
    for (let i = 0; i < len; i++) {
      const d = (pa[i] || 0) - (pb[i] || 0);
      if (d !== 0) return d;
    }
    return 0;
  },
  async verificar() {
    if (!VERSAO_APP || VERSAO_APP === "dev") return null;
    try {
      const r = await comTempo(fetch(this.LATEST, { headers: { Accept: "application/vnd.github+json" } }), 12000);
      if (!r.ok) return null;
      const o = await r.json();
      const versao = String(o.tag_name || "").replace(/^v/, "");
      if (!versao || this.comparar(versao, VERSAO_APP) <= 0) return null;
      const asset = (o.assets || []).find((x) => x.name === this.ASSET);
      if (!asset || !asset.browser_download_url) return null;
      this.oferecer({ versao, url: asset.browser_download_url });
      return { versao, url: asset.browser_download_url };
    } catch (e) { return null; }
  },
  instalarHomebrew(url) {
    try {
      if (window.webOS && window.webOS.service && window.webOS.service.request) {
        toast("Baixando e instalando atualização…");
        window.webOS.service.request("luna://org.webosbrew.hbchannel.service", {
          method: "install",
          parameters: { ipkUrl: url },
          onSuccess: () => toast("Atualização instalada! Reabra o aplicativo."),
          onFailure: () => toast("Instale manualmente o arquivo " + this.ASSET + " no GitHub Releases."),
        });
        return true;
      }
    } catch (e) { /* sem Homebrew Channel */ }
    return false;
  },
  oferecer(rel) {
    if (Player.ativo) { toast("Nova versão " + rel.versao + " disponível!"); return; }
    const temHb = !!(window.webOS && window.webOS.service && window.webOS.service.request);
    toast("Nova versão " + rel.versao + " disponível (atual: " + VERSAO_APP + ")" + (temHb ? " · instalando via Homebrew…" : " · baixe " + this.ASSET + " no GitHub"));
    if (temHb) this.instalarHomebrew(rel.url);
  },
};

window.BadWolf = { Cat, Player, Telas, Foco, Usuario, Conta, Esporte, Placar, Atualizador, versao: VERSAO_APP };
if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", iniciar); else iniciar();
