import { Fragment, useEffect, useId, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'

export interface MenuItem {
  label: string
  onSelect: () => void
  /** Final or destructive, such as Cancel want: shown in red. */
  danger?: boolean
  /** Set for a choice among several, such as a category: a menuitemradio, ticked when true. */
  checked?: boolean
}

export interface MenuGroup {
  /** Shown above the group, and names it for screen readers. */
  label?: string
  items: MenuItem[]
}

interface Props {
  /** The trigger's accessible name, such as "More actions for Ramen downtown". */
  label: string
  groups: MenuGroup[]
}

/**
 * A ⋯ button that opens a short menu of less common actions, so a row shows
 * only its main one. Follows the WAI-ARIA menu button pattern: the first item
 * takes focus on open; arrow keys, Home and End move between items; Escape
 * closes and returns focus to the button; Tab or a click outside closes it.
 *
 * Choosing an item closes the menu and puts focus back on the button before
 * the action runs, so a confirm dialog the action opens returns there too.
 */
export default function RowMenu({ label, groups }: Props) {
  const [open, setOpen] = useState(false)
  const trigger = useRef<HTMLButtonElement>(null)
  const menu = useRef<HTMLDivElement>(null)
  const menuId = useId()

  const items = () => Array.from(menu.current?.querySelectorAll<HTMLElement>('[role^="menuitem"]') ?? [])

  // The first item takes focus when the menu opens.
  useEffect(() => {
    if (open) items()[0]?.focus()
  }, [open])

  // A press anywhere outside closes it, without stealing focus from where it went.
  useEffect(() => {
    if (!open) return
    const onDown = (e: PointerEvent) => {
      const target = e.target as Node
      if (!menu.current?.contains(target) && !trigger.current?.contains(target)) setOpen(false)
    }
    document.addEventListener('pointerdown', onDown)
    return () => document.removeEventListener('pointerdown', onDown)
  }, [open])

  function close() {
    setOpen(false)
    trigger.current?.focus()
  }

  function onKeyDown(e: KeyboardEvent) {
    const all = items()
    const at = all.indexOf(document.activeElement as HTMLElement)
    const move = (i: number) => {
      e.preventDefault()
      all[(i + all.length) % all.length]?.focus()
    }
    if (e.key === 'ArrowDown') move(at + 1)
    else if (e.key === 'ArrowUp') move(at - 1)
    else if (e.key === 'Home') move(0)
    else if (e.key === 'End') move(all.length - 1)
    else if (e.key === 'Escape') {
      e.preventDefault()
      // Stops a surrounding Escape handler, such as edit mode's, from acting too.
      e.stopPropagation()
      close()
    } else if (e.key === 'Tab') setOpen(false)
    else if (e.key.length === 1 && /\S/.test(e.key) && !e.ctrlKey && !e.metaKey && !e.altKey) {
      // Type-ahead: the next item, after the focused one and wrapping round,
      // whose label starts with the letter typed.
      const letter = e.key.toLowerCase()
      const next = [...all.slice(at + 1), ...all.slice(0, at + 1)].find((item) =>
        item.textContent?.trim().toLowerCase().startsWith(letter),
      )
      if (next) {
        e.preventDefault()
        next.focus()
      }
    }
  }

  return (
    <span className="relative">
      <button
        ref={trigger}
        type="button"
        aria-label={label}
        title="More actions"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={() => setOpen((o) => !o)}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown' && !open) {
            e.preventDefault()
            setOpen(true)
          }
        }}
        className="btn flex size-8 items-center justify-center rounded-full border-transparent bg-transparent text-stone-500 shadow-none hover:bg-stone-100 dark:bg-transparent dark:text-stone-400 dark:hover:bg-stone-800"
      >
        <svg aria-hidden="true" viewBox="0 0 24 24" className="size-5 fill-current">
          <circle cx="5" cy="12" r="1.75" />
          <circle cx="12" cy="12" r="1.75" />
          <circle cx="19" cy="12" r="1.75" />
        </svg>
      </button>
      {open && (
        <div
          ref={menu}
          id={menuId}
          role="menu"
          aria-label={label}
          onKeyDown={onKeyDown}
          className="card absolute top-full right-0 z-20 mt-1 flex w-52 flex-col p-1.5 text-sm shadow-lg dark:border-stone-700 dark:bg-stone-800 dark:shadow-black/40"
        >
          {groups.map((group, g) => (
            <Fragment key={group.label ?? g}>
              {g > 0 && <div role="separator" className="my-1 border-t border-stone-200 dark:border-stone-700" />}
              <div role="group" aria-label={group.label} className="flex flex-col">
                {group.label && (
                  <span
                    aria-hidden="true"
                    className="px-2.5 pt-1 pb-0.5 text-xs font-medium text-stone-500 dark:text-stone-400"
                  >
                    {group.label}
                  </span>
                )}
                {group.items.map((item) => (
                  <button
                    key={item.label}
                    type="button"
                    role={item.checked === undefined ? 'menuitem' : 'menuitemradio'}
                    aria-checked={item.checked}
                    tabIndex={-1}
                    onClick={() => {
                      close()
                      item.onSelect()
                    }}
                    className={`flex cursor-pointer items-center justify-between gap-2 rounded-lg px-2.5 py-1.5 text-left hover:bg-stone-100 focus:bg-violet-50 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-violet-500 dark:hover:bg-stone-700 dark:focus:bg-violet-950/60 ${
                      item.danger ? 'text-red-700 dark:text-red-400' : 'text-stone-800 dark:text-stone-200'
                    }`}
                  >
                    {item.label}
                    {item.checked && (
                      <svg aria-hidden="true" viewBox="0 0 24 24" className="size-4 fill-none stroke-current stroke-2">
                        <path strokeLinecap="round" strokeLinejoin="round" d="m5 12 5 5 9-10" />
                      </svg>
                    )}
                  </button>
                ))}
              </div>
            </Fragment>
          ))}
        </div>
      )}
    </span>
  )
}
