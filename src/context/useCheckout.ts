import { useContext } from 'react'
import { CheckoutContext } from './checkoutState'

export function useCheckout() {
  const value = useContext(CheckoutContext)
  if (!value) {
    throw new Error('CheckoutProvider is missing')
  }
  return value
}
