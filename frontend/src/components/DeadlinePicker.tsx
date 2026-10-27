import { useId, useState } from 'react'
import { dateField, deadlineOf } from './deadline'
import type { DeadlineChoice, DeadlineDraft } from './deadline'

interface Props {
  /** Names the select; the date and time fields add " date" and " time". */
  label: string
  value: DeadlineDraft
  onChange: (draft: DeadlineDraft) => void
  /** What "no deadline" means here: Reopen can only keep or replace one, not remove it. */
  noneLabel?: string
  autoFocus?: boolean
}

/**
 * When a want closes: a preset, or a date with an optional time. It only
 * holds the fields; `deadlineOf` turns them into a moment. A date that can't
 * be used says why, since the button it disables can't.
 */
export default function DeadlinePicker({ label, value, onChange, noneLabel = 'No deadline', autoFocus }: Props) {
  // Read once, so rendering stays pure. Only a hint to the browser's calendar:
  // a past pick is caught by deadlineOf, which disables the button.
  const [today] = useState(() => dateField(new Date()))
  const hintId = useId()
  const invalid = value.choice === 'date' && deadlineOf(value) === 'invalid'
  const options: [DeadlineChoice, string][] = [
    ['none', noneLabel],
    ['hour', 'In 1 hour'],
    ['tomorrow', 'Tomorrow'],
    ['week', 'In 1 week'],
    ['date', 'Pick a date'],
  ]
  return (
    <>
      <select
        value={value.choice}
        autoFocus={autoFocus}
        onChange={(e) => onChange({ ...value, choice: e.target.value as DeadlineChoice })}
        aria-label={label}
        className="field px-2 py-1.5 text-sm"
      >
        {options.map(([choice, text]) => (
          <option key={choice} value={choice}>
            {text}
          </option>
        ))}
      </select>
      {value.choice === 'date' && (
        <>
          <input
            type="date"
            min={today}
            value={value.date}
            onChange={(e) => onChange({ ...value, date: e.target.value })}
            aria-label={`${label} date`}
            aria-invalid={invalid && value.date !== '' ? true : undefined}
            aria-describedby={invalid ? hintId : undefined}
            className="field px-2 py-1.5 text-sm"
          />
          {/* Optional: left empty, the deadline is noon, or 23:59 for today after noon. */}
          <input
            type="time"
            value={value.time}
            onChange={(e) => onChange({ ...value, time: e.target.value })}
            aria-label={`${label} time`}
            aria-invalid={invalid && value.date !== '' ? true : undefined}
            aria-describedby={invalid ? hintId : undefined}
            title="Optional: noon if left empty"
            className="field px-2 py-1.5 text-sm"
          />
          {invalid && (
            <span id={hintId} className="basis-full text-sm text-red-700 dark:text-red-400">
              {value.date ? "Pick a time that hasn't passed" : 'Choose a date'}
            </span>
          )}
        </>
      )}
    </>
  )
}
