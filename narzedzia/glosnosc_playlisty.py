# Pomiar przybliżonej głośności (LUFS) utworów z playlisty Spotify
# na podstawie 30-s podglądów z iTunes. Uruchamiane w GitHub Actions.
import json, re, sys, subprocess, urllib.request, urllib.parse, statistics, time, os, tempfile

PLAYLISTA = sys.argv[1]
UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36"}

def pobierz(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()

html = pobierz(f"https://open.spotify.com/embed/playlist/{PLAYLISTA}").decode("utf-8", "replace")
m = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', html, re.S)
dane = json.loads(m.group(1))
ent = dane["props"]["pageProps"]["state"]["data"]["entity"]
utwory = [(t["title"], t.get("subtitle", "")) for t in ent["trackList"]]
print(f"Playlista: {ent.get('name')} | utworów: {len(utwory)}", flush=True)

def lufs(plik):
    p = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", plik, "-af", "ebur128=peak=true", "-f", "null", "-"],
                       capture_output=True, text=True)
    s = p.stderr[p.stderr.rfind("Summary:"):]
    i = re.search(r"I:\s+(-?[\d.]+) LUFS", s)
    lra = re.search(r"LRA:\s+([\d.]+) LU", s)
    pk = re.search(r"Peak:\s+(-?[\d.]+) dBFS", s)
    return float(i.group(1)), float(lra.group(1)) if lra else None, float(pk.group(1)) if pk else None

def norm(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())

wyniki, brak = [], []
for tytul, wyk in utwory:
    glowny = wyk.split(",")[0].strip()
    czysty = re.sub(r"\s*[\(\[].*?[\)\]]", "", tytul).split(" - ")[0]
    trafienie = None
    for kraj in ("PL", "US"):
        q = urllib.parse.urlencode({"term": f"{glowny} {czysty}", "entity": "song", "limit": 10, "country": kraj})
        try:
            res = json.loads(pobierz(f"https://itunes.apple.com/search?{q}"))["results"]
        except Exception as e:
            print("iTunes błąd:", e); time.sleep(5); res = []
        for r in res:
            if r.get("previewUrl") and norm(czysty)[:12] in norm(r.get("trackName", "")) and norm(glowny)[:8] in norm(r.get("artistName", "")):
                trafienie = r; break
        if trafienie: break
        time.sleep(1)
    if not trafienie:
        brak.append(f"{wyk} – {tytul}"); continue
    with tempfile.NamedTemporaryFile(suffix=".m4a", delete=False) as f:
        f.write(pobierz(trafienie["previewUrl"])); sciezka = f.name
    try:
        I, LRA, PK = lufs(sciezka)
        wyniki.append((I, LRA, PK, wyk, tytul))
        print(f"{I:6.1f} LUFS | {wyk} – {tytul}", flush=True)
    except Exception as e:
        brak.append(f"{wyk} – {tytul} (pomiar: {e})")
    os.unlink(sciezka)
    time.sleep(1)

wyniki.sort(key=lambda w: w[0], reverse=True)
lv = [w[0] for w in wyniki]
out = ["# Głośność utworów z playlisty (podglądy 30 s z iTunes)", "",
       f"Playlista: {ent.get('name')}. Zmierzone: {len(wyniki)} z {len(utwory)}.", "",
       f"Średnia: {statistics.mean(lv):.1f} LUFS, mediana: {statistics.median(lv):.1f} LUFS, "
       f"odchylenie: {statistics.pstdev(lv):.1f} LU, zakres: {min(lv):.1f} do {max(lv):.1f} LUFS.", "",
       "| # | LUFS | do −10 (dB) | Szczyt dBFS | Wykonawca | Tytuł |", "|---|---|---|---|---|---|"]
for n, (I, LRA, PK, wyk, tyt) in enumerate(wyniki, 1):
    out.append(f"| {n} | {I:.1f} | {-10 - I:+.1f} | {PK if PK is not None else '-'} | {wyk} | {tyt} |")
if brak:
    out += ["", "Nie znaleziono w iTunes:", ""] + [f"- {b}" for b in brak]
tekst = "\n".join(out)
print("\n=====WYNIK=====\n" + tekst + "\n=====KONIEC=====")
open("wynik.md", "w").write(tekst)
if os.environ.get("GITHUB_STEP_SUMMARY"):
    open(os.environ["GITHUB_STEP_SUMMARY"], "a").write(tekst)
