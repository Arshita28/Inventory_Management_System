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

## Truck intro landing page

The supplied `landing page.mp4` (1920×1080, approximately 3.453 seconds) is preserved as `assets/landing-intro.mp4`. At `/`, it plays once, muted and inline. On the actual `ended` event the video pauses, its matching last-frame still remains visible, and a 0.85-second fade/slide introduces the welcome text, Get started and How it works in the black ground below the truck. The original clip is not looped, redrawn, cropped, or sped up. The final still was extracted from the last video frame using AVFoundation.

The full 16:9 scene scales with the page width so the whole truck stays in view, with black ground continuing below on tall screens. Get started opens `/app`; How it works smoothly scrolls to a section below the intro, with three numbered cards explaining the existing workflow in the charcoal, olive and lime theme. Native anchor navigation also works without JavaScript; reduced-motion preferences disable smooth scrolling. Application filters and write redirects target `/app`, while old root query URLs remain compatible. No login has been added.

The landing JavaScript is limited to playback and modal behavior; Java services and persistence still run the application. Reduced-motion preferences show the final still immediately. Autoplay rejection displays a Play intro control; unavailable media falls back to the still and entry links. Video delivery supports validated byte ranges for seeking. Changes remain local until explicitly requested for publishing.

Verification: all 73 Java tests passed (zero failures/errors). HTTP checks cover separate landing/application pages, video byte ranges and invalid-range rejection. Browser checks confirmed `ended=true`, `paused=true`, `currentTime=duration=3.45345`, no loop, and content opacity 0 during playback then 1 after completion. How it works opens the workflow explanation; Get started opens `/app`. The bundled video matches the uploaded original byte-for-byte.

## Sidebar application workspace

The `/app` Home view now shares the charcoal/olive card surfaces, pale text and lime accents used by the report panels. The previous conveyor hero background is removed from the application. The separate truck intro landing at `/` retains its accepted playback and scrolling How it works section.

A persistent sidebar provides Home, Dashboard, Analytics, Management, Replenishment, Purchase orders and Suppliers. Java renders only navigation permitted for the current actor. A small `workspace.js` progressively enhances the server-rendered content by displaying one view at a time, keeping native fragment URLs, browser history, deep links and query-based forms. Without JavaScript, all sections remain accessible through the sidebar anchors. Filters, selected SKUs and validation responses choose the relevant view. The sidebar becomes a compact icon rail on narrow screens, with accessible link names and tooltips retained.

Verification: all 73 Java tests pass. Browser checks switched through all seven views, confirming a single visible view and matching active navigation for each, with no horizontal page overflow at the reviewed width. No inventory or supplier transaction was created during visual verification.

### Navigation and icon cleanup
Landing calls to action now use centered, equally sized text-only buttons. The Inventory logo returns to the landing intro, replacing the separate Back to welcome link. The application header shows only the selected page name. Compact 16-pixel SVG line icons replace oversized font arrow glyphs throughout report and Home links.

## Login in the landing content area

Login replaces Get started. Clicking it shows the role/username/password form in the same black area; the truck final frame remains above it. Admin, Manager and Staff choices are checked against persisted Java account roles. First use provides one-time Admin setup; subsequent accounts are administered from People & access. The app sidebar includes a POST-based Logout control at the bottom left. Password input behavior follows GOV.UK's documented labels, autofill and Show/Hide pattern; password/session behavior follows OWASP. See `authentication.md` for the complete implementation and source references.
