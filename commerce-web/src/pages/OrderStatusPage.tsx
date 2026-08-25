import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getOrder } from '../api/orders'
import { isApiError } from '../api/client'
import type { Order } from '../api/types'
import { ErrorNotice } from '../components/ErrorNotice'
import { money } from '../utils/format'

const terminalStatuses = new Set(['CONFIRMED', 'REJECTED_OUT_OF_STOCK', 'REJECTED_PAYMENT', 'CANCELLED'])

const statusCopy: Record<string, { title: string; detail: string }> = {
  PENDING: { title: 'Order received', detail: 'We are starting your order.' },
  INVENTORY_RESERVATION_PENDING: { title: 'Checking stock', detail: 'We are reserving your items.' },
  INVENTORY_RESERVED: { title: 'Stock reserved', detail: 'Your items are set aside while payment begins.' },
  PAYMENT_PENDING: { title: 'Processing payment', detail: 'The test payment is being processed.' },
  PAYMENT_CHARGED: { title: 'Payment approved', detail: 'Payment succeeded and we are finalising your stock.' },
  INVENTORY_COMMIT_PENDING: { title: 'Finalising stock', detail: 'Reserved items are being committed to your order.' },
  PAYMENT_FAILED: { title: 'Payment needs attention', detail: 'The payment failed and reserved stock will be released.' },
  INVENTORY_RELEASE_PENDING: { title: 'Releasing stock', detail: 'Reserved items are being returned after the payment result.' },
  COMPENSATION_PENDING: { title: 'Correcting the order', detail: 'We are safely reversing completed checkout steps.' },
  PAYMENT_REFUND_PENDING: { title: 'Refund in progress', detail: 'The test payment is being refunded before cancellation.' },
  CONFIRMED: { title: 'Order confirmed', detail: 'Payment and stock are confirmed. Your order is ready for fulfilment.' },
  REJECTED_OUT_OF_STOCK: { title: 'Some items sold out', detail: 'The order could not be completed because stock changed before reservation.' },
  REJECTED_PAYMENT: { title: 'Payment was not approved', detail: 'The order was stopped because the selected test payment was declined or failed.' },
  CANCELLED: { title: 'Order cancelled', detail: 'This order is cancelled and will not be fulfilled.' },
}

export function OrderStatusPage() {
  const { orderId = '' } = useParams()
  const [order, setOrder] = useState<Order | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [timedOut, setTimedOut] = useState(false)

  useEffect(() => {
    let active = true
    let timer: number | undefined
    const startedAt = Date.now()

    async function poll() {
      try {
        const current = await getOrder(orderId)
        if (!active) {
          return
        }
        setOrder(current)
        setError(null)
        if (terminalStatuses.has(current.status)) {
          return
        }
      } catch (caught) {
        if (active) {
          setError(isApiError(caught) ? caught.message : 'Order status could not be refreshed.')
        }
      }
      if (!active) {
        return
      }
      if (Date.now() - startedAt >= 60_000) {
        setTimedOut(true)
        return
      }
      timer = window.setTimeout(() => void poll(), 2000)
    }

    void poll()
    return () => {
      active = false
      if (timer !== undefined) {
        window.clearTimeout(timer)
      }
    }
  }, [orderId])

  const copy = statusCopy[order?.status ?? 'PENDING'] ?? { title: 'Order in progress', detail: 'Your order is moving through checkout.' }
  const terminal = order ? terminalStatuses.has(order.status) : false

  return (
    <section className="page order-page">
      <div className={terminal ? 'status-orb terminal' : 'status-orb'}>{terminal ? '✓' : <span />}</div>
      <p className="eyebrow">Order {orderId.slice(0, 8)}</p>
      <h1>{copy.title}</h1>
      <p className="order-detail">{copy.detail}</p>
      <ErrorNotice message={error} />
      {timedOut && <div className="notice warning">Live updates paused after one minute. Refresh the page to check again.</div>}
      {order && <div className="panel order-card"><div className="order-status"><span>Status</span><strong>{order.status.replaceAll('_', ' ')}</strong></div>{order.items.map((item) => <div className="order-item" key={item.productId}><span>{item.name} × {item.quantity}</span><strong>{money(item.unitPriceMinor * item.quantity, order.currency)}</strong></div>)}<div className="order-total"><span>Total</span><strong>{money(order.totalMinor, order.currency)}</strong></div></div>}
      {terminal && <Link className="button primary" to="/products">Continue shopping</Link>}
    </section>
  )
}
