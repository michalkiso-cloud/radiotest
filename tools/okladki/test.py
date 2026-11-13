# Test: bieżące tytuły ICY ze stacji + wyszukanie okładki w iTunes i Deezer
import urllib.request, urllib.parse, json, re
STACJE = {"Disco": "https://n44a-eu.rcs.revma.com/cvswvmyewzzuv", "FTB": "https://play.radioftb.net:8000/stream",
          "Eska2": "https://waw.ic.smcdn.pl/1380-1.mp3", "Cmp3": "https://s.radiocmp3.eu/stream256",
          "RMF24": "https://rs201-krk.rmfstream.pl/RMF24"}
def tytul(url):
    req = urllib.request.Request(url, headers={"Icy-MetaData": "1", "User-Agent": "Python-urllib/3.12"})
    with urllib.request.urlopen(req, timeout=20) as r:
        n = int(r.headers.get("icy-metaint", 0))
        if not n: return None
        for _ in range(6):
            r.read(n); dl = r.read(1)[0]
            m = r.read(dl*16).rstrip(b"\0").decode("utf-8", "ignore")
            t = re.search(r"StreamTitle='(.*?)';", m)
            if t and t.group(1).strip(): return t.group(1)
    return None
def itunes(q):
    u = "https://itunes.apple.com/search?" + urllib.parse.urlencode({"term": q, "entity": "song", "limit": 1, "country": "PL"})
    d = json.load(urllib.request.urlopen(u, timeout=15))["results"]
    return (d[0]["artistName"] + " – " + d[0]["trackName"], d[0]["artworkUrl100"].replace("100x100", "600x600")) if d else None
def deezer(q):
    d = json.load(urllib.request.urlopen("https://api.deezer.com/search?" + urllib.parse.urlencode({"q": q, "limit": 1}), timeout=15))["data"]
    return (d[0]["artist"]["name"] + " – " + d[0]["title"], d[0]["album"]["cover_xl"]) if d else None
def czysc(t):
    t = re.sub(r"\(.*?\)|\[.*?\]", " ", t)
    t = re.sub(r"(?i)\b(feat\.?|ft\.?|x|&|vs\.?)\b", " ", t)
    return re.sub(r"\s+", " ", t.replace(" - ", " ")).strip()
tytuly = {}
for n, u in STACJE.items():
    try: tytuly[n] = tytul(u)
    except Exception as e: tytuly[n] = None; print(n, "błąd strumienia:", e)
tytuly["test1"] = "Tiesto & Jonas Blue feat. Rita Ora - Ritual"
tytuly["test2"] = "Tempo Giusto - Paradigm Shift"
tytuly["test3"] = "Sylwia Grzeszczak - Tamta dziewczyna"
for n, t in tytuly.items():
    print(f"== {n}: {t!r}")
    if not t: continue
    for nazwa, f in (("iTunes", itunes), ("Deezer", deezer)):
        try: print(f"   {nazwa}: {f(czysc(t))}")
        except Exception as e: print(f"   {nazwa}: błąd {e}")
