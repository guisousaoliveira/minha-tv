"""
Monta o app Bad Wolf Esportes da LG (webOS): webos-esportes/dist/ e o pacote BadWolfEsportes-LG.ipk.

  cd webos-esportes && npm ci --no-audit --no-fund && python3 build.py

Variáveis (opcionais):
  APP_VERSION     versão (ex.: 1.0.140); padrão 1.0.0
  BADWOLF_TOKEN   chave de acesso ao catálogo, já embutida no app (sem ela o app pede a chave na 1ª vez)
  BADWOLF_SERVER  servidor do catálogo; padrão https://badwolf.pages.dev

O .ipk é o formato que a LG instala: um arquivo "ar" com debian-binary, control.tar.gz e data.tar.gz
(o app fica em usr/palm/applications/<id>/). Igual ao que o ares-package gera.
"""

import gzip
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import time
import zipfile

AQUI = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(AQUI, "src")
DIST = os.path.join(AQUI, "dist")


def montar_dist(versao, token, servidor):
    if os.path.isdir(DIST):
        shutil.rmtree(DIST)
    os.makedirs(DIST)
    esbuild = os.path.join(AQUI, "node_modules", ".bin", "esbuild.cmd" if os.name == "nt" else "esbuild")
    # TVs LG a partir do webOS 4 (2018) usam um Chrome 53: o esbuild converte o JS moderno para ele
    subprocess.run([esbuild, os.path.join(SRC, "app.js"), "--bundle", "--minify", "--target=chrome53",
                    "--outfile=" + os.path.join(DIST, "app.js")], check=True)
    shutil.copy(os.path.join(AQUI, "node_modules", "hls.js", "dist", "hls.light.min.js"), DIST)
    for f in ("index.html", "app.css"):
        shutil.copy(os.path.join(SRC, f), DIST)
    for f in os.listdir(os.path.join(AQUI, "img")):
        shutil.copy(os.path.join(AQUI, "img", f), DIST)
    cfg = {"versao": versao, "servidor": servidor}
    if token:
        cfg["token"] = token
    with open(os.path.join(DIST, "config.js"), "w", encoding="utf-8") as f:
        f.write("window.BW_CONFIG = " + json.dumps(cfg, ensure_ascii=False) + ";\n")
    with open(os.path.join(AQUI, "appinfo.json"), encoding="utf-8") as f:
        info = json.load(f)
    info["version"] = versao
    with open(os.path.join(DIST, "appinfo.json"), "w", encoding="utf-8") as f:
        json.dump(info, f, ensure_ascii=False, indent=2)
    return info


def _tar_gz(arquivos):
    """arquivos: [(caminho no pacote, bytes ou None para pasta)] -> .tar.gz em bytes (dono root, sem data)."""
    bruto = io.BytesIO()
    with tarfile.open(fileobj=bruto, mode="w", format=tarfile.GNU_FORMAT) as tar:
        for nome, dados in arquivos:
            ti = tarfile.TarInfo(nome)
            ti.uid = ti.gid = 0
            ti.uname = ti.gname = "root"
            ti.mtime = int(time.time())
            if dados is None:
                ti.type = tarfile.DIRTYPE
                ti.mode = 0o755
                tar.addfile(ti)
            else:
                ti.size = len(dados)
                ti.mode = 0o644
                tar.addfile(ti, io.BytesIO(dados))
    return gzip.compress(bruto.getvalue(), mtime=0)


def _ar(membros):
    """Arquivo "ar" (formato do .ipk/.deb): [(nome, bytes)]."""
    out = io.BytesIO()
    out.write(b"!<arch>\n")
    agora = int(time.time())
    for nome, dados in membros:
        cab = "%-16s%-12d%-6d%-6d%-8s%-10d`\n" % (nome, agora, 0, 0, "100644", len(dados))
        out.write(cab.encode("ascii"))
        out.write(dados)
        if len(dados) % 2:
            out.write(b"\n")
    return out.getvalue()


def montar_ipk(info, saida):
    app_id = info["id"]
    base_app = "usr/palm/applications/%s/" % app_id
    base_pkg = "usr/palm/packages/%s/" % app_id
    dados = []
    pastas = {"usr/", "usr/palm/", "usr/palm/applications/", "usr/palm/packages/", base_app, base_pkg}
    tamanho = 0
    for raiz, dirs, files in os.walk(DIST):
        dirs.sort()
        rel = os.path.relpath(raiz, DIST)
        if rel != ".":
            pastas.add(base_app + rel.replace(os.sep, "/") + "/")
        for f in sorted(files):
            caminho = os.path.join(raiz, f)
            nome = base_app + os.path.relpath(caminho, DIST).replace(os.sep, "/")
            with open(caminho, "rb") as fh:
                b = fh.read()
            tamanho += len(b)
            dados.append((nome, b))
    pkginfo = json.dumps({"id": app_id, "version": info["version"], "app": app_id}, indent=2).encode()
    dados.append((base_pkg + "packageinfo.json", pkginfo))
    tudo = [(p, None) for p in sorted(pastas)] + dados
    control = (
        "Package: %s\nVersion: %s\nSection: misc\nPriority: optional\nArchitecture: all\n"
        "Installed-Size: %d\nMaintainer: Bad Wolf TV <nobody@example.com>\n"
        "Description: Bad Wolf Esportes para webOS.\nwebOS-Package-Format-Version: 2\nwebOS-Packager-Version: x.y.x\n"
        % (app_id, info["version"], tamanho)
    ).encode()
    ipk = _ar([
        ("debian-binary", b"2.0\n"),
        ("control.tar.gz", _tar_gz([("control", control)])),
        ("data.tar.gz", _tar_gz(tudo)),
    ])
    with open(saida, "wb") as f:
        f.write(ipk)
    return len(ipk)


def montar_simulador_zip(saida):
    with zipfile.ZipFile(saida, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for raiz, dirs, files in os.walk(DIST):
            dirs.sort()
            for f in sorted(files):
                caminho = os.path.join(raiz, f)
                zf.write(caminho, os.path.relpath(caminho, DIST).replace(os.sep, "/"))
    return os.path.getsize(saida)


def main():
    versao = os.environ.get("APP_VERSION", "1.0.0").strip() or "1.0.0"
    token = os.environ.get("BADWOLF_TOKEN", "").strip()
    servidor = os.environ.get("BADWOLF_SERVER", "https://badwolf.pages.dev").strip()
    info = montar_dist(versao, token, servidor)
    saida = os.path.join(AQUI, "BadWolfEsportes-LG.ipk")
    n = montar_ipk(info, saida)
    sim = os.path.join(AQUI, "BadWolfEsportes-simulador.zip")
    ns = montar_simulador_zip(sim)
    print("BadWolfEsportes-LG.ipk %s (%d KiB) + BadWolfEsportes-simulador.zip (%d KiB)%s" % (versao, n // 1024, ns // 1024, "" if token else " · sem chave embutida: o app pede na 1ª vez"))


if __name__ == "__main__":
    sys.exit(main())
