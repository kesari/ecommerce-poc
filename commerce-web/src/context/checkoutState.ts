import { createContext } from 'react'
import type { Quote } from '../api/types'

export type CheckoutState = {
  quote: Quote | null
  setQuote: (quote: Quote | null) => void
}

export const CheckoutContext = createContext<CheckoutState | null>(null)
