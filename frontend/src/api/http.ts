/** Turns a failed response into an Error carrying something readable. */
export async function fail(response: Response): Promise<never> {
  const body = await response.text()
  throw new Error(body || `${response.status} ${response.statusText}`)
}
