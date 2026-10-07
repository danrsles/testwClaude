import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// jest-dom above adds its matchers (toBeInTheDocument, toHaveValue, ...) to
// Vitest's expect and augments the types to match.
//
// Testing Library only registers its own afterEach when Vitest globals are
// enabled. They are not — tests import describe/it/expect explicitly — so the
// unmount is wired up here. Without it, every render stays in the document and
// queries in later tests match elements left behind by earlier ones.
afterEach(cleanup)
