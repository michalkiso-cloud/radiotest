// Radio – pośrednik w Cloudflare Workers (wersja 2)
// - serwuje stronę radia (pliki z repozytorium GitHub, więc aktualizacje nie wymagają wklejania),
// - przekazuje strumienie, przedstawiając się jak Python (bez wstawianych reklam), z CORS,
// - przechowuje ustawienia (schowek KV „UST”, jeśli jest podpięty).
const TOKEN = "WSTAW_SWOJ_KLUCZ";   // prywatny klucz dostępu
const PLIKI = "https://raw.githubusercontent.com/michalkiso-cloud/radiotest/claude/project-thread-2jzzc7/web/";
const DOZWOLONE = ["revma.com", "di.fm", "radioftb.net", "smcdn.pl", "radiocmp3.eu", "rmfstream.pl",
  "api.deezer.com", "dzcdn.net", "itunes.apple.com", "mzstatic.com"];
const CORS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "*",
  "Access-Control-Allow-Methods": "GET, PUT, OPTIONS", "Access-Control-Expose-Headers": "*" };
const TYPY = { html: "text/html; charset=utf-8", js: "text/javascript; charset=utf-8", mjs: "text/javascript; charset=utf-8",
  wasm: "application/wasm", tflite: "application/octet-stream", png: "image/png", json: "application/json" };

function tekst(t, status = 200) {
  return new Response(t, { status, headers: { ...CORS, "content-type": "text/plain; charset=utf-8" } });
}

function zalogowany(req, url) {
  if (url.searchParams.get("t") === TOKEN) return true;
  const c = req.headers.get("cookie") || "";
  return c.split(/;\s*/).includes("k=" + TOKEN);
}

async function plik(adres) {
  const r = await fetch(adres, { cf: { cacheTtl: 30, cacheEverything: true } });
  if (!r.ok) return tekst("brak pliku (" + r.status + ")", 404);
  const ext = adres.split("?")[0].split(".").pop();
  const h = new Headers(CORS);
  h.set("content-type", TYPY[ext] || "application/octet-stream");
  h.set("cache-control", ext === "html" ? "no-store" : "max-age=300");
  return new Response(r.body, { headers: h });
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === "OPTIONS") return new Response(null, { headers: CORS });

    // wejście z kluczem: zapamiętaj go w ciasteczku i ukryj w adresie
    if (url.pathname === "/" && url.searchParams.get("k") === TOKEN) {
      return new Response(null, { status: 302, headers: {
        "location": "/", "set-cookie": `k=${TOKEN}; Path=/; Max-Age=31536000; Secure; HttpOnly; SameSite=Lax` } });
    }
    if (!zalogowany(req, url)) {
      return url.pathname === "/" ? tekst("Radio: pośrednik działa ✓") : tekst("brak dostępu", 403);
    }

    if (url.pathname === "/") return plik(PLIKI + "index.html");
    if (url.pathname.startsWith("/f/")) return plik(PLIKI + url.pathname.slice(3).replace(/\.\./g, ""));
    if (url.pathname.startsWith("/npm/")) return plik("https://cdn.jsdelivr.net" + url.pathname.replace(/\.\./g, ""));

    if (url.pathname === "/ust") {
      if (!env.UST) return tekst("brak schowka", 501);
      if (req.method === "PUT") {
        const t = await req.text();
        if (t.length > 200000) return tekst("za duże", 413);
        await env.UST.put("ustawienia", t);
        return tekst("ok");
      }
      const t = await env.UST.get("ustawienia");
      return new Response(t || "{}", { headers: { ...CORS, "content-type": "application/json", "cache-control": "no-store" } });
    }

    if (url.pathname === "/s") {
      const cel = url.searchParams.get("u") || "";
      let host = "";
      try { host = new URL(cel).hostname; } catch { return tekst("zły adres", 400); }
      if (!DOZWOLONE.some(d => host === d || host.endsWith("." + d))) return tekst("host niedozwolony", 403);
      const r = await fetch(cel, { headers: { "User-Agent": "Python-urllib/3.12",
        "Icy-MetaData": url.searchParams.get("icy") ? "1" : "0" } });
      const h = new Headers(r.headers);
      for (const [k, v] of Object.entries(CORS)) h.set(k, v);
      h.set("Cache-Control", "no-store");
      return new Response(r.body, { status: r.status, headers: h });
    }
    return tekst("nie ma", 404);
  }
};
