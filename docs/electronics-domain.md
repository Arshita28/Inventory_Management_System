# VoltEdge Electronics: business and class design

## Naming decision

VoltEdge Electronics is the fictional company using the inventory application. Keep the existing project name, repository name, Maven coordinates, and artifact names unchanged. Company branding must be presented separately from the application name.

## Business scope

The company sells phone and computer accessories. Purchases replenish inventory from suppliers; sales reduce it. Later phases add device compatibility, warranty handling, serial numbers for selected items, and supplier comparison.

| Category | Example products | Category class | Distinct data or validation |
| --- | --- | --- | --- |
| Charging | Chargers, USB cables, charging pads | `ChargingDetails` | Connector type, supported charging wattage, device compatibility |
| Audio | Headphones, earphones, speakers | `AudioDetails` | Wired/wireless connection, microphone support, battery life when applicable |
| Computer accessories | Keyboards, mice, webcams, USB hubs | `ComputerAccessoryDetails` | Accessory type, connection type, supported operating systems |
| Storage | Pen drives, memory cards, external SSDs | `StorageDetails` | Positive capacity, storage interface, supported devices |
| Phone accessories | Cases, screen protectors, stands | `PhoneAccessoryDetails` | Accessory type and supported phone models; universal stands can be marked universal |
| Power | Power banks, extension boards, surge protectors | `PowerDetails` | Power-device type; battery capacity for power banks or socket count/rated load for power strips |

Charging covers adapters, cables, and charging pads. Power covers portable batteries and power distribution. This gives overlapping-looking products a defined category rule.

## Implemented Java design

The implementation retains common inventory data in `Product`: SKU, name, category, selling price, reorder level, and active state. Stock balances and movements continue to use the existing service and repository contracts.

A `ProductCategory` enum defines six electronics categories plus a migration-only `LEGACY` value. A `ProductDetails` interface provides category-specific behavior. Six immutable Java record classes implement that interface, validate their own fields, and override `summary()`. A product contains its details through composition. Java records are classes with private final component fields and generated accessors; compact constructors enforce validation.

```text
Product
  contains ProductDetails
              |
              +-- ChargingDetails
              +-- AudioDetails
              +-- ComputerAccessoryDetails
              +-- StorageDetails
              +-- PhoneAccessoryDetails
              +-- PowerDetails
```

This demonstrates classes, encapsulation, constructors, interfaces, polymorphism, enums, and composition. Category-specific fields and validation give each class a purpose. The existing immutable `Product` record cannot be extended, so this plan does not assume it can serve as a superclass. Inheritance remains demonstrated through meaningful application hierarchies such as inventory exceptions; it does not need to be forced into every category.

Each SKU represents one sellable variant. A black 1 m cable and a white 2 m cable have separate SKUs and stock balances. Serial numbers, when added, identify individual units beneath a SKU rather than replacing it.

## Implemented workflow

1. New products use a dropdown containing only the six electronics categories.
2. Choose a category and click **Show category fields** before entering the product data. This server-rendered step shows only that category's inputs and needs no JavaScript.
3. Create the product with validated specifications, then receive or sell stock as before.
4. Edit specifications or explicitly classify a legacy product without changing balances, active state, SKU, or movement history.
5. Inspect polymorphic specification summaries in the catalogue and selected-product panel. CSV and JSON exports also include category/specification information.

The server performs category selection through GET requests before the product form is filled. Switching categories reloads the form; choose the category before entering a draft. For Power and Audio, the form labels explain zero-valued fields that do not apply to a subtype.

## Persistence and legacy migration

`V002__electronics_details.sql` adds `details_type` and `details_data` to the existing products table and records version 2 in `schema_migrations`. Existing rows default to `LEGACY`; their original names, category labels, prices, quantities, and movement rows remain unchanged. The migration is idempotent and does not guess missing electronics specifications.

`ProductDetailsCodec` stores explicit category codes and JDK properties data, then reconstructs the matching class through `ProductDetailsFactory`. No Java object serialization or reflection is used. Metadata and specifications are saved in the same repository transaction.

The default browser view contains electronics only. **Catalogue view → Legacy records** shows preserved older items; **All records** combines both. Selecting an old SKU directly also opens its legacy view. An existing legacy product can keep its original category or be explicitly reclassified with complete specifications. New legacy products cannot be created through the browser; typed electronics cannot be downgraded to legacy through services.

## Sample company catalogue

| SKU | Product | Category | Remaining units |
| --- | --- | --- | --- |
| VE-CHG-001 | 65W USB-C fast charger | Charging | 4 |
| VE-AUD-001 | Wireless over-ear headphones | Audio | 15 |
| VE-CMP-001 | Wireless compact keyboard | Computer accessories | 18 |
| VE-STO-001 | 512 GB portable SSD | Storage | 8 |
| VE-PHN-001 | Clear phone case | Phone accessories | 5 |
| VE-PWR-001 | 20000 mAh power bank | Power | 10 |

The repeatable seed starts with 60 units across six products, of which two are low-stock. It does not replace previously saved products or their specifications. The original console demo now uses an electronics charger too. Company branding is separate from the unchanged application and repository names.

## Verification and remaining work

Tests cover category-specific validation, factory reconstruction, all six classes round-tripping through JDBC, migration of an old schema with stock/history, specification edits, relevant category form fields, invalid category rejection, and legacy reclassification. Existing stock safety, permission, and HTTP tests remain in place.

Compatibility is currently searchable through the specification text; warranty tracking, serial numbers, and a dedicated compatibility catalogue remain future work.
