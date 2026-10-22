// Tests run inside workerd through Cloudflare's Vitest plugin, with a real local D1 and the
// migrations applied in test/setup.ts. The Cloudflare DNS API is never reached: the Worker
// under test is built with a fake fetch (test/fake-cloudflare.ts).
import { defineConfig } from "vitest/config";
import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import path from "node:path";

export default defineConfig(async () => {
  const migrations = await readD1Migrations(path.join(import.meta.dirname, "migrations"));
  return {
    plugins: [
      cloudflareTest({
        wrangler: { configPath: "./wrangler.jsonc" },
        miniflare: {
          bindings: {
            TEST_MIGRATIONS: migrations,
            CF_API_TOKEN: "test-token",
            CF_ZONE_ID: "zone-test",
            CF_API_BASE: "https://cloudflare.invalid/client/v4",
            // Low enough to reach in a test; production uses the defaults in src/api.ts.
            REGISTER_PER_DAY: "10",
            REQUESTS_PER_NAME_PER_HOUR: "30",
            ACME_FIRST_PER_DAY: "3",
            ACME_PER_NAME_PER_WEEK: "5",
          },
        },
      }),
    ],
    test: {
      setupFiles: ["./test/setup.ts"],
      include: ["test/**/*.test.ts"],
    },
  };
});
