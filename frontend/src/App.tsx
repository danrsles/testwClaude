import Wants from './components/Wants'

/**
 * The app shell: the page frame and nothing else. When there is more than one
 * screen, this is where a router goes, and Wants becomes one route among several.
 */
export default function App() {
  return (
    <main className="mx-auto max-w-3xl px-4 pt-12 pb-16">
      <Wants />
    </main>
  )
}
