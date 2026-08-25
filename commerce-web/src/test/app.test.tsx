import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { setTokens } from '../auth/authStore'
import App from '../App'
import type { Address, Basket, Order, Quote } from '../api/types'
import { describe, expect, it, vi } from 'vitest'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const tokens = {
  accessToken: 'access-token',
  refreshToken: 'refresh-token',
  tokenType: 'Bearer',
  expiresInSeconds: 300,
}

const basket: Basket = {
  basketId: 'basket-1',
  basketVersion: 7,
  couponCode: null,
  items: [{
    productId: 'product-1',
    name: 'Basmati Rice 5kg',
    unitPriceMinor: 65000,
    currency: 'INR',
    quantity: 2,
    lineTotalMinor: 130000,
  }],
  subtotalMinor: 130000,
  discountMinor: 13000,
  totalMinor: 117000,
  currency: 'INR',
}

const address: Address = {
  id: 'address-1',
  fullName: 'Raj Mohan',
  line1: '12 Market Road',
  line2: null,
  city: 'Bengaluru',
  state: 'Karnataka',
  postalCode: '560001',
  country: 'IN',
  phoneNumber: '9999999999',
  createdAt: '2026-08-24T10:00:00Z',
  updatedAt: '2026-08-24T10:00:00Z',
}

function quote(): Quote {
  return {
    quoteId: 'quote-1',
    expiresAt: new Date(Date.now() + 600_000).toISOString(),
    basketVersion: 7,
    price: {
      subtotalMinor: 130000,
      discountMinor: 13000,
      shippingMinor: 10000,
      taxMinor: 21060,
      totalMinor: 148060,
      currency: 'INR',
    },
    estimatedDelivery: { from: '2026-08-25', to: '2026-08-26' },
  }
}

const confirmedOrder: Order = {
  orderId: 'order-1',
  status: 'CONFIRMED',
  totalMinor: 148060,
  currency: 'INR',
  items: [{ productId: 'product-1', name: 'Basmati Rice 5kg', unitPriceMinor: 65000, quantity: 2 }],
}

function open(path: string) {
  window.history.replaceState(null, '', path)
  return render(<App />)
}

function checkoutHandler(orderResults: Response[] = []) {
  let orderIndex = 0
  return vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (url === '/api/v1/addresses') {
      return json([address])
    }
    if (url === '/api/v1/basket' && method === 'GET') {
      return json(basket)
    }
    if (url === '/api/v1/checkout/quotes') {
      return json(quote())
    }
    if (url === '/api/v1/orders' && method === 'POST') {
      return orderResults[orderIndex++] ?? json({ orderId: 'order-1', status: 'PENDING' }, 202)
    }
    if (url === '/api/v1/orders/order-1') {
      return json(confirmedOrder)
    }
    throw new Error(`Unexpected request ${method} ${url}`)
  })
}

async function reachPayment(fetchMock: ReturnType<typeof checkoutHandler>) {
  setTokens(tokens)
  vi.stubGlobal('fetch', fetchMock)
  open('/checkout')
  fireEvent.click(await screen.findByRole('button', { name: 'Generate checkout quote' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Continue to payment' }))
  await screen.findByRole('heading', { name: 'Payment' })
}

describe('commerce web', () => {
  it('stores login tokens, redirects to products, and maps failed credentials', async () => {
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(json(tokens))
      .mockResolvedValueOnce(json([]))
    vi.stubGlobal('fetch', fetchMock)
    open('/login')

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'buyer@example.com' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'password123' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => expect(window.location.pathname).toBe('/products'))
    expect(sessionStorage.getItem('commerce.accessToken')).toBe('access-token')
  })

  it('shows a mapped message for failed login', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(json({ status: 401, code: 'INVALID_CREDENTIALS' }, 401)))
    open('/login')
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'buyer@example.com' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('The email or password is incorrect.')
  })

  it('redirects an unauthenticated protected route to login', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>())
    open('/basket')
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/login')
  })

  it('redirects to login when the retry after refresh also returns 401', async () => {
    setTokens(tokens)
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(json({ status: 401, code: 'UNAUTHENTICATED' }, 401))
      .mockResolvedValueOnce(json({ ...tokens, accessToken: 'fresh-access' }))
      .mockResolvedValueOnce(json({ status: 401, code: 'UNAUTHENTICATED' }, 401))
    vi.stubGlobal('fetch', fetchMock)
    open('/basket')
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('changes basket quantity, removes a line, and rerenders totals', async () => {
    setTokens(tokens)
    const updated = { ...basket, basketVersion: 8, items: [{ ...basket.items[0]!, quantity: 3, lineTotalMinor: 195000 }], subtotalMinor: 195000, discountMinor: 19500, totalMinor: 175500 }
    const empty = { ...updated, basketVersion: 9, items: [], subtotalMinor: 0, discountMinor: 0, totalMinor: 0 }
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(json(basket))
      .mockResolvedValueOnce(json(updated))
      .mockResolvedValueOnce(json(empty))
    vi.stubGlobal('fetch', fetchMock)
    open('/basket')

    const quantity = await screen.findByLabelText('Quantity for Basmati Rice 5kg')
    fireEvent.change(quantity, { target: { value: '3' } })
    expect(await screen.findAllByText('₹1,950.00')).not.toHaveLength(0)
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/v1/basket/items/product-1')
    expect(fetchMock.mock.calls[1]?.[1]?.method).toBe('PATCH')

    fireEvent.click(screen.getByRole('button', { name: 'Remove' }))
    expect(await screen.findByRole('heading', { name: 'Your basket is empty' })).toBeInTheDocument()
    expect(fetchMock.mock.calls[2]?.[1]?.method).toBe('DELETE')
  })

  it('offers to replace an already active coupon', async () => {
    setTokens(tokens)
    const couponBasket = { ...basket, couponCode: 'SAVE10' }
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(json(couponBasket))
      .mockResolvedValueOnce(json({ status: 409, code: 'COUPON_ALREADY_APPLIED' }, 409))
    vi.stubGlobal('fetch', fetchMock)
    open('/basket')

    fireEvent.change(await screen.findByLabelText('Coupon code'), { target: { value: 'NEW20' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('SAVE10 is already active.')
    expect(screen.getByRole('button', { name: 'Replace with NEW20' })).toBeInTheDocument()
  })

  it('renders the full checkout price, expiry countdown, and delivery window', async () => {
    setTokens(tokens)
    vi.stubGlobal('fetch', checkoutHandler())
    open('/checkout')
    fireEvent.click(await screen.findByRole('button', { name: 'Generate checkout quote' }))

    expect(await screen.findByRole('heading', { name: 'Your estimated total' })).toBeInTheDocument()
    expect(screen.getByText('Subtotal')).toBeInTheDocument()
    expect(screen.getByText('Discount')).toBeInTheDocument()
    expect(screen.getByText('Shipping')).toBeInTheDocument()
    expect(screen.getByText('Tax')).toBeInTheDocument()
    expect(screen.getByText('Total')).toBeInTheDocument()
    expect(screen.getByText('₹1,480.60')).toBeInTheDocument()
    expect(screen.getByText('25 Aug 2026 – 26 Aug 2026')).toBeInTheDocument()
    expect(screen.getByText(/Expires in/)).toBeInTheDocument()
  })

  it('sends credit card token and reuses the idempotency key for a retry', async () => {
    const fetchMock = checkoutHandler([
      json({ status: 503, code: 'DOWNSTREAM_SERVICE_UNAVAILABLE' }, 503),
      json({ orderId: 'order-1', status: 'PENDING' }, 202),
    ])
    await reachPayment(fetchMock)

    fireEvent.click(screen.getByLabelText(/Declined payment/))
    fireEvent.click(screen.getByRole('button', { name: 'Place order' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Retry place order' }))
    expect(await screen.findByRole('heading', { name: 'Order confirmed' })).toBeInTheDocument()

    const orderCalls = fetchMock.mock.calls.filter(([input, init]) => input === '/api/v1/orders' && init?.method === 'POST')
    expect(orderCalls).toHaveLength(2)
    const firstHeaders = new Headers(orderCalls[0]?.[1]?.headers)
    const secondHeaders = new Headers(orderCalls[1]?.[1]?.headers)
    expect(firstHeaders.get('Idempotency-Key')).toBeTruthy()
    expect(secondHeaders.get('Idempotency-Key')).toBe(firstHeaders.get('Idempotency-Key'))
    expect(JSON.parse(String(orderCalls[0]?.[1]?.body))).toMatchObject({ payment: { method: 'CREDIT_CARD', token: 'tok_declined' } })
  })

  it('stops polling and explains a terminal order status', async () => {
    vi.useFakeTimers()
    setTokens(tokens)
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(json({ ...confirmedOrder, status: 'REJECTED_OUT_OF_STOCK' }))
    vi.stubGlobal('fetch', fetchMock)
    open('/orders/order-1')

    await act(async () => { await Promise.resolve(); await Promise.resolve() })
    expect(screen.getByRole('heading', { name: 'Some items sold out' })).toBeInTheDocument()
    expect(screen.getByText(/stock changed before reservation/)).toBeInTheDocument()
    act(() => { vi.advanceTimersByTime(10_000) })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('returns to the basket when order placement reports BASKET_VERSION_CHANGED', async () => {
    const fetchMock = checkoutHandler([json({ status: 409, code: 'BASKET_VERSION_CHANGED' }, 409)])
    await reachPayment(fetchMock)
    fireEvent.click(screen.getByRole('button', { name: 'Place order' }))

    await waitFor(() => expect(window.location.pathname).toBe('/basket'))
    expect(await screen.findByRole('alert')).toHaveTextContent('Your basket changed after this quote was created.')
  })
})
