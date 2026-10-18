#!/usr/bin/env python3
"""Baixa a coleção de reserva do app: destaques em domínio público do Met Museum, por estilo.

Roda no GitHub Actions antes do build. Nunca derruba o build: se a rede falhar,
o app sai com o que conseguiu baixar (ou sem coleção, e aí usa a paisagem).
"""
import json
import os
import random
import sys
import time
import urllib.parse
import urllib.request

API = "https://collectionapi.metmuseum.org/public/collection/v1"
PER_STYLE = 10
UA = {"User-Agent": "ConsigliereIA build (app pessoal)"}

# Mesmas buscas do app (data/Settings.kt → ArtStyle).
STYLES = {
    "GENERAL": ["isHighlight=true&hasImages=true&departmentId=11&q=painting"],
    "JAPANESE": [
        "isHighlight=true&hasImages=true&departmentId=6&artistOrCulture=true&q=Japan",
        "hasImages=true&artistOrCulture=true&q=Hokusai",
        "hasImages=true&artistOrCulture=true&q=Hiroshige",
    ],
    "RENAISSANCE": ["isHighlight=true&hasImages=true&departmentId=11&dateBegin=1400&dateEnd=1600&q=painting"],
    "IMPRESSIONISM": [
        "isHighlight=true&hasImages=true&departmentId=11&dateBegin=1860&dateEnd=1910&q=landscape",
        "isHighlight=true&hasImages=true&artistOrCulture=true&q=Monet",
    ],
    "SOCIAL_REALISM": [
        "hasImages=true&departmentId=11&artistOrCulture=true&q=Millet",
        "hasImages=true&departmentId=11&artistOrCulture=true&q=Courbet",
        "hasImages=true&departmentId=11&artistOrCulture=true&q=Daumier",
        "hasImages=true&departmentId=1&artistOrCulture=true&q=Bellows",
        "hasImages=true&departmentId=1&artistOrCulture=true&q=Sloan",
    ],
}


def get(url, binary=False):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=30) as r:
        data = r.read()
    return data if binary else json.loads(data)


def main(out_dir):
    random.seed(42)
    manifest = {}
    for style, queries in STYLES.items():
        ids = []
        for q in queries:
            try:
                ids += (get(f"{API}/search?{q}").get("objectIDs") or [])[:200]
            except Exception as e:
                print(f"[{style}] busca falhou: {e}")
        ids = list(dict.fromkeys(ids))
        random.shuffle(ids)
        os.makedirs(os.path.join(out_dir, style), exist_ok=True)
        items = []
        for oid in ids:
            if len(items) >= PER_STYLE:
                break
            try:
                o = get(f"{API}/objects/{oid}")
                img = o.get("primaryImageSmall") or ""
                if not o.get("isPublicDomain") or not img:
                    continue
                data = get(img, binary=True)
                rel = f"art/{style}/{oid}.jpg"
                with open(os.path.join(out_dir, style, f"{oid}.jpg"), "wb") as f:
                    f.write(data)
                caption = " — ".join(x for x in [o.get("title"), o.get("artistDisplayName")] if x)
                items.append({"file": rel, "caption": caption})
                time.sleep(0.05)
            except Exception as e:
                print(f"[{style}] obra {oid} falhou: {e}")
        manifest[style] = items
        print(f"[{style}] {len(items)} obras")
    with open(os.path.join(out_dir, "manifest.json"), "w") as f:
        json.dump(manifest, f, ensure_ascii=False)


if __name__ == "__main__":
    try:
        main(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/art")
    except Exception as e:
        print(f"coleção não baixada: {e}")
