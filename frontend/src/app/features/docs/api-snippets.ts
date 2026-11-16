import { CodeSample } from '../../shared/code-block.component';

/** Adres serwera API widziany przez przeglądarkę (ten sam host co aplikacja). */
export function apiBase(): string {
  return typeof window === 'undefined' ? 'https://twoja-domena.pl' : window.location.origin;
}

const KEY = 'nrv_live_TWÓJ_KLUCZ';

export function quickStart(key = KEY, base = apiBase()): CodeSample[] {
  return [
    {
      label: 'curl',
      code: `curl -X POST ${base}/v1/text-to-speech \\
  -H "Authorization: Bearer ${key}" \\
  -H "Content-Type: application/json" \\
  -d '{"text": "Dzień dobry! Tak brzmi Narrivy.", "voice_id": "voice_warm_velvet", "format": "mp3"}' \\
  --output nagranie.mp3`,
    },
    {
      label: 'Python',
      code: `import requests

res = requests.post(
    "${base}/v1/text-to-speech",
    headers={"Authorization": "Bearer ${key}"},
    json={
        "text": "Dzień dobry! Tak brzmi Narrivy.",
        "voice_id": "voice_warm_velvet",
        "format": "mp3",
    },
    timeout=180,
)
res.raise_for_status()
with open("nagranie.mp3", "wb") as f:
    f.write(res.content)`,
    },
    {
      label: 'JavaScript',
      code: `// Node.js 18+ (klucz trzymaj na serwerze, nie w przeglądarce)
import { writeFile } from "node:fs/promises";

const res = await fetch("${base}/v1/text-to-speech", {
  method: "POST",
  headers: {
    Authorization: "Bearer ${key}",
    "Content-Type": "application/json",
  },
  body: JSON.stringify({
    text: "Dzień dobry! Tak brzmi Narrivy.",
    voice_id: "voice_warm_velvet",
    format: "mp3",
  }),
});
if (!res.ok) throw new Error((await res.json()).error.message);
await writeFile("nagranie.mp3", Buffer.from(await res.arrayBuffer()));`,
    },
  ];
}

export function jobSamples(base = apiBase()): CodeSample[] {
  return [
    {
      label: 'curl',
      code: `# 1. Utwórz zadanie (odpowiedź 202 z identyfikatorem)
curl -X POST ${base}/v1/text-to-speech/jobs \\
  -H "Authorization: Bearer ${KEY}" \\
  -H "Content-Type: application/json" \\
  -H "Idempotency-Key: rozdzial-1" \\
  -d '{"text": "Rozdział pierwszy…", "voice_id": "voice_warm_narrator",
       "callback_url": "https://twoja-domena.pl/webhooks/narrivy"}'

# 2. Sprawdź stan (queued → processing → succeeded / failed)
curl ${base}/v1/text-to-speech/jobs/job_… -H "Authorization: Bearer ${KEY}"

# 3. Pobierz nagranie
curl ${base}/v1/text-to-speech/jobs/job_…/audio -H "Authorization: Bearer ${KEY}" --output rozdzial-1.mp3`,
    },
    {
      label: 'Python',
      code: `import time, requests

API = "${base}/v1"
H = {"Authorization": "Bearer ${KEY}"}

job = requests.post(f"{API}/text-to-speech/jobs", headers={**H, "Idempotency-Key": "rozdzial-1"},
                    json={"text": open("rozdzial-1.txt").read(), "voice_id": "voice_warm_narrator"}).json()

while job["status"] in ("queued", "processing"):
    time.sleep(3)
    job = requests.get(f"{API}/text-to-speech/jobs/{job['id']}", headers=H).json()

if job["status"] == "failed":
    raise RuntimeError(job["error"]["message"])
audio = requests.get(f"{API}/text-to-speech/jobs/{job['id']}/audio", headers=H)
open("rozdzial-1.mp3", "wb").write(audio.content)`,
    },
  ];
}

export function webhookSamples(): CodeSample[] {
  return [
    {
      label: 'Python',
      code: `import hmac, hashlib, time

def verify(body: bytes, header: str, secret: str, tolerance: int = 300) -> bool:
    """header = wartość nagłówka Narrivy-Signature: "t=1731700000,v1=5f2b…" """
    parts = dict(p.split("=", 1) for p in header.split(","))
    t, signature = parts["t"], parts["v1"]
    if abs(time.time() - int(t)) > tolerance:
        return False  # stare zdarzenie — możliwe powtórzenie
    expected = hmac.new(secret.encode(), f"{t}.".encode() + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, signature)`,
    },
    {
      label: 'Node.js',
      code: `import crypto from "node:crypto";

// rawBody — surowa treść żądania (Buffer), np. express.raw({ type: "application/json" })
export function verify(rawBody, header, secret, tolerance = 300) {
  const parts = Object.fromEntries(header.split(",").map((p) => p.split("=")));
  if (Math.abs(Date.now() / 1000 - Number(parts.t)) > tolerance) return false;
  const expected = crypto.createHmac("sha256", secret).update(\`\${parts.t}.\`).update(rawBody).digest("hex");
  if (!parts.v1 || parts.v1.length !== expected.length) return false;
  return crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(parts.v1));
}`,
    },
  ];
}
