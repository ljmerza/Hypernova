// Every test starts from an empty, freshly migrated database: `reset()` wipes the local D1 and
// the migrations are applied again. Without this, one test's names and rate-limit counters would
// leak into the next.
import { applyD1Migrations, env, reset } from "cloudflare:test";
import { beforeEach } from "vitest";

beforeEach(async () => {
  await reset();
  await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
});
