import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { CATEGORIES } from '../types/wants'
import Info from './Info'

// Info is static: it calls no API and has no controls, so there is no
// interaction or failure path to cover — only what it renders.
describe('Info', () => {
  it('introduces the app', () => {
    render(<Info />)

    expect(screen.getByRole('heading', { level: 1, name: 'About Wants' })).toBeInTheDocument()
  })

  it('lists every category with a description', () => {
    render(<Info />)

    const list = within(screen.getByRole('region', { name: 'Categories' })).getByRole('list')
    const items = within(list).getAllByRole('listitem')
    expect(items).toHaveLength(CATEGORIES.length)
    CATEGORIES.forEach((c, i) => {
      expect(within(items[i]).getByText(c)).toBeInTheDocument()
    })
    expect(screen.getByText('Films and shows to watch.')).toBeInTheDocument()
  })

  it('describes the stack', () => {
    render(<Info />)

    const stack = screen.getByRole('region', { name: 'How it is built' })
    expect(within(stack).getByText('API')).toBeInTheDocument()
    expect(within(stack).getByText(/Spring Boot/)).toBeInTheDocument()
    expect(within(stack).getByText('Frontend')).toBeInTheDocument()
    expect(within(stack).getByText(/React and TypeScript/)).toBeInTheDocument()
  })
})
