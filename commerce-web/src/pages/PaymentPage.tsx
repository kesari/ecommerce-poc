import { useRef, useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { isApiError } from '../api/client'
import { placeOrder, type PaymentToken } from '../api/orders'
import { ErrorNotice } from '../components/ErrorNotice'
import { PriceBreakdown } from '../components/PriceBreakdown'
import { useCheckout } from '../context/useCheckout'

const paymentOptions: Array<{ token: PaymentToken; title: string; detail: string }> = [
  { token: 'tok_success', title: 'Successful payment', detail: 'Simulates an approved card charge.' },
  { token: 'tok_declined', title: 'Declined payment', detail: 'Simulates a card issuer decline.' },
  { token: 'tok_error', title: 'Provider error', detail: 'Simulates a temporary payment error.' },
]

export function PaymentPage() {
  const navigate = useNavigate()
  const { quote } = useCheckout()
  const [token, setToken] = useState<PaymentToken>('tok_success')
  const [error, setError] = useState<string | null>(null)
  const [quoteExpired, setQuoteExpired] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const idempotencyKey = useRef(crypto.randomUUID())

  if (!quote) {
    return <Navigate to="/checkout" replace />
  }
  const activeQuote = quote

  async function submitOrder() {
    setSubmitting(true)
    setError(null)
    setQuoteExpired(false)
    try {
      const order = await placeOrder(activeQuote.quoteId, token, idempotencyKey.current)
      navigate(`/orders/${order.orderId}`, { replace: true })
    } catch (caught) {
      if (isApiError(caught) && (caught.problem.code === 'BASKET_VERSION_CHANGED' || caught.problem.code === 'QUOTE_BASKET_CHANGED')) {
        navigate('/basket', { replace: true, state: { message: caught.message } })
        return
      }
      if (isApiError(caught) && caught.problem.code === 'QUOTE_EXPIRED') {
        setQuoteExpired(true)
      }
      setError(isApiError(caught) ? caught.message : 'The order could not be placed.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="page narrow-page">
      <div className="page-heading"><div><p className="eyebrow">Final step</p><h1>Payment</h1></div></div>
      <div className="payment-layout">
        <section className="panel payment-panel">
          <div className="section-heading"><div><span className="step-number">3</span><h2>Choose a test outcome</h2></div><span className="secure-pill">CREDIT_CARD</span></div>
          <p className="muted">No real card details are collected or stored.</p>
          <div className="payment-options">{paymentOptions.map((option) => <label className={token === option.token ? 'payment-option selected' : 'payment-option'} key={option.token}><input type="radio" name="payment-token" value={option.token} checked={token === option.token} onChange={() => setToken(option.token)} /><span><strong>{option.title}</strong><span>{option.detail}</span><code>{option.token}</code></span></label>)}</div>
          <ErrorNotice message={error} />
          {quoteExpired && <Link className="button" to="/checkout">Regenerate quote</Link>}
          <button className="primary full" type="button" onClick={() => void submitOrder()} disabled={submitting}>{submitting ? 'Placing order…' : error ? 'Retry place order' : 'Place order'}</button>
        </section>
        <aside className="panel payment-summary"><h2>Order total</h2><PriceBreakdown quote={activeQuote} /></aside>
      </div>
    </section>
  )
}
