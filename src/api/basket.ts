import { apiRequest } from './client'
import type { Basket } from './types'

export function getBasket() {
  return apiRequest<Basket>('/basket')
}

export function addBasketItem(productId: string, quantity: number) {
  return apiRequest<Basket>('/basket/items', {
    method: 'POST',
    body: JSON.stringify({ productId, quantity }),
  })
}

export function updateBasketQuantity(productId: string, quantity: number) {
  return apiRequest<Basket>(`/basket/items/${productId}`, {
    method: 'PATCH',
    body: JSON.stringify({ quantity }),
  })
}

export function removeBasketItem(productId: string) {
  return apiRequest<Basket>(`/basket/items/${productId}`, { method: 'DELETE' })
}

export function applyBasketCoupon(code: string) {
  return apiRequest<Basket>('/basket/coupon', {
    method: 'PUT',
    body: JSON.stringify({ code }),
  })
}

export function removeBasketCoupon() {
  return apiRequest<Basket>('/basket/coupon', { method: 'DELETE' })
}
