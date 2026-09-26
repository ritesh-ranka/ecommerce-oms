# API Reference

Base URL `http://localhost:8080` · all paths prefixed `/api/v1` · JSON throughout.

Interactive version at **`/swagger-ui.html`** (click **Authorize**, paste an `accessToken`). Machine-readable
spec at `/v3/api-docs`. A runnable walkthrough is in [`../requests.http`](../requests.http); a Postman
collection with auto-captured tokens is in [`../postman_collection.json`](../postman_collection.json).

---

## Conventions

**Success** — every 2xx body is wrapped:

```json
{ "success": true, "message": "Order placed", "data": { } }
```

**Errors** — one shape for every non-2xx, including filter-chain failures:

```json
{
  "timestamp": "2026-09-26T14:12:03Z",
  "status": 409,
  "code": "INSUFFICIENT_STOCK",
  "message": "Insufficient stock for TEE-BLK-M: requested 3, available 1",
  "details": [ { "sku": "TEE-BLK-M", "requested": 3, "available": 1 } ],
  "path": "/api/v1/checkout",
  "traceId": "b7c1e2f0a934"
}
```

Branch on `code`, never on `message`. Every response carries an `X-Trace-Id` header matching `traceId`, which
appears on every log line produced by that request — including asynchronous work that runs after the response.

**Money** is always an object, never a bare number:

```json
{ "amount": 18287.64, "currency": "INR" }
```

**Pagination** — `?page=0&size=20&sort=id,desc`:

```json
{ "content": [], "page": 0, "size": 20, "totalElements": 42, "totalPages": 3, "first": true, "last": false }
```

**Auth** — `Authorization: Bearer <accessToken>`, from `POST /auth/login`. Stateless HS256, 12-hour default TTL.

---

## Error codes

| Code | Status | Meaning |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Field validation; `details` lists `field` / `rejectedValue` / `reason` |
| `MALFORMED_REQUEST` | 400 | Unparseable body, wrong type, missing parameter or header |
| `UNAUTHENTICATED` | 401 | No usable token |
| `INVALID_CREDENTIALS` | 401 | Login rejected — deliberately identical for unknown email and wrong password |
| `TOKEN_INVALID` | 401 | Malformed, forged, or expired token |
| `FORBIDDEN` | 403 | Role does not permit the operation |
| `PAYMENT_DECLINED` | 402 | Gateway declined; stock released, order kept as `PAYMENT_FAILED` |
| `NOT_FOUND` | 404 | Missing **or not yours** — see the note below |
| `DUPLICATE_RESOURCE` | 409 | Unique constraint (email, SKU, slug, warehouse code) |
| `INSUFFICIENT_STOCK` | 409 | `details` carries per-SKU `requested` / `available` |
| `ILLEGAL_TRANSITION` | 409 | Lifecycle move not permitted; the message names what is |
| `CONCURRENT_MODIFICATION` | 409 | Lost the optimistic-lock race after retries; safe to retry |
| `REQUEST_IN_PROGRESS` | 409 | An identical idempotent request is still running |
| `CART_EMPTY` | 422 | Checkout or preview with nothing in the cart |
| `DISCOUNT_NOT_APPLICABLE` | 422 | Unknown, expired, exhausted, below minimum, or out of scope |
| `RETURN_NOT_ALLOWED` | 422 | Not delivered, outside the window, or quantity exceeds what is returnable |
| `BUSINESS_RULE_VIOLATION` | 422 | Domain rule rejected the request |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Same key, different body |
| `PAYMENT_UNCONFIRMED` | 502 | Gateway gave no answer; holds retained, safe to retry with the same key |
| `REFUND_FAILED` | 502 | Gateway refused the refund; the whole operation rolled back |
| `INTERNAL_ERROR` | 500 | Unexpected — never leaks the exception message |

> **404 vs 403.** Requesting another customer's order, or a shipment in a warehouse you are not assigned to,
> returns **404**. A 403 would confirm the resource exists and let anyone enumerate order volume.

---

## 1. Authentication

| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | `/auth/register` | public | Register a customer and receive a token |
| POST | `/auth/login` | public | Exchange credentials for a bearer token |
| GET | `/auth/me` | any | Describe the authenticated caller |

Self-registration always yields `ROLE_CUSTOMER`; privileged roles are seeded or assigned by an admin.

```http
POST /api/v1/auth/login
{ "email": "customer@oms.dev", "password": "Password@123" }
```

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresInSeconds": 43200,
    "user": { "id": 2, "email": "customer@oms.dev", "fullName": "Cara Customer",
              "zone": "WEST", "roles": ["ROLE_CUSTOMER"], "warehouseIds": [] }
  }
}
```

Seeded accounts, all `Password@123`: `admin@oms.dev`, `customer@oms.dev`, `customer2@oms.dev`,
`staff.mum@oms.dev` (Mumbai), `staff.del@oms.dev` (Delhi).

---

## 2. Catalog — public

| Method | Path | Purpose |
|---|---|---|
| GET | `/categories` | Full nested category tree |
| GET | `/categories/{id}` | One category |
| GET | `/products` | Search and filter, paginated |
| GET | `/products/{id}` | Detail with all purchasable variants |

Unauthenticated on purpose: a storefront must be shoppable before anyone signs in.

`GET /products` filters, all optional and composable: `q` (name/description/brand), `categoryId`,
`categorySlug`, `brand`, `minPrice`, `maxPrice`. Filtering by a parent category **includes its descendants**, so
`?categorySlug=apparel` returns items filed under Men and Women.

```http
GET /api/v1/products?categorySlug=audio&maxPrice=10000&size=5
```

The `variants[].id` values from `/products/{id}` are what `POST /cart/items` expects.

---

## 3. Notifications

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/notifications` | CUSTOMER | My messages, newest first |

Produced by the outbox pipeline **after** the triggering response was returned — the simplest way to confirm
the downstream work ran without blocking checkout.

---

## 4. Inventory

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/inventory?variantId=&warehouseId=` | ADMIN, STAFF | Search stock rows |
| GET | `/inventory/variants/{variantId}` | ADMIN, STAFF | Availability across every warehouse |
| GET | `/inventory/low-stock` | ADMIN, STAFF | Rows at or below reorder level |
| GET | `/inventory/{inventoryItemId}/ledger` | ADMIN, STAFF | Append-only movement history |
| PUT | `/admin/inventory` | ADMIN | Set an absolute level (stock-take) |
| POST | `/admin/inventory/adjustments` | ADMIN | Apply a relative correction |

Customers have no access: exact stock counts are competitive intelligence, and a failed checkout already tells
a customer what they need ("requested 3, available 1").

`available` is always **derived** as `on_hand - reserved` and never stored.

```http
GET /api/v1/inventory/variants/9
```

```json
{ "success": true, "data": {
  "variantId": 9, "sku": "TEE-BLK-M", "displayName": "Everyday Cotton Tee — Black / M",
  "totalOnHand": 1, "totalReserved": 0, "totalAvailable": 1,
  "warehouses": [
    { "warehouseId": 1, "warehouseCode": "WH-MUM", "onHand": 1, "reserved": 0, "available": 1 },
    { "warehouseId": 2, "warehouseCode": "WH-DEL", "onHand": 0, "reserved": 0, "available": 0 }
  ] } }
```

Variant 9 is seeded with exactly one unit network-wide — the oversell demo SKU. Call this before and after
`OversellPreventionIT` and the numbers reconcile.

`PUT /admin/inventory` is refused with 422 if the new level is below units already reserved for open orders:
those belong to customers with confirmed orders.

The ledger records `movementType` (`RESERVE`, `RELEASE`, `EXPIRE`, `SALE`, `RETURN_RESTOCK`, `INBOUND`,
`ADJUSTMENT`) with `onHandAfter` and `reservedAfter`, so a discrepancy can be bisected to the movement that
caused it.

---

## 5. Cart

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/cart` | CUSTOMER | Current basket |
| POST | `/cart/items` | CUSTOMER | Add units of a SKU (merges an existing line) |
| PATCH | `/cart/items/{variantId}` | CUSTOMER | Set a line's quantity (absolute) |
| DELETE | `/cart/items/{variantId}` | CUSTOMER | Remove a line |
| DELETE | `/cart` | CUSTOMER | Empty the cart |
| POST | `/cart/preview` | CUSTOMER | Dry-run the full price breakdown |

No endpoint accepts a cart or user id — the basket is always derived from the token, so reading someone else's
is unexpressible rather than merely guarded.

The cart holds **no prices and no stock**. Prices resolve from the live catalog on every read; reservation
begins at checkout, so an abandoned basket cannot deny stock to buyers ready to pay.

### `POST /cart/preview`

Runs the **same pricing engine** checkout runs, so the preview cannot disagree with the charge. Reserves
nothing and creates nothing.

```http
POST /api/v1/cart/preview
{ "couponCode": "SAVE10", "destinationZone": "WEST" }
```

```json
{ "success": true, "data": { "totalUnits": 2, "distinctSkuCount": 1, "pricing": {
  "subtotal":      { "amount": 15998.00, "currency": "INR" },
  "discountTotal": { "amount": 500.00,   "currency": "INR" },
  "taxTotal":      { "amount": 2789.64,  "currency": "INR" },
  "shippingFee":   { "amount": 0.00,     "currency": "INR" },
  "grandTotal":    { "amount": 18287.64, "currency": "INR" },
  "appliedDiscountCode": "SAVE10",
  "lines": [ { "sku": "NIM-BUDS-PRO-WHT", "quantity": 2,
               "unitPrice":     { "amount": 7999.00, "currency": "INR" },
               "lineSubtotal":  { "amount": 15998.00, "currency": "INR" },
               "lineDiscount":  { "amount": 500.00,  "currency": "INR" },
               "lineTax":       { "amount": 2789.64, "currency": "INR" },
               "taxRate": 0.180000,
               "lineTotal":     { "amount": 18287.64, "currency": "INR" } } ] } } }
```

Reading the numbers: 10% of 15998.00 is 1599.80, but `SAVE10` **caps at 500.00**. Tax is 18% of **15498.00** —
the *post-discount* base. Shipping is free because 15498.00 clears the 999.00 threshold, which is tested against
the post-discount subtotal so a coupon cannot buy free delivery.

Per-line `lineDiscount` is the line's **apportioned share** of the order coupon. It is persisted on the order at
checkout, which is what makes a correct partial refund computable later.

Coupons to try: `SAVE10`, `FLAT200` (flat 200 off above 1500), `AUDIO15` (15% on Audio only), `EXPIRED5`
(returns 422 with the reason).

---

## 6. Checkout and orders

| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | `/checkout` | CUSTOMER | Place an order — **requires `Idempotency-Key`** |
| GET | `/orders` | CUSTOMER | My orders, newest first; `?status=` |
| GET | `/orders/{id}` | CUSTOMER, ADMIN | Full order |
| GET | `/orders/by-number/{orderNumber}` | CUSTOMER, ADMIN | Lookup by quotable number |
| POST | `/orders/{id}/cancel` | CUSTOMER, ADMIN | Cancel before dispatch |
| GET | `/admin/orders` | ADMIN | All orders; `?status=` |

### `POST /checkout`

```http
POST /api/v1/checkout
Idempotency-Key: 3f1a9c52-7b44-4a0e-9e7d-2c8f6b1d4e55

{
  "shippingAddress": { "recipientName": "Cara Customer", "line1": "42 Marine Drive, Flat 9B",
                       "city": "Mumbai", "zone": "WEST", "postalCode": "400020", "phone": "9000000002" },
  "paymentMethod": "CARD",
  "paymentToken": "tok_success",
  "couponCode": "SAVE10"
}
```

```json
{ "success": true, "message": "Order placed. Fulfillment, notification, and audit continue asynchronously.",
  "data": { "orderId": 41, "orderNumber": "ORD-20260926-A1B2C3D4E5", "status": "CONFIRMED",
            "grandTotal": { "amount": 18287.64, "currency": "INR" },
            "payment": { "status": "CAPTURED", "method": "CARD", "gatewayReference": "CH_a1b2c3d4e5f6a7b8" },
            "fulfillingWarehouseIds": [1] } }
```

`paymentMethod`: `CARD`, `UPI`, `NET_BANKING`, `WALLET`, `COD` (confirmed without capture).

**`Idempotency-Key` is required.** Same key + same body replays the original response verbatim with
`Idempotent-Replay: true` and performs no further side effects. Same key + a *different* body returns 422.
A failed attempt releases the claim, so a transient decline does not lock the customer out.

**Forcing each branch.** The simulated gateway is deterministic:

| `paymentToken` | Result | Stock | Order | Payment |
|---|---|---|---|---|
| `tok_success` or anything else | 201 | committed | `CONFIRMED` | `CAPTURED` |
| `tok_decline` | 402 `PAYMENT_DECLINED` | **released** | `PAYMENT_FAILED` | `FAILED` |
| `tok_timeout` | 502 `PAYMENT_UNCONFIRMED` | **retained** | `AWAITING_PAYMENT` | `UNCONFIRMED` |
| `tok_error` | 502 `PAYMENT_UNCONFIRMED` | **retained** | `AWAITING_PAYMENT` | `UNCONFIRMED` |
| `tok_slow` | 201 after ~750 ms | committed | `CONFIRMED` | `CAPTURED` |

On an unknown outcome the hold is deliberately retained — releasing it could sell units the customer has
already paid for. The reservation TTL resolves it: stock returns and the order is cancelled automatically.

### `GET /orders/{id}`

Returns the money breakdown, per-line discount share and tax, payment state, `refundedTotal`, `cancellable`,
and `allowedNextStatuses` read straight from the state machine — so a client never has to hard-code the
lifecycle.

```
AWAITING_PAYMENT ──> CONFIRMED ──> PACKED ──> SHIPPED ──> DELIVERED ──> RETURN_REQUESTED ──> RETURNED
       │                  │            │                                        │
       ├──> PAYMENT_FAILED└────────────┴──> CANCELLED                           └──> DELIVERED (rejected)
       └──> CANCELLED (TTL expiry)
```

### `POST /orders/{id}/cancel`

Permitted from `AWAITING_PAYMENT`, `CONFIRMED`, `PACKED`. Once `SHIPPED`, returns 409 `ILLEGAL_TRANSITION` —
the goods are with a carrier and the returns flow is the only route back.

One transaction, **refund before restock**: if the refund fails everything rolls back and the order is
untouched. Also releases the coupon redemption, so a cancelled order does not consume a limited promotion.

---

## 7. Fulfillment

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/fulfillment/queue` | STAFF, ADMIN | My warehouse's work queue; `?status=` |
| GET | `/fulfillment/orders/{orderId}/shipments` | STAFF, ADMIN | Every parcel for an order |
| POST | `/fulfillment/shipments/{shipmentId}/status` | STAFF, ADMIN | Advance a parcel |

Shipments appear here as a result of the **asynchronous** pipeline, so a queue entry is itself evidence that
routing ran after the checkout response.

```http
POST /api/v1/fulfillment/shipments/18/status
{ "status": "PACKED" }
```

`PENDING → PACKED → SHIPPED → DELIVERED`, with `CANCELLED` available before dispatch. A tracking number is
minted at `SHIPPED`. Skipping a step returns 409.

**The order status is derived from the least advanced parcel.** A split allocation produces several parcels that
move independently, so a two-parcel order becomes `SHIPPED` only when both have shipped — never telling the
customer their whole order is on its way while half of it sits on a shelf.

Acting on a shipment in a warehouse you are not assigned to returns **404**.

---

## 7. Returns and refunds

| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | `/orders/{orderId}/returns` | CUSTOMER | Request a full or partial return |
| GET | `/orders/{orderId}/returns` | CUSTOMER, STAFF, ADMIN | Return history for an order |
| GET | `/returns/{returnId}` | CUSTOMER, STAFF, ADMIN | One return request |
| GET | `/returns/pending` | STAFF, ADMIN | Review queue, oldest first |
| POST | `/returns/{returnId}/approve` | STAFF, ADMIN | Refund and restock |
| POST | `/returns/{returnId}/reject` | STAFF, ADMIN | Decline |

A customer may **ask**; only staff or an admin may **approve**. Letting the requester approve would mean
self-service refunds with no inspection of the goods.

```http
POST /api/v1/orders/41/returns
{ "reason": "SIZE_MISMATCH", "comment": "Ordered medium, needed large",
  "lines": [ { "orderLineId": 57, "quantity": 1 } ] }
```

```json
{ "success": true, "data": {
  "id": 3, "orderId": 41, "status": "REQUESTED", "reason": "SIZE_MISMATCH",
  "totalUnits": 1, "totalRefund": { "amount": 9143.82, "currency": "INR" },
  "restockable": true,
  "lines": [ { "orderLineId": 57, "quantity": 1,
               "refund": { "amount": 9143.82, "currency": "INR" } } ] } }
```

`9143.82` is **half of 18287.64** — half of what was actually *paid*, with the discount share and tax both
reversed. Half the list price would be 7999.00, which would under-refund the tax and over-refund the discount.

`reason`: `SIZE_MISMATCH`, `CHANGED_MIND`, `DAMAGED`, `DEFECTIVE`, `WRONG_ITEM_SENT`, `NOT_AS_DESCRIBED`,
`OTHER`. `DAMAGED` and `DEFECTIVE` are refunded but **not restocked** (`restockable: false`) — putting them back
would promise a unit that cannot ship.

Requesting refunds and restocks **nothing**. Rules enforced:

- order must be `DELIVERED`
- within `oms.returns.window-days` (default 7) measured **from delivery**, not placement
- quantity ≤ what is still returnable, counting approved **and pending** claims, so two pending requests cannot
  together return more than was bought

On approve: money first, then restock to the warehouse that shipped the unit, then settle. A gateway refusal
rolls everything back and leaves the request `REQUESTED` for a retry. The order becomes `RETURNED` if every unit
has come back, otherwise `DELIVERED` so the remainder stays returnable. Rejecting returns the order to
`DELIVERED` so the customer can ask again.

Partial refunds sum **exactly**: three single-unit returns of a ₹100 line refund 33.33, 33.33, then 33.34.

---

## 8. Admin — catalog

| Method | Path | Purpose |
|---|---|---|
| POST / PUT / DELETE | `/admin/categories`, `/admin/categories/{id}` | Category tree management |
| GET | `/admin/products` | List including `DRAFT` and `ARCHIVED` |
| GET | `/admin/products/{id}` | Detail regardless of status |
| POST / PUT | `/admin/products`, `/admin/products/{id}` | Create and update |
| DELETE | `/admin/products/{id}` | **Archive** — order lines still reference the variant |
| POST | `/admin/products/{productId}/variants` | Add a SKU |
| PUT | `/admin/variants/{variantId}` | Update a SKU |

Deleting a category with children or products is refused with 422 rather than cascading and orphaning rows.
Re-parenting a category under its own descendant is refused.

---

## 9. Admin — warehouses

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/warehouses` | ADMIN, STAFF | List (staff need to identify their own) |
| POST | `/admin/warehouses` | ADMIN | Create |
| PUT | `/admin/warehouses/{id}` | ADMIN | Update or deactivate |
| POST | `/admin/warehouses/{id}/staff` | ADMIN | Assign a staff member |

Deactivating excludes a warehouse from allocation but preserves its inventory and shipment history. Staff
assignment is what scopes the fulfillment queue.

---

## 10. Admin — pricing

| Method | Path | Purpose |
|---|---|---|
| GET | `/admin/discounts` | List with redemption counters |
| POST | `/admin/discounts` | Create a coupon |
| PUT | `/admin/discounts/{id}` | Update terms or toggle |
| DELETE | `/admin/discounts/{id}` | Deactivate (past redemptions kept for campaign cost) |
| GET | `/admin/tax-rates` | List configured rates |
| PUT | `/admin/tax-rates` | Upsert a category's rate |
| DELETE | `/admin/tax-rates/{id}` | Remove — the category then inherits |

Customers apply a coupon by code but can never enumerate the table, which would hand out every unadvertised
promotion.

```http
POST /api/v1/admin/discounts
{ "code": "WELCOME15", "description": "15% off your first order",
  "type": "PERCENTAGE_OFF", "discountValue": 15.0, "maxDiscount": 750.00, "minOrderValue": 999.00,
  "categoryId": null, "startsAt": "2026-01-01T00:00:00Z", "endsAt": "2027-01-01T00:00:00Z",
  "usageLimit": 1000, "perCustomerLimit": 1 }
```

`type`: `PERCENTAGE_OFF` (`discountValue` is a percentage, optionally capped) or `FLAT_AMOUNT_OFF`
(`discountValue` is an absolute amount, clamped to the basket). `categoryId` restricts to that category **and
its descendants**.

Tax `rate` is a **fraction** — `0.18` means 18%. Rates are sparse: a category with no row inherits from its
nearest ancestor, then from `oms.pricing.default-tax-rate`.

---

## 11. Admin — operations

| Method | Path | Purpose |
|---|---|---|
| GET | `/admin/outbox/stats` | Counts by status plus registered handlers |
| GET | `/admin/outbox/dead-letters` | Events that exhausted their retries |
| POST | `/admin/outbox/{eventId}/redeliver` | Re-run handlers that have not yet succeeded |
| GET | `/admin/audit-events` | Search; `?entityType=&action=` |
| GET | `/admin/audit-events/{entityType}/{entityId}` | Full history, e.g. `Order/41` |
| GET | `/admin/payments/unconfirmed` | Payment reconciliation queue |

Production observability is out of scope; this is the minimum that makes the async design defensible, because
an outbox silently accumulating dead letters is worse than no outbox at all.

```json
{ "success": true, "data": {
  "pending": 0, "processed": 12, "deadLetter": 0,
  "registeredHandlers": ["audit-trail", "customer-notification", "fulfillment-routing"],
  "checkedAt": "2026-09-26T14:12:09Z" } }
```

Redelivery skips handlers that already succeeded, via the `(event, handler)` execution marker, so it cannot
duplicate a notification or a shipment.

Every audit row carries the `traceId` of the request that caused it — including rows written asynchronously
minutes later — so an audit entry leads straight to the full log context.

`/admin/payments/unconfirmed` is the queue an operator checks against the provider's records after a gateway
timeout. In a production build this is where a webhook or reconciliation job would settle them.
