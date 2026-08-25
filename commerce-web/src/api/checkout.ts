import { apiRequest } from './client'
import type { Address, AddressInput, Quote } from './types'

export function listAddresses() {
  return apiRequest<Address[]>('/addresses')
}

export function createAddress(address: AddressInput) {
  return apiRequest<Address>('/addresses', {
    method: 'POST',
    body: JSON.stringify(address),
  })
}

export function createQuote(basketId: string, addressId: string) {
  return apiRequest<Quote>('/checkout/quotes', {
    method: 'POST',
    body: JSON.stringify({ basketId, addressId }),
  })
}
