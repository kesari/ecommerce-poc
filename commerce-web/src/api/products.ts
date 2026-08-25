import { apiRequest } from './client'
import type { Product } from './types'

export function listProducts(page: number, size: number) {
  return apiRequest<Product[]>(`/products?page=${page}&size=${size}`, {}, false)
}
