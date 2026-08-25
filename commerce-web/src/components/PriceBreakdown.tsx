import type { Quote } from '../api/types'
import { money } from '../utils/format'

export function PriceBreakdown({ quote }: { quote: Quote }) {
  const { price } = quote
  return (
    <dl className="price-breakdown">
      <div><dt>Subtotal</dt><dd>{money(price.subtotalMinor, price.currency)}</dd></div>
      <div><dt>Discount</dt><dd>−{money(price.discountMinor, price.currency)}</dd></div>
      <div><dt>Shipping</dt><dd>{money(price.shippingMinor, price.currency)}</dd></div>
      <div><dt>Tax</dt><dd>{money(price.taxMinor, price.currency)}</dd></div>
      <div className="total-row"><dt>Total</dt><dd>{money(price.totalMinor, price.currency)}</dd></div>
    </dl>
  )
}
