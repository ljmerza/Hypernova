// Claude's to-do list in a session (docs/PROTOCOL.md, "Session usage and the to-do list"), as its
// own tool calls left it: the TodoWrite list it last wrote, or the tasks it made with TaskCreate
// and moved on with TaskUpdate (Claude Code's newer task list). The phone pins it over the
// composer while a turn runs.
import type { Ev, ToolEv } from './normalize.ts'

export type TodoStatus = 'pending' | 'in_progress' | 'completed'
/** [text]: what it is ("Run the tests"); [active]: how it reads while under way ("Running the tests"). */
export type Todo = { id: string; text: string; active: string | null; status: TodoStatus }

const STATUSES = new Set<TodoStatus>(['pending', 'in_progress', 'completed'])
const str = (v: unknown) => (typeof v === 'string' && v.trim() ? v.trim() : null)

/** TodoWrite's list, as its input has it (null when the input was too big to send whole). */
function written(input: Record<string, unknown>): Todo[] | null {
  const list = input.todos
  if (!Array.isArray(list)) return null
  const out: Todo[] = []
  list.forEach((t, i) => {
    if (!t || typeof t !== 'object') return
    const o = t as Record<string, unknown>
    const text = str(o.content) ?? str(o.subject)
    if (!text) return
    const status = STATUSES.has(o.status as TodoStatus) ? (o.status as TodoStatus) : 'pending'
    out.push({ id: str(o.id) ?? String(i + 1), text, active: str(o.activeForm), status })
  })
  return out
}

/** The task id TaskCreate's result names ("Task #3 created successfully: …"), as Claude Code reads it. */
function createdId(ev: ToolEv): string | null {
  return /Task #(\S+) created successfully/.exec(ev.output ?? '')?.[1] ?? null
}

/**
 * The to-do list after [events], or null when Claude made none. A list whose items are all done
 * is still returned (the phone hides it); one written empty is gone.
 */
export function todosOf(events: Ev[]): { todos: Todo[]; updatedAt: number } | null {
  let list = null as Todo[] | null
  let at = 0
  for (const e of events) {
    if (e.kind !== 'tool' || e.status === 'error') continue
    if (e.name === 'TodoWrite') {
      const w = written(e.input)
      if (w == null) continue
      list = w
      at = e.ts
    } else if (e.name === 'TaskCreate') {
      // Its id is only known from its result.
      const id = createdId(e)
      const text = str(e.input.subject)
      if (!id || !text) continue
      list = (list ?? []).filter((t) => t.id !== id)
      list.push({ id, text, active: str(e.input.activeForm), status: 'pending' })
      at = e.ts
    } else if (e.name === 'TaskUpdate') {
      if (e.status !== 'done') continue
      const id = str(e.input.taskId)
      if (!id || !list) continue
      const status = e.input.status
      if (status === 'deleted') list = list.filter((t) => t.id !== id)
      else
        list = list.map((t) =>
          t.id !== id
            ? t
            : {
                ...t,
                text: str(e.input.subject) ?? t.text,
                active: str(e.input.activeForm) ?? t.active,
                status: STATUSES.has(status as TodoStatus) ? (status as TodoStatus) : t.status,
              },
        )
      at = e.ts
    }
  }
  if (list == null || list.length === 0) return null
  return { todos: list, updatedAt: at }
}
