// CDP probe for a page running in an Android WebView (or any Chrome target):
// rAF frame intervals, long tasks and a sampled CPU profile while you swipe.
// Usage: node cdp_probe.mjs <webSocketDebuggerUrl> [seconds=8] [outPrefix=probe]
// Needs Node 22+ (global WebSocket). Writes <outPrefix>.cpuprofile (open it in DevTools).
// Author: Pandiyaraj Karuppasamy
// Date: Oct-30-2026
import { writeFileSync } from 'node:fs';
const [ws, secs = '8', out = 'probe'] = process.argv.slice(2);
const sock = new WebSocket(ws);
let id = 0;
const waiting = new Map();
const send = (method, params = {}) =>
  new Promise((res, rej) => {
    const n = ++id;
    waiting.set(n, { res, rej });
    sock.send(JSON.stringify({ id: n, method, params }));
  });
sock.onmessage = (m) => {
  const msg = JSON.parse(m.data);
  if (msg.id && waiting.has(msg.id)) {
    const w = waiting.get(msg.id);
    waiting.delete(msg.id);
    msg.error ? w.rej(new Error(JSON.stringify(msg.error))) : w.res(msg.result);
  }
};
await new Promise((r) => (sock.onopen = r));
const ev = async (expr) => (await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })).result.value;

const info = await ev(`(() => {
  const c = document.querySelector('canvas');
  const gl = c && (c.getContext('webgl2') || c.getContext('webgl'));
  return { dpr: devicePixelRatio, css: [innerWidth, innerHeight], canvas: c ? [c.width, c.height] : null,
    aa: gl ? gl.getContextAttributes().antialias : null,
    renderer: gl ? gl.getParameter(gl.RENDERER) : null };
})()`);
console.log('info', JSON.stringify(info));

await ev(`(() => {
  window.__ft = []; window.__lt = []; let last = 0;
  const loop = (t) => { if (last) window.__ft.push(t - last); last = t; if (window.__ft.length < 5000) requestAnimationFrame(loop); };
  requestAnimationFrame(loop);
  try { new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__lt.push(Math.round(e.duration)); }).observe({ type: 'longtask', buffered: false }); } catch {}
  return true;
})()`);
await send('Profiler.enable');
await send('Profiler.setSamplingInterval', { interval: 200 });
await send('Profiler.start');
console.log('recording', secs, 's');
await new Promise((r) => setTimeout(r, Number(secs) * 1000));
const { profile } = await send('Profiler.stop');
const stats = await ev(`(() => {
  const f = window.__ft.slice(); const s = [...f].sort((a,b)=>a-b);
  const q = (p) => Math.round(s[Math.floor(s.length*p)]*10)/10;
  return { frames: f.length, p50: q(0.5), p90: q(0.9), p99: q(0.99), max: Math.round(s[s.length-1]),
    over17: f.filter(x=>x>17).length, over33: f.filter(x=>x>33).length, longtasks: window.__lt };
})()`);
console.log('frames', JSON.stringify(stats));
writeFileSync(out + '.cpuprofile', JSON.stringify(profile));

// Self time per function (url:line:col), top 25.
const self = new Map();
const byId = new Map(profile.nodes.map((n) => [n.id, n]));
const dts = profile.timeDeltas;
profile.samples.forEach((sid, k) => {
  const n = byId.get(sid);
  const cf = n.callFrame;
  const key = `${cf.functionName || '(anon)'} ${cf.url.split('/').pop()}:${cf.lineNumber}:${cf.columnNumber}`;
  self.set(key, (self.get(key) || 0) + (dts[k] || 0));
});
const total = [...self.values()].reduce((a, b) => a + b, 0);
console.log('top self time (ms):');
for (const [k, v] of [...self.entries()].sort((a, b) => b[1] - a[1]).slice(0, 25)) console.log((v / 1000).toFixed(1).padStart(8), ((100 * v) / total).toFixed(1).padStart(5) + '%', k);
sock.close();
process.exit(0);
