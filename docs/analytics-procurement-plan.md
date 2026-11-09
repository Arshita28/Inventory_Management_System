# Analytics and procurement implementation plan

Reference: the supplied [Inventory Management in 11 minutes video](https://www.youtube.com/watch?v=0NOER-Lle-0) and [its creator's accompanying notes](https://thinkeduca.com/inventory-management/). The YouTube transcript export was unavailable; the creator's notes were reviewed instead. The application is implementing practical workflows, not reproducing a transcript or claiming all topics are complete.

## Delivery contract

1. Read a consistent snapshot of products, stock movements, supplier offers, and purchase orders.
2. Calculate date-filtered sales units, receipts, daily movement trends, best sellers, and current retail stock value. Retail value is not acquisition cost, revenue, or profit. Do not invent historical prices or sale dates.
3. Show active low-stock goods with recent demand, pending quantities, and explained reorder suggestions. Use supplier lead time and a review period; subtract open order commitments to avoid duplicate replenishment.
4. Create a purchase draft from a low-stock row or supplier offer, preserving quoted supplier, unit cost, and quantity. Submitting an order does not change physical stock.
5. Require another authorized actor to approve it. Use explicit demo identities until real authentication is implemented; never imply these are authenticated users.
6. Receive approved orders, including partial deliveries. Record the order receipt and inventory movement in one transaction. Prevent over-receipt, duplicate receipt effects, and conflicting request-ID reuse.
7. Preserve transition history. Allow cancellation only before any receipt, and retain rejected/cancelled/completed orders.
8. Verify domain rules, date boundaries, money/quantity limits, restarts, atomic rollback, and concurrent requests. Check the connected workflow in the browser.

## Rules and limits

- Six electronics categories remain the default business scope; legacy products remain preserved.
- Active orders (draft, awaiting approval, approved, partially received) count as replenishment commitments. They prevent opening a second active order for the same SKU. Only approved/partially received quantities are labeled approved inbound.
- Default demand window: last 30 calendar days in Asia/Kolkata. At least seven observed history days and nonzero sales are required to use a demand-based suggestion; otherwise use a threshold top-up and do not show a misleading coverage estimate. Analytics accept an inclusive date range and use explicit timezone boundaries.
- Reorder trigger: max(configured threshold, ceil(observed daily demand × lead days) + configured threshold).
- Target: max(trigger + 1, ceil(observed daily demand × (lead days + 14 review days)) + configured threshold).
- Fallback target without sufficient history: max(threshold + 1, 2 × threshold).
- Suggested quantity: max(0, target − on-hand − open commitments), raised to the supplier minimum quantity only when positive. The configured threshold is used as a provisional safety buffer; statistical forecasting is not claimed.
- Purchase drafts and approval decisions are local records, not supplier messages or payments.
- Full sales invoicing, historical revenue/profit, FIFO cost layers, EOQ based on measured carrying/ordering costs, physical stock reconciliation, and real authentication remain separate work. No placeholder figures will be presented for them.

## Completion evidence

Implemented and passed 71 automated tests, including 14 procurement tests and 6 analytics tests, plus 18 HTTP tests. The HTTP tests execute creation, submission, rejected self-approval, independent approval, partial receipt, retry, excessive receipt rejection, and final receipt. JDBC fault injection verifies stock/order/history rollback together. Browser verification completed on localhost:8081:

- Created `PO-B0D20107` for six low-stock chargers from a replenishment card.
- Submitted as `demo-manager`; switched to `demo-approver` and approved independently.
- Received two units, then the remaining four. The order reached RECEIVED (6/6).
- Charger stock changed from 4 to 10; total electronics stock changed from 60 to 66; low-stock products changed from 2 to 1.
- Analytics recorded 52 sold units and 118 received units in the current 30-day window. Current retail stock value became 129834.00.
- Verified the five purchase events (creation, submission, approval, and both receipts) with their actors and timestamps.
- Returned the browser to the manager demo identity and the Analytics section.

A pre-migration database backup is retained under ignored `data/backups`. Existing inventory and prior electronics specifications were preserved. Orders remain local demonstration records, not supplier transmissions. Preserve the project name and keep changes local.
