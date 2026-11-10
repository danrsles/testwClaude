import { SignIn } from '@clerk/react'

/**
 * What a signed-out visitor sees instead of the app: Clerk's own sign-in form,
 * which also links to sign-up. Hash routing keeps Clerk's multi-step flow
 * (#/factor-one and so on) off our routes, so it works at any path.
 */
export default function SignInScreen() {
  return (
    <main className="mx-auto flex max-w-3xl flex-col items-center px-4 pt-12 pb-16">
      <h1 className="bg-gradient-to-r from-violet-600 to-emerald-700 bg-clip-text text-4xl font-bold tracking-tight text-transparent dark:from-violet-400 dark:to-emerald-400">
        Yuwant
      </h1>
      <p className="mt-1 mb-8 text-stone-500 dark:text-stone-400">Sign in to see and share wants.</p>
      <SignIn routing="hash" />
    </main>
  )
}
