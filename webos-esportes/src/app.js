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
  token() { return store("token") || CFG.token || ""; },
  base() { return SERVIDOR + "/" + encodeURIComponent(this.token()) + "/app/v2/"; },

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
    try { guia = await this.parte("guia"); } catch (e) { /* sem guia: os eventos ficam vazios */ }
    progresso(3, 3);
    this.categorias = cats || this.categorias;
    this.canais = canais || [];
    this.guia = guia || {};
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

// ======================= casca (topo com as abas) =======================

const ABAS = [{ id: "canais", t: "Canais" }, { id: "eventos", t: "Eventos" }];

/** Topo com as abas. [abaixo] = zona da tela que recebe o foco quando você desce das abas. */
function casca(ui, abaAtual, abaixo) {
  const top = h("div.top");
  top.appendChild(h("img.logo", { src: "logo.png" }));
  top.appendChild(h("div.brand", { html: "BAD WOLF <b>ESPORTES</b>" }));
  const abas = h("div.abas", { "data-zona": "abas", "data-eixo": "h", "data-baixo": abaixo, style: { display: "flex" } });
  ABAS.forEach((a) => {
    const el = h("div.tab.foc" + (a.id === abaAtual ? ".sel" : ""), { text: a.t, "data-aba": a.id });
    el._ok = () => { if (a.id !== abaAtual) trocarAba(a.id); else Foco.mover("down"); };
    if (a.id === abaAtual) abas._ultimo = el;
    abas.appendChild(el);
  });
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
  Telas.abrir(id === "eventos" ? TelaEventos() : TelaCanais());
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

  async buscarLiga(liga, comHoje) {
    const urls = [this.API + liga.id + "/scoreboard"];
    if (comHoje) urls.push(this.API + liga.id + "/scoreboard?dates=" + this.dataEspn(new Date()));
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

  /** Jogos para mostrar: ao vivo, de hoje/amanhã e os encerrados há pouco (com placar final). */
  lista() {
    const agora = Date.now();
    return Object.keys(this.jogos).map((id) => this.jogos[id]).filter((j) => {
      if (j.estado === "in") return true;
      if (j.estado === "pre") return j.ini - agora < 30 * 3600 * 1000 && j.ini > agora - 3 * 3600 * 1000;
      return agora - j.ini < 4 * 3600 * 1000; // encerrado: até ~2 h depois do fim
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
};

// ======================= Eventos =======================

function TelaEventos() {
  let lista, listaInner, onde, ondeInner, cabeca, selecionado = null, aviso;
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
    if (item.jogo) {
      const j = item.jogo;
      cabeca.appendChild(h("div.liga", null, [j.logoLiga ? h("span.ligalogo", { bg: j.logoLiga }) : null, j.liga]));
      cabeca.appendChild(h("div.placar-grande", null, [
        h("div.time", null, [escudo(j.casa.logo, j.casa.nome), h("div.tnome", { text: j.casa.nome })]),
        h("div.placar-num", { text: j.estado === "pre" ? hora(j.ini) : (j.casa.placar || "0") + " x " + (j.fora.placar || "0") }),
        h("div.time", null, [escudo(j.fora.logo, j.fora.nome), h("div.tnome", { text: j.fora.nome })]),
      ]));
      cabeca.appendChild(h("div.sub" + (j.estado === "in" ? ".vivo" : ""), { text: estadoJogo(j) }));
      opcoes = Placar.onde(j);
    } else {
      const ev = item.ev;
      if (ev.liga) cabeca.appendChild(h("div.liga", { text: ev.liga }));
      cabeca.appendChild(h("h3", { text: ev.jogo }));
      cabeca.appendChild(h("div.sub", { text: quandoGuia(ev) }));
      opcoes = ev.onde;
    }
    if (!opcoes.length) {
      cabeca.appendChild(h("div.hint", { text: "Não achei este jogo no guia dos canais. Procure na aba Canais." }));
      return;
    }
    cabeca.appendChild(h("div.hint", { text: "Onde assistir · OK no canal para abrir" }));
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
    const el = h("div.evento.jogo.foc" + (j.estado === "in" ? ".vivo" : ""), { "data-chave": "j" + j.id }, [
      h("div.ev-hora", { text: j.estado === "in" ? (j.detalhe || "AO VIVO") : j.estado === "post" ? "FIM" : hora(j.ini) }),
      h("div.ev-times", null, [
        h("div.lado", null, [escudo(j.casa.logo, j.casa.nome), h("span", { text: j.casa.nome })]),
        h("div.ev-placar", { text: placar(j) }),
        h("div.lado.fora", null, [h("span", { text: j.fora.nome }), escudo(j.fora.logo, j.fora.nome)]),
      ]),
      h("div.ev-liga", { text: j.liga }),
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
    listaInner.innerHTML = "";
    const jogos = Placar.lista();
    // outros eventos do guia (UFC, NBA, F1...), sem repetir os jogos de futebol que já estão em cima
    const chavesJogos = jogos.map((j) => [Placar.chaves(j.casa.completo + " " + j.casa.nome), Placar.chaves(j.fora.completo + " " + j.fora.nome)]);
    const outros = Esporte.eventos().filter((ev) => !ev.reprise && !chavesJogos.some(([a, b]) => {
      const t = semAcento(ev.jogo);
      return a.some((k) => t.indexOf(k) >= 0) && b.some((k) => t.indexOf(k) >= 0);
    })).slice(0, 40);
    const secao = (t) => listaInner.appendChild(h("div.secao", { text: t }));
    const vivos = jogos.filter((j) => j.estado === "in"), proximos = jogos.filter((j) => j.estado === "pre"), fim = jogos.filter((j) => j.estado === "post");
    if (vivos.length) { secao("Ao vivo agora"); vivos.forEach((j) => listaInner.appendChild(linhaJogo(j))); }
    if (proximos.length) { secao("Próximos jogos"); proximos.forEach((j) => listaInner.appendChild(linhaJogo(j))); }
    if (outros.length) { secao("Outros eventos no guia"); outros.forEach((ev) => listaInner.appendChild(linhaGuia(ev))); }
    if (fim.length) { secao("Encerrados"); fim.forEach((j) => listaInner.appendChild(linhaJogo(j))); }
    aviso.textContent = !jogos.length && !outros.length ? "Nenhum evento importante no momento." : "";
    $$(".evento", listaInner).forEach((el) => {
      el._aoFocar = () => { clearTimeout(el._t); el._t = setTimeout(() => { if (Foco.atual === el) desenharOnde(el._item); }, 120); };
      el._ok = () => { if (!selecionado || selecionado !== el._item) desenharOnde(el._item); Foco.entrar(onde); };
    });
    // continua no mesmo jogo depois de atualizar o placar
    const volta = chaveFoco && $$(".evento", listaInner).filter((e) => e.getAttribute("data-chave") === chaveFoco)[0];
    if (volta && Foco.atual && Foco.atual.closest("[data-zona='eventos']")) Foco.definir(volta);
    return $(".evento", listaInner);
  };

  return {
    montar(ui) {
      Fundo.cor("radial-gradient(1100px 700px at 1650px 330px, #0a2a33, #000)");
      casca(ui, "eventos", "eventos");
      const tela = h("div.screen");
      const cols = h("div.cols");
      lista = h("div.evlist", { "data-rola": "y", "data-folga": "80", "data-zona": "eventos", "data-eixo": "v", "data-cima": "abas", "data-dir": "onde" });
      listaInner = h("div.evlist-inner");
      aviso = h("div.empty");
      lista.appendChild(aviso);
      lista.appendChild(listaInner);
      const painel = h("div.evpanel");
      cabeca = h("div.evcabeca");
      onde = h("div.onde", { "data-rola": "y", "data-folga": "60", "data-zona": "onde", "data-eixo": "v", "data-esq": "eventos", "data-cima": "abas" });
      ondeInner = h("div.onde-inner");
      onde.appendChild(ondeInner);
      painel.appendChild(cabeca); painel.appendChild(onde);
      cols.appendChild(lista); cols.appendChild(painel);
      tela.appendChild(cols);
      ui.appendChild(tela);
      // primeiro o que já tem (guia e placares guardados); os placares chegam em seguida
      let pri = desenharLista();
      if (!Object.keys(Placar.jogos).length) aviso.textContent = "Buscando os jogos de hoje…";
      desenharOnde(pri ? pri._item : null);
      const tela0 = this, t0 = Date.now();
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
    v.addEventListener("playing", () => { this.tocouUmaVez = true; this.reconexoes = 0; this.carregando(false); this.ultimoTempo = -1; });
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
    const abriu = () => { if (this.ativo && !this.tocouUmaVez && (v.currentTime > 0 || this.quadros() > 0)) { this.tocouUmaVez = true; this.carregando(false); } };
    v.addEventListener("timeupdate", abriu);
    v.addEventListener("loadeddata", abriu);
    v.addEventListener("canplay", abriu);
  },

  // ---------- fontes ----------
  /** Tentativas de um canal/vídeo: cada fonte (sem cabeçalho especial primeiro); no ao vivo, também a versão .m3u8. */
  montarTentativas(fontes, aoVivo) {
    const ordem = fontes.filter((f) => !f.h).concat(fontes.filter((f) => f.h)).slice(0, 8);
    const temJs = !!(window.Hls && window.Hls.isSupported());
    const out = [];
    ordem.forEach((f) => {
      const ehM3u8 = /\.m3u8(\?|$)/i.test(f.u);
      const m3u8 = ehM3u8 ? f.u : (aoVivo ? xtreamHls(f.u) : null);
      // 1) HLS pelo hls.js, com a lista sempre pedida no link original (o endereço redirecionado vence em
      //    segundos). Lista que chega de outra máquina do servidor fora de sincronia recomeça do zero (ver
      //    carregarUrl). Na LG o .ts direto no player da TV piscava a cada 8-15 s; por isso o HLS vem antes.
      if (m3u8 && temJs) out.push({ url: m3u8, q: f.q, js: true, tipo: "HLS" });
      // 2) reserva: o link do jeito que veio, no player da TV
      if (!ehM3u8 || !temJs) out.push({ url: f.u, q: f.q, tipo: ehM3u8 ? "HLS (TV)" : aoVivo ? "TS" : "" });
    });
    return out;
  },

  /** Carregador de listas do hls.js que sempre volta no link original do canal (ver montarTentativas). */
  carregadorEstavel(original) {
    const Base = window.Hls.DefaultConfig.loader;
    // todos os endereços para onde o link original já redirecionou (o hls.js continua pedindo o primeiro)
    const redirecionados = {};
    return class extends Base {
      load(context, config, callbacks) {
        if (redirecionados[context.url]) context.url = original;
        const ok = callbacks.onSuccess;
        const ctx = context;
        callbacks = Object.assign({}, callbacks, {
          onSuccess(resp, stats, c, net) {
            if (ctx.url === original && resp && resp.url && resp.url !== original) redirecionados[resp.url] = 1;
            ok(resp, stats, c, net);
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
        // lista que veio de outra máquina do servidor, fora de sincronia: recomeça do zero (sem misturar os pedaços)
        if (d.details === window.Hls.ErrorDetails.LEVEL_PARSING_ERROR && this.hls === hls) {
          const agora = Date.now();
          this._trocasLista = (this._trocasLista || []).filter((t) => agora - t < 60000).concat(agora);
          if (this._trocasLista.length <= 5) { console.log("player: lista de outro servidor, recomeçando"); this.carregarUrl(t); return; }
          this.falhou("lista instável");
          return;
        }
        if (!d.fatal) return;
        // erro de rede/mídia no meio: o hls.js tenta se recuperar antes de trocar de fonte
        if (d.type === window.Hls.ErrorTypes.MEDIA_ERROR && !this._recuperouMidia) { this._recuperouMidia = true; hls.recoverMediaError(); return; }
        this.falhou("hls " + d.details);
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
    if (this.tentIdx >= this.tentativas.length) { this.semFonte(); return; }
    const t = this.tentativas[this.tentIdx];
    this.tocouUmaVez = false;
    this.carregando(true);
    this.carregarUrl(t);
    const minha = this.tentIdx;
    // não abriu a tempo: próxima fonte
    this._abrirT = setTimeout(() => { if (this.tentIdx === minha && !this.tocouUmaVez) this.falhou("demorou"); }, this.aoVivo ? 12000 : 25000);
  },

  falhou(motivo) {
    if (!this.ativo) return;
    if (this.tocouUmaVez && this.reconexoes < 2) {
      // estava tocando e caiu: reabre a mesma fonte antes de trocar
      this.reconexoes++;
      const pos = this.aoVivo ? 0 : this.video.currentTime;
      this.tocouUmaVez = false;
      this.carregando(true);
      this.carregarUrl(this.tentativas[this.tentIdx]);
      if (pos) this._retomar = pos;
      return;
    }
    console.log("player: fonte falhou (" + motivo + ")");
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
    this.ultimoTempo = -1; this.ultimosQuadros = -1; this.paradoDesde = Date.now();
    this.vigia = setInterval(() => {
      const v = this.video;
      const t = v.currentTime, q = this.quadros();
      const andou = t !== this.ultimoTempo || (q >= 0 && q !== this.ultimosQuadros);
      this.ultimoTempo = t; this.ultimosQuadros = q;
      if (!this.ativo || v.paused || !this.tocouUmaVez || andou) { this.paradoDesde = Date.now(); return; }
      // relógio e quadros parados: só conta como travado se a TV também diz que está sem dados para tocar
      // (algumas TVs LG não atualizam o relógio no ao vivo e o vídeo segue normal)
      const semDados = v.readyState < 3;
      if (!semDados && q < 0) { this.paradoDesde = Date.now(); return; }
      if (Date.now() - this.paradoDesde > (this.aoVivo ? 15000 : 25000)) {
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
    clearTimeout(this._abrirT); clearInterval(this.vigia); clearTimeout(this.zapT); clearTimeout(this.seekT);
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
  if (!Cat.token()) { pedirChave(); return; }
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

window.BadWolf = { Cat, Player, Telas, Foco, Usuario, Esporte, Placar, versao: VERSAO_APP };
if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", iniciar); else iniciar();
