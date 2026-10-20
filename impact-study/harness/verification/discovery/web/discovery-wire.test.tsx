/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { afterEach, expect, test, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { BasketPage } from '../pages/BasketPage'
import { ProductsPage } from '../pages/ProductsPage'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

test('PROTECTED_WIRE_CONSUMPTION', async () => {
 const input=readFileSync(process.env.WIRE_INPUT!, 'utf8')
 const basket=process.env.WIRE_KIND === 'basket'
 const path=basket?'/api/v1/basket':'/api/v1/products?page=0&size=20'
 const requests:string[]=[]
 vi.stubGlobal('fetch',vi.fn(async (url:string) => {
  requests.push(String(url));
  if(url!==path) throw new Error(`Unexpected transport ${url}`)
  return new Response(input,{status:200,headers:{'Content-Type':'application/json'}})
 }))
 const {container}=render(<MemoryRouter>{basket?<BasketPage/>:<ProductsPage/>}</MemoryRouter>)
 if(basket) {
  await screen.findByText('Basket total')
  expect(container.querySelector('.total-row dd')?.textContent,'BASKET_VISIBLE_TOTAL').toBe('₹123.45')
 } else {
  await screen.findByText('Bag')
  await waitFor(() => expect(container.querySelectorAll('.product-card')).toHaveLength(2))
  const cards=Array.from(container.querySelectorAll('.product-card'))
  expect(cards.map(c=>c.querySelector('h2')?.textContent),'PRODUCT_ORDER').toEqual(['Bag','Cup'])
  expect(cards.map(c=>c.querySelector('img')?.getAttribute('src')),'PRODUCT_IMAGES').toEqual(['https://images.example/bag.png','https://images.example/cup.png'])
  expect(cards.map(c=>c.querySelector('strong')?.textContent),'PRODUCT_PRICES').toEqual(['₹123.45','₹67.89'])
 }
 expect(requests).toEqual([path])
})
