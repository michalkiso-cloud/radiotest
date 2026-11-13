// Procesory dźwięku (AudioWorklet) strony radia.

const SR16 = 16000;

// ---------- Wyrównywanie głośności (jak Glosnosc.kt): pomiar LUFS z filtrem K, powolne dociąganie, limiter ----------
function polka(fs) {
  const gDb = 3.99984385397, q = 0.7071752369554193, fc = 1681.9744509555319;
  const a = Math.pow(10, gDb / 40), w = 2 * Math.PI * fc / fs, al = Math.sin(w) / (2 * q), c = Math.cos(w);
  const b0 = a * ((a + 1) + (a - 1) * c + 2 * Math.sqrt(a) * al);
  const b1 = -2 * a * ((a - 1) + (a + 1) * c);
  const b2 = a * ((a + 1) + (a - 1) * c - 2 * Math.sqrt(a) * al);
  const a0 = (a + 1) - (a - 1) * c + 2 * Math.sqrt(a) * al;
  const a1 = 2 * ((a - 1) - (a + 1) * c);
  const a2 = (a + 1) - (a - 1) * c - 2 * Math.sqrt(a) * al;
  return [b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0];
}
function gornoprzep(fs) {
  const q = 0.5003270373253953, fc = 38.13547087613982;
  const w = 2 * Math.PI * fc / fs, al = Math.sin(w) / (2 * q), c = Math.cos(w), a0 = 1 + al;
  return [(1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - al) / a0];
}

class Wyrownywacz extends AudioWorkletProcessor {
  constructor() {
    super();
    this.wlaczone = true; this.cel = -10; this.maks = 1.5; this.korekta = 0;
    this.s1 = polka(sampleRate); this.s2 = gornoprzep(sampleRate);
    this.z = [new Float64Array(4), new Float64Array(4)];
    this.blokRamek = Math.max(1, Math.round(sampleRate * 0.4));
    this.wBloku = 0; this.sumaBloku = 0;
    this.energia = -1; this.blokowOdStartu = 0; this.poziom = NaN;
    this.g = 1; this.gCel = 1; this.krokG = 0; this.wzm = 0; this.obwiednia = 0;
    this.port.onmessage = (e) => {
      const d = e.data;
      if (d.ust) Object.assign(this, d.ust);
      if (d.reset !== undefined) this.reset(d.reset);
    };
  }
  reset(startDb) {
    this.energia = -1; this.blokowOdStartu = 0; this.poziom = NaN;
    this.wBloku = 0; this.sumaBloku = 0;
    this.g = Math.pow(10, startDb / 20); this.gCel = this.g; this.krokG = 0; this.wzm = startDb;
  }
  filtrK(x, s) {
    const a = this.s1, b = this.s2;
    const y1 = a[0] * x + s[0];
    s[0] = a[1] * x - a[3] * y1 + s[1];
    s[1] = a[2] * x - a[4] * y1;
    const y2 = b[0] * y1 + s[2];
    s[2] = b[1] * y1 - b[3] * y2 + s[3];
    s[3] = b[2] * y1 - b[4] * y2;
    return y2;
  }
  koniecBloku() {
    const ms = this.sumaBloku / this.wBloku;
    this.wBloku = 0; this.sumaBloku = 0; this.blokowOdStartu++;
    const lufs = -0.691 + 10 * Math.log10(ms + 1e-12);
    if (lufs > -60) {
      const tau = this.blokowOdStartu < 20 ? 3 : 40, a = 0.4 / tau;
      this.energia = this.energia < 0 ? ms : this.energia + a * (ms - this.energia);
      this.poziom = -0.691 + 10 * Math.log10(this.energia + 1e-12);
    }
    const teraz = 20 * Math.log10(this.g);
    if (!(this.wlaczone && isNaN(this.poziom))) {
      const autoDb = this.wlaczone ? Math.min(this.cel - this.poziom, this.maks) : 0;
      const celDb = autoDb + this.korekta;
      const maxKrok = this.blokowOdStartu < 20 ? 1.0 : 0.1;
      const nowyDb = teraz + Math.max(-maxKrok, Math.min(maxKrok, celDb - teraz));
      this.wzm = nowyDb;
      this.gCel = Math.pow(10, nowyDb / 20);
      this.krokG = (this.gCel - this.g) / this.blokRamek;
    }
    this.port.postMessage({ poziom: this.poziom, wzmocnienie: this.wzm });
  }
  process(inputs, outputs) {
    const we = inputs[0], wy = outputs[0];
    if (!we || we.length === 0) { for (const c of wy) c.fill(0); return true; }
    const n = we[0].length, k = Math.min(2, we.length);
    const prog = 0.944, zw = 1 - 1 / (sampleRate * 0.15);
    for (let i = 0; i < n; i++) {
      let e = 0;
      for (let ch = 0; ch < k; ch++) { const y = this.filtrK(we[ch][i], this.z[ch]); e += y * y; }
      if (k === 1) e *= 2;   // mono liczone jak dwa kanały
      this.sumaBloku += e;
      if (++this.wBloku >= this.blokRamek) this.koniecBloku();
      if (this.krokG !== 0) {
        this.g += this.krokG;
        if ((this.krokG > 0 && this.g >= this.gCel) || (this.krokG < 0 && this.g <= this.gCel)) { this.g = this.gCel; this.krokG = 0; }
      }
      let szczyt = 0;
      for (let ch = 0; ch < k; ch++) szczyt = Math.max(szczyt, Math.abs(we[ch][i] * this.g));
      this.obwiednia = szczyt > this.obwiednia ? szczyt : this.obwiednia * zw + szczyt * (1 - zw);
      const gl = this.obwiednia > prog ? this.g * prog / this.obwiednia : this.g;
      for (let ch = 0; ch < wy.length; ch++) wy[ch][i] = we[Math.min(ch, k - 1)][i] * gl;
    }
    return true;
  }
}
registerProcessor("wyrownywacz", Wyrownywacz);

// ---------- RMF24: bufor 180 s, próbki 16 kHz do YAMNet, odtwarzanie z opóźnieniem (jak Nasluch + OdtwarzaczRmf) ----------
const BUFOR_S = 180;
const PACZKA = 8000;   // 0,5 s przy 16 kHz

class Rmf extends AudioWorkletProcessor {
  constructor() {
    super();
    this.dl = sampleRate * BUFOR_S;
    this.buf = [new Float32Array(this.dl), new Float32Array(this.dl)];
    this.krok = sampleRate / SR16;
    this.zeruj();
    this.grane = false; this.poz = 0; this.cel = Infinity; this.zgloszono = false;
    this.licznik = 0;
    this.port.onmessage = (e) => {
      const d = e.data;
      if (d.reset) this.zeruj();
      if (d.start !== undefined) {
        const min = Math.max(0, this.zapisane - this.dl + sampleRate);
        this.poz = Math.max(min, Math.min(this.zapisane, d.start));
        this.grane = true; this.cel = Infinity; this.zgloszono = false;
        this.stan();
      }
      if (d.stop) this.grane = false;
      if (d.cel !== undefined) { this.cel = d.cel; this.zgloszono = false; }
    };
  }
  zeruj() {
    this.zapisane = 0; this.nWe = 0; this.nWy = 0; this.poprzednia = 0;
    this.paczka = new Float32Array(PACZKA); this.wPaczce = 0; this.ramkaPaczki = 0;
  }
  stan() { this.port.postMessage({ stan: true, zapisane: this.zapisane, poz: this.poz, grane: this.grane, sr: sampleRate }); }
  process(inputs, outputs) {
    const we = inputs[0], wy = outputs[0];
    const n = wy[0].length;
    if (we && we.length > 0 && we[0].length > 0) {
      const k = Math.min(2, we.length);
      for (let i = 0; i < n; i++) {
        const p = this.zapisane % this.dl;
        const l = we[0][i], r = we[k - 1][i];
        this.buf[0][p] = l; this.buf[1][p] = r;
        this.zapisane++;
        const x = (l + r) / 2;
        while (this.nWy * this.krok <= this.nWe) {
          const t = Math.max(0, Math.min(1, this.nWy * this.krok - (this.nWe - 1)));
          if (this.wPaczce === 0) this.ramkaPaczki = this.zapisane - 1;
          this.paczka[this.wPaczce++] = this.poprzednia + (x - this.poprzednia) * t;
          if (this.wPaczce === PACZKA) {
            this.port.postMessage({ pcm: this.paczka, ramka: this.ramkaPaczki }, [this.paczka.buffer]);
            this.paczka = new Float32Array(PACZKA); this.wPaczce = 0;
          }
          this.nWy++;
        }
        this.poprzednia = x;
        this.nWe++;
      }
    }
    for (let i = 0; i < n; i++) {
      if (this.grane && this.poz < this.zapisane) {
        const p = this.poz % this.dl;
        wy[0][i] = this.buf[0][p];
        if (wy.length > 1) wy[1][i] = this.buf[1][p];
        this.poz++;
      } else { wy[0][i] = 0; if (wy.length > 1) wy[1][i] = 0; }
    }
    if (this.grane && !this.zgloszono && this.poz >= this.cel) {
      this.zgloszono = true;
      this.port.postMessage({ naCel: true });
    }
    if (++this.licznik % 40 === 0) this.stan();
    return true;
  }
}
registerProcessor("rmf", Rmf);
