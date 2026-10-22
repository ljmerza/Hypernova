import type { D1Migration } from "cloudflare:test";
import type { Env } from "../src/api.ts";

declare global {
  namespace Cloudflare {
    interface Env extends EnvWithMigrations {}
  }
}

type EnvWithMigrations = Env & { TEST_MIGRATIONS: D1Migration[] };

export {};
