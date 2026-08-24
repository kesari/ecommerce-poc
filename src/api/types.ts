export type TokenPair = {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresInSeconds: number
}

export type Problem = {
  type?: string
  title?: string
  status: number
  detail?: string
  code: string
  correlationId?: string | null
}

export type Product = {
  id: string
  name: string
  description: string | null
  imageUrl: string | null
  priceMinor: number
  currency: string
  active: boolean
}

export type BasketItem = {
  productId: string
  name: string
  unitPriceMinor: number
  currency: string
  quantity: number
  lineTotalMinor: number
}

export type Basket = {
  basketId: string
  basketVersion: number
  couponCode: string | null
  items: BasketItem[]
  subtotalMinor: number
  discountMinor: number
  totalMinor: number
  currency: string
}

export type Address = {
  id: string
  fullName: string
  line1: string
  line2: string | null
  city: string
  state: string
  postalCode: string
  country: string
  phoneNumber: string
  createdAt: string
  updatedAt: string
}

export type AddressInput = Omit<Address, 'id' | 'createdAt' | 'updatedAt'>

export type Quote = {
  quoteId: string
  expiresAt: string
  basketVersion: number
  price: {
    subtotalMinor: number
    discountMinor: number
    shippingMinor: number
    taxMinor: number
    totalMinor: number
    currency: string
  }
  estimatedDelivery: {
    from: string
    to: string
  }
}

export type OrderItem = {
  productId: string
  name: string
  unitPriceMinor: number
  quantity: number
}

export type Order = {
  orderId: string
  status: string
  totalMinor: number
  currency: string
  items: OrderItem[]
}

export type OrderAccepted = {
  orderId: string
  status: string
}
