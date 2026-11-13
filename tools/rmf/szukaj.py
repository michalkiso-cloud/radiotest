# Szukanie strumieni RMF24 lepszej jakości: radio-browser + rmfon + sondowanie wariantów
import urllib.request, json, re, ssl
UA = {"User-Agent": "Python-urllib/3.12", "Icy-MetaData": "1"}
def naglowki(url):
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=10)
        h = r.headers
        dane = r.read(65536); r.close()
        return f"OK {r.status} typ={h.get('Content-Type')} icy-br={h.get('icy-br')} ice-audio={h.get('ice-audio-info')} name={h.get('icy-name')} | {len(dane)} B"
    except Exception as e:
        return f"BŁĄD {e}"
kandydaci = set()
try:
    for host in ["de1.api.radio-browser.info", "nl1.api.radio-browser.info"]:
        d = json.load(urllib.request.urlopen(f"https://{host}/json/stations/byname/rmf24", timeout=15))
        for s in d:
            print("radio-browser:", s["name"], s["bitrate"], s["codec"], s["url_resolved"] or s["url"])
            kandydaci.add(s["url_resolved"] or s["url"])
        break
except Exception as e: print("radio-browser błąd", e)
for u in ["https://www.rmfon.pl/stacje/ajax_playing_main.txt", "https://www.rmfon.pl/json/stations.txt",
          "https://rmfon.pl/stacje/flash_aac_9.xml.txt", "https://www.rmfon.pl/stacje/flash_aac_9.xml.txt"]:
    try:
        t = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0"}), timeout=10).read().decode("utf-8", "ignore")
        print("rmfon", u, "->", t[:300].replace("\n", " "))
        for m in re.findall(r"https?://[^\s\"'<>]*rmf[^\s\"'<>]*", t):
            if "24" in m: kandydaci.add(m)
    except Exception as e: print("rmfon", u, "błąd", e)
for srv in ["rs201-krk", "rs101-krk", "rs102-krk", "rs6-krk2", "rs9-krk2", "rs201-krk-cyfronet", "rs101-krk-cyfronet"]:
    for sciezka in ["RMF24", "rmf_24", "RMF24.mp3", "rmf24", "RMF24_MP3", "RMF24MP3", "rmf_24_mp3", "RMF24.aac"]:
        kandydaci.add(f"https://{srv}.rmfstream.pl/{sciezka}")
for k in sorted(kandydaci):
    w = naglowki(k)
    if not w.startswith("BŁĄD"): print("STRUMIEŃ", k, "=>", w)
print("sprawdzono", len(kandydaci))
