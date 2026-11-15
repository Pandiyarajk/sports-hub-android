// CDP trace for a page running in an Android WebView: frames begun vs drawn,
// dropped frames and where the frame pipeline spends time, while you swipe.
// Usage: node cdp_trace.mjs <webSocketDebuggerUrl> [seconds=8] [out=trace.json]
// Needs Node 22+ (global WebSocket). The JSON loads in chrome://tracing or Perfetto.
// Author: Pandiyaraj Karuppasamy
// Date: Oct-30-2026
import { writeFileSync } from 'node:fs';
const [ws, secs = '8', out = 'trace.json'] = process.argv.slice(2);
const sock = new WebSocket(ws);
let id = 0;
const waiting = new Map();
const events = [];
let done;
const finished = new Promise((r) => (done = r));
const send = (method, params = {}) =>
  new Promise((res, rej) => {
    const n = ++id;
    waiting.set(n, { res, rej });
    sock.send(JSON.stringify({ id: n, method, params }));
  });
sock.onmessage = (m) => {
  const msg = JSON.parse(m.data);
  if (msg.method === 'Tracing.dataCollected') events.push(...msg.params.value);
  if (msg.method === 'Tracing.tracingComplete') done();
  if (msg.id && waiting.has(msg.id)) {
    const w = waiting.get(msg.id);
    waiting.delete(msg.id);
    msg.error ? w.rej(new Error(JSON.stringify(msg.error))) : w.res(msg.result);
  }
};
await new Promise((r) => (sock.onopen = r));
await send('Tracing.start', {
  traceConfig: {
    recordMode: 'recordAsMuchAsPossible',
    includedCategories: ['cc', 'viz', 'gpu', 'benchmark', 'input', 'disabled-by-default-devtools.timeline.frame', 'devtools.timeline'],
  },
  transferMode: 'ReportEvents',
});
console.log('tracing', secs, 's');
await new Promise((r) => setTimeout(r, Number(secs) * 1000));
await send('Tracing.end');
await finished;
writeFileSync(out, JSON.stringify(events));
const count = new Map();
for (const e of events) count.set(e.name, (count.get(e.name) || 0) + 1);
const pick = (n) => events.filter((e) => e.name === n);
console.log('events', events.length);
for (const n of ['PipelineReporter', 'DroppedFrame', 'Graphics.Pipeline', 'BeginFrame', 'DrawFrame', 'Display::DrawAndSwap', 'Frame', 'NeedsBeginFrameChanged', 'Swap', 'GPUTask', 'CommandBufferStub::OnAsyncFlush', 'WebGLRenderingContext::drawElements', 'EventDispatch', 'LatencyInfo.Flow', 'InputLatency::GestureScrollUpdate']) {
  if (count.get(n)) console.log(n.padEnd(40), count.get(n));
}
// PipelineReporter states: presented vs dropped.
const states = new Map();
for (const e of pick('PipelineReporter')) {
  if (e.ph !== 'b') continue;
  const st = e.args?.chrome_frame_reporter?.state ?? e.args?.data?.state ?? 'unknown';
  states.set(st, (states.get(st) || 0) + 1);
}
console.log('frame states', JSON.stringify([...states]));
// Total GPU main-thread task time.
const gpuDur = events.filter((e) => e.ph === 'X' && /GpuChannel|CommandBuffer|GPUTask|GLES2DecoderImpl|DecoderContext/.test(e.name)).reduce((s, e) => s + (e.dur || 0), 0);
console.log('gpu-ish task ms', (gpuDur / 1000).toFixed(1));
const top = [...count.entries()].sort((a, b) => b[1] - a[1]).slice(0, 25);
console.log('top names', JSON.stringify(top));
sock.close();
process.exit(0);
