// Wykrywanie mowy (YAMNet) w osobnym wątku, żeby strona nie przycinała się przy analizie.
self.exports = {}; self.module = { exports: self.exports };
importScripts("/f/mediapipe-audio.js");   // MediaPipe tasks-audio 0.10.14 (Apache 2.0)
(() => {
  const MP = "/npm/@mediapipe/tasks-audio@0.10.14";
  const { FilesetResolver, AudioClassifier } = self.module.exports.FilesetResolver ? self.module.exports : self.exports;
  const IDX_MOWA = 0, IDX_MUZYKA = 132, OKNO = 15600;
  let klas = null, blad = null;
  const gotowy = (async () => {
    try {
      const fs = await FilesetResolver.forAudioTasks(MP + "/wasm");
      klas = await AudioClassifier.createFromOptions(fs, { baseOptions: { modelAssetPath: "/f/yamnet.tflite" }, maxResults: -1, scoreThreshold: 0 });
      postMessage({ gotowy: true });
    } catch (e) { blad = e.message || String(e); postMessage({ blad }); }
  })();
  self.onmessage = async (e) => {
    await gotowy;
    const { id, x } = e.data;
    if (!klas) { postMessage({ id, blad }); return; }
    const t0 = performance.now();
    // cały blok naraz: MediaPipe dzieli go na okna 0,975 s (bez nakładania) – dużo szybciej niż okno po oknie
    const wyn = klas.classify(x, 16000) || [];
    const okna = [];
    for (const r of wyn) {
      const kat = r?.classifications?.[0]?.categories || [];
      let mowa = 0, muz = 0;
      for (const c of kat) { if (c.index === IDX_MOWA) mowa = c.score; else if (c.index === IDX_MUZYKA) muz = c.score; }
      const st = Math.round((r.timestampMs || 0) * 16);
      if (st + OKNO <= x.length + 160) okna.push([st, mowa, muz]);
    }
    const ms = Math.round(performance.now() - t0);
    postMessage({ id, okna, ms });
  };
})();
