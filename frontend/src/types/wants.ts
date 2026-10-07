export const CATEGORIES = ['FOOD', 'MOVIE', 'GAME', 'TRIP'] as const

export type Category = (typeof CATEGORIES)[number]

export interface User {
  id: number
  username: string
}

export interface Want {
  id: number
  message: string
  category: Category
  user: User
}

export interface WantRequest {
  message: string
  category: Category
  userId: number
}
