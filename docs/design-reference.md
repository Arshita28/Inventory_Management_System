# Dashboard design reference and asset provenance

## Reference

The user supplied [Inventory Management Dashboard by Vlad Bahatskyi for VALMAX](https://dribbble.com/shots/27195047-Inventory-Management-Dashboard) and a matching 5.05-second video. Three frames from the local video were inspected with the macOS video decoder to understand the composition.

Applied visual elements: warm sand surroundings, a rounded charcoal application frame, a cinematic conveyor hero, translucent metric cards, white pill navigation, lime highlights, compact reporting cards, segmented stock health, and a category distribution ring. The project name and VoltEdge Electronics company identity remain unchanged.

The reference's warehouse load, write-offs, AI claim, sales goal, and growth percentages were not inserted as fabricated operational data. The app instead shows recorded sales/receipts, confirmed inbound quantities, suppliers, active-product availability, low-stock counts, current retail value, and actual category unit totals.

## Implementation

- `src/main/resources/inventory.html`: semantic application shell and existing workflow slots.
- `src/main/resources/assets/dashboard.css`: readable, responsive theme with keyboard focus, skip navigation, and reduced-motion support.
- `src/main/java/com/inventory/preview/DashboardPresentation.java`: presentation of real operational metrics, stock health, and category distribution.
- `src/main/resources/assets/conveyor-hero.png`: generated decorative background, saved inside the project and bundled in the JAR.

The background is illustrative, not a live warehouse camera. No reference screenshots, author logos, or third-party fonts are embedded. Native SVG handles small interface icons and data visualizations. Java services, validation, JDBC transactions, approvals, stock history, and inventory records continue to drive all working forms and metrics.

Today, Week, and Month links set actual analytics date ranges. Navigation targets the existing dashboard, analytics, catalogue, replenishment, and purchasing sections. The VE menu contains the existing explicit demo-identity controls; it does not imply real authentication.

## Generated asset

Mode: built-in `image_gen` tool; no CLI fallback. Output: `src/main/resources/assets/conveyor-hero.png` (1942 × 809 pixels). The selected output was copied into the workspace from the tool's generated-images directory; the original was retained.

Final generation prompt:

> Use case: stylized-concept. Asset type: wide 3D background image for an inventory dashboard hero, not a screenshot or UI mockup. Create a polished photorealistic studio 3D render of a brushed-metal roller conveyor running diagonally from the bottom-left foreground toward the right background, carrying one large plain kraft cardboard shipping carton at center-right and a smaller carton farther back. Warm sand and muted taupe seamless studio environment, soft diffuse daylight, premium materials, subtle fine-grain texture, realistic soft shadows, metallic rollers with beautiful reflections, contemporary logistics editorial aesthetic. Wide landscape composition, approximately 2.4:1. Keep the upper left third uncluttered beige negative space for white heading text added later by code. Conveyor fills the lower half, main carton visible around the center without filling the entire frame. This is a background asset only. Absolutely no interface, no cards, no charts, no text, no numbers, no lettering, no brand logos, no watermark, no border, no people.

## Verification

All 72 automated tests passed, including asset content types, PNG validity, and rejection of unlisted asset paths. Browser review covered the normal companion-panel width, the 1440-pixel desktop layout needed to compare the three-card reference composition, and the 390-pixel narrow layout. The narrow page width matched its 390-pixel viewport, without page-level horizontal overflow. Tables retain their own horizontal scrolling when needed.

Minor review fixes shortened the hero subtitle to prevent overlap and improved desktop report type size. Final browser verification confirmed that Week selects the actual 2026-11-03 through 2026-11-09 range, the identity menu opens correctly, and reorder navigation lands directly on the low-stock form. In-page navigation uses immediate native anchor scrolling after a smooth-scrolling issue was found during review. The final desktop check measured a 1440-pixel page width within the 1440-pixel viewport and confirmed the hero copy ends above the sales card. Temporary viewport overrides were reset before completion. Test data and existing purchase orders are preserved; no supplier order was transmitted.
