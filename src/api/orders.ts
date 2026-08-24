import { apiRequest } from './client'
import type { Order, OrderAccepted } from './types'

export type PaymentToken = 'tok_success' | 'tok_declined' | 'tok_error'

export function placeOrder(quoteId: string, token: PaymentToken, idempotencyKey: string) {
  return apiRequest<OrderAccepted>('/orders', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({
      quoteId,
      payment: { method: 'CREDIT_CARD', token },
    }),
  })
}

export function getOrder(orderId: string) {
  return apiRequest<Order>(`/orders/${orderId}`)
}
