---
description: Create a React component with tests, and iterate until the whole suite passes
argument-hint: <ComponentName> [what it should do]
---

Create a React component in this repository's frontend, write tests for it, and do not stop until every test passes.

`$ARGUMENTS` names the component; anything after the name describes what it should do. If no name was given, ask for one before writing anything.

## 1. Read these first

Match what is already here instead of inventing conventions:

- `frontend/src/components/Wants.tsx` — how a component is written
- `frontend/src/components/Wants.test.tsx` — how a component is tested
- `frontend/src/index.css` — the shared `field` and `btn` classes, and the `@theme` colours
- the **Frontend** section of `CLAUDE.md` — the layering rules

## 2. Where the files go

- `frontend/src/components/<Name>.tsx` — one component per file, PascalCase filename matching the component, default export
- `frontend/src/components/<Name>.test.tsx` — colocated beside it

If the component needs data from the backend, that work belongs in its own layer, not in the component:

- `frontend/src/api/<resource>.ts` — one function per endpoint, no state
- `frontend/src/hooks/use<Resource>.ts` — the server state for that resource
- `frontend/src/types/<resource>.ts` — the shapes the API returns and accepts

Do not wire the component into `App.tsx` or a route unless asked to.

## 3. Rules the component must follow

- TypeScript throughout. Props go in an `interface Props`, documented where a name is not self-explanatory.
- `import type` for type-only imports. `verbatimModuleSyntax` is on, so importing a type as a value fails the build.
- **A component never calls `api/` directly.** It reads server state through a hook. Only hooks call `api/`.
- `useState` is for UI state only — a draft field, which row is open, a toggle. Anything that lives on the server belongs in a hook.
- Tailwind for styling. Reuse `field` and `btn` from `index.css`; neither sets padding, so pass your own (`field px-3 py-2`).
- Use `SubmitEvent<HTMLFormElement>` for form submits. `FormEvent` is deprecated in React 19 and the IDE will flag it.
- Give every interactive element an accessible name — a visible label or `aria-label`. The tests query by role and name, so an unnamed control is untestable.
- No `any`. `noUnusedLocals` and `noUnusedParameters` are on, so unused bindings fail the type check.

## 4. Rules the tests must follow

- Vitest with React Testing Library. Import `describe`, `it`, `expect` and `vi` from `vitest` explicitly — globals are off.
- Query by role, label or visible text. Never by class name or test id.
- Drive the UI with `userEvent`, not `fireEvent`.
- Stub the `api/` module with `vi.mock`, never `fetch`. The component and its hook are under test; the transport is not.
- Cover three things at minimum:
  1. what renders from the data or props it is given
  2. each user interaction it supports, one test each
  3. **the failure path** — a rejected API call shows an error and does not discard what the user typed
- No snapshot tests.

## 5. Run until everything is green

```bash
cd frontend
npm test          # vitest run
npx tsc --noEmit
npx oxlint
```

Loop: run the suite, read the failure, fix the component or the test, run it again. Repeat until all three commands are clean.

**Never weaken an assertion, loosen a query or delete a test to reach green.** If a test is genuinely wrong, say so and explain why before changing it. If a test exposes a real bug in the component, fix the component.

Report the actual output. If something still fails after a few attempts, say what fails and what you have ruled out rather than claiming success.

## 6. When it passes

- Say what the component does, its props, and what the tests cover.
- Per the further instructions in `CLAUDE.md`, update `README.md` if this adds a feature worth describing there.
- Leave the work uncommitted unless asked to commit.
