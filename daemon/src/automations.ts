// Automations (docs/PROTOCOL.md, "Automations"; the design is docs/specs/spec-automations.md):
// "When … → Do …" cards that run on the Mac, so they work with the phone off. This file keeps them
// (store), notices when they're due (time triggers through schedule.ts; events off the daemon's own
// broadcast), lets a run through its gates in order, starts or prompts its sessions and follows
// them to the end, and answers the routes.
//
// The engine is a class over an [AutoIO], as Stay awake's keeper is: everything that touches the
// daemon (sessions, the account, the clock, the disk) goes through it, so the tests drive time and
// events by hand and never start a real claude. The daemon's own wiring is at the bottom.
//
// Never logged: prompts, results and notes' texts. The log names ids and states only.
//
// The code is in automations/: contract.ts (the shapes, limits and errors), text.ts (rules,
// results, placeholders), engine.ts (the engine) and daemon.ts (its wiring to the daemon).
export * from './automations/contract.ts'
export * from './automations/text.ts'
export * from './automations/engine.ts'
export * from './automations/daemon.ts'
