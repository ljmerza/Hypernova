// The Worker's entry point. `createWorker` takes the two things that touch the outside world
// (fetch, for the Cloudflare API, and the clock) so tests can run the exact same handlers against
// a fake DNS API at a chosen moment; the default export wires in the real ones.

import { handleRequest, runMaintenance, type Deps, type Env } from "./api.ts";

export type { Env } from "./api.ts";

export function createWorker(deps: Deps): ExportedHandler<Env> {
  return {
    fetch(request, env) {
      return handleRequest(request, env, deps);
    },
    async scheduled(_controller, env) {
      const report = await runMaintenance(env, deps);
      console.log("maintenance", JSON.stringify(report));
    },
  };
}

export default createWorker({
  fetch: (input, init) => fetch(input, init),
  now: () => Math.floor(Date.now() / 1000),
});
