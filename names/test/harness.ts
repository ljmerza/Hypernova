// Everything a test needs: a Worker built on the fake Cloudflare API and a clock it can move,
// a client that sends through that Worker as if from a chosen source address, and the cron.
import { createExecutionContext, createScheduledController, env, waitOnExecutionContext } from "cloudflare:test";
import { NameServiceClient, generateServerKey, type ServerKey } from "../client.ts";
import { createWorker } from "../src/index.ts";
import { FakeCloudflare } from "./fake-cloudflare.ts";

export const API = "https://api.hypernova.observer";
export const PUBLIC_IP = "93.184.216.34";

export class Harness {
  /** Unix seconds; tests move it. */
  now = 1_800_000_000;
  cf = new FakeCloudflare(() => this.now);
  worker = createWorker({ fetch: this.cf.fetch, now: () => this.now });
  /** The source address Cloudflare would report for the next calls. */
  callerIp = PUBLIC_IP;

  /** Sends a raw request through the Worker, as Cloudflare would deliver it. */
  async fetch(url: string, init: RequestInit = {}): Promise<Response> {
    const req = new Request(url, init);
    const headers = new Headers(req.headers);
    if (!headers.has("cf-connecting-ip") && this.callerIp) headers.set("cf-connecting-ip", this.callerIp);
    const ctx = createExecutionContext();
    const res = await this.worker.fetch!(new Request(req, { headers }), env, ctx);
    await waitOnExecutionContext(ctx);
    return res;
  }

  /** A client wired to this Worker. */
  client(): NameServiceClient {
    return new NameServiceClient({ baseUrl: API, fetch: (input, init) => this.fetch(String(input), init), now: () => this.now });
  }

  /** Registers a fresh key and returns everything needed to act as that server. */
  async register(): Promise<{ key: ServerKey; name: string; client: NameServiceClient }> {
    const key = await generateServerKey();
    const client = this.client();
    const reg = await client.register(key.publicKey);
    return { key, name: reg.name, client };
  }

  /** Runs the hourly maintenance once. */
  async maintenance(): Promise<void> {
    const ctx = createExecutionContext();
    await this.worker.scheduled!(createScheduledController({ scheduledTime: this.now * 1000 }), env, ctx);
    await waitOnExecutionContext(ctx);
  }
}

export async function bodyOf(res: Response): Promise<any> {
  return res.json();
}
export const JSON_HEADERS = { "content-type": "application/json" };
