/** What the deadline picker shows: a preset, or a date with an optional time. */
export type DeadlineChoice = 'none' | 'hour' | 'tomorrow' | 'week' | 'date'

/**
 * The picker's fields as they stand. `date` is YYYY-MM-DD and `time` HH:MM,
 * both in local time, as date and time inputs give them; `time` may be empty.
 */
export interface DeadlineDraft {
  choice: DeadlineChoice
  date: string
  time: string
}

export const NO_DEADLINE: DeadlineDraft = { choice: 'none', date: '', time: '' }

const pad = (n: number) => String(n).padStart(2, '0')

/** A Date as the YYYY-MM-DD a date input holds, in local time. */
export function dateField(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** The picker's fields for a deadline the API sent, in local time; none for null. */
export function draftOf(expiresAt: string | null): DeadlineDraft {
  if (expiresAt === null) return NO_DEADLINE
  const d = new Date(expiresAt)
  return { choice: 'date', date: dateField(d), time: `${pad(d.getHours())}:${pad(d.getMinutes())}` }
}

export function sameDraft(a: DeadlineDraft, b: DeadlineDraft): boolean {
  return a.choice === b.choice && a.date === b.date && a.time === b.time
}

/**
 * The moment a draft names, built in local time: null for no deadline, and
 * 'invalid' for a date not yet picked or a moment not in the future, which
 * the API would refuse. A date without a time means noon, or 23:59 when it is
 * today and noon has already passed.
 */
export function deadlineOf(draft: DeadlineDraft, now = new Date()): Date | null | 'invalid' {
  const [y, m, d] = [now.getFullYear(), now.getMonth(), now.getDate()]
  let at: Date
  switch (draft.choice) {
    case 'none':
      return null
    case 'hour':
      at = new Date(now.getTime() + 60 * 60 * 1000)
      break
    case 'tomorrow':
      at = new Date(y, m, d + 1, 12, 0)
      break
    case 'week':
      // Seven days on the calendar, not 168 hours, so a clock change in
      // between keeps the same time of day.
      at = new Date(y, m, d + 7, now.getHours(), now.getMinutes(), now.getSeconds(), now.getMilliseconds())
      break
    case 'date': {
      if (!draft.date) return 'invalid'
      const [py, pm, pd] = draft.date.split('-').map(Number)
      if (draft.time) {
        const [hh, mm] = draft.time.split(':').map(Number)
        at = new Date(py, pm - 1, pd, hh, mm)
      } else {
        const isToday = draft.date === dateField(now)
        at = isToday && now.getHours() >= 12 ? new Date(py, pm - 1, pd, 23, 59) : new Date(py, pm - 1, pd, 12, 0)
      }
      break
    }
  }
  return Number.isNaN(at.getTime()) || at.getTime() <= now.getTime() ? 'invalid' : at
}
