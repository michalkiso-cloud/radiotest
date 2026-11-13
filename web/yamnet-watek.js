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
    const okna = [];
    for (let st = 0; st + OKNO <= x.length; st += OKNO / 2) {
      const kat = klas.classify(x.subarray(st, st + OKNO), 16000)?.[0]?.classifications?.[0]?.categories || [];
      let mowa = 0, muz = 0;
      for (const c of kat) { if (c.index === IDX_MOWA) mowa = c.score; else if (c.index === IDX_MUZYKA) muz = c.score; }
      okna.push([st, mowa, muz]);
    }
    postMessage({ id, okna });
  };
})();
