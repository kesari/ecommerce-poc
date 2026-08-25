import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { addBasketItem } from '../api/basket'
import { isApiError } from '../api/client'
import { listProducts } from '../api/products'
import type { Product } from '../api/types'
import { useAuth } from '../auth/useAuth'
import { ErrorNotice } from '../components/ErrorNotice'
import { money } from '../utils/format'

const PAGE_SIZE = 20

export function ProductsPage() {
  const navigate = useNavigate()
  const authenticated = useAuth()
  const [products, setProducts] = useState<Product[]>([])
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [added, setAdded] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    listProducts(page, PAGE_SIZE)
      .then((result) => active && setProducts(result))
      .catch((caught: unknown) => active && setError(isApiError(caught) ? caught.message : 'Products could not be loaded.'))
      .finally(() => active && setLoading(false))
    return () => { active = false }
  }, [page])

  async function add(product: Product) {
    if (!authenticated) {
      navigate('/login', { state: { from: '/products' } })
      return
    }
    setError(null)
    try {
      await addBasketItem(product.id, 1)
      setAdded(product.id)
    } catch (caught) {
      setError(isApiError(caught) ? caught.message : 'The product could not be added.')
    }
  }

  return (
    <section className="page">
      <div className="page-heading">
        <div><p className="eyebrow">Everyday essentials</p><h1>Products worth bringing home</h1></div>
        <p>Simple prices, quick delivery estimates, and a secure test checkout.</p>
      </div>
      <ErrorNotice message={error} />
      {loading ? <div className="loading">Loading products…</div> : (
        <div className="product-grid">
          {products.map((product) => (
            <article className="product-card" key={product.id}>
              <div className="product-image">
                {product.imageUrl ? <img src={product.imageUrl} alt="" /> : <span>{product.name.slice(0, 1)}</span>}
              </div>
              <div className="product-body">
                <h2>{product.name}</h2>
                <p>{product.description ?? 'A dependable pick for your basket.'}</p>
                <div className="product-action">
                  <strong>{money(product.priceMinor, product.currency)}</strong>
                  <button type="button" onClick={() => void add(product)} disabled={!product.active}>{added === product.id ? 'Added' : product.active ? 'Add to basket' : 'Unavailable'}</button>
                </div>
              </div>
            </article>
          ))}
        </div>
      )}
      <div className="pagination">
        <button type="button" onClick={() => setPage((value) => Math.max(0, value - 1))} disabled={page === 0}>Previous</button>
        <span>Page {page + 1}</span>
        <button type="button" onClick={() => setPage((value) => value + 1)} disabled={products.length < PAGE_SIZE}>Next</button>
      </div>
    </section>
  )
}
