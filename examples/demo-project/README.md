# Demo project

Open this folder in CPI Workbench and click a file in `inputs/` to load it as the input body.

| Try | Input | What it shows |
|---|---|---|
| `mappings/orders-to-batch.xsl` (XSLT 3.0) | `inputs/orders.xml` | An XSLT 3.0 mapping; its output is `inputs/order-batch.xml`. |
| `scripts/validate-order-batch.groovy` (2.x) | `inputs/order-batch.xml` | Reads XML with `groovy.xml.XmlSlurper`, sets headers and properties. |
| `scripts/validate-order-batch-json.groovy` (2.x) | `inputs/order-batch.json` | The same for a JSON body with `JsonSlurper`. |
| `mappings/order-to-salesorder.xsl` | `inputs/order.xml` | Header `SenderSystem` and property `CustomerGroup` bound to `xsl:param`, `hci:setHeader`, `xsl:include`. |
| `scripts/enrich-order.groovy` (1.x) | `inputs/order.xml` | Value Mapping and Secure Store from `platform/`, MPL. |
| `scripts/route-by-partner.groovy` (2.x) | `inputs/order.xml` with header `SalesChannel` = `web-eu` | Partner Directory from `platform/`. |
| `schemas/salesorder.xsd` | output of `order-to-salesorder.xsl` | XSD validation. |
| Flow Steps tab (sample settings) | the tab's own sample body | Content Modifier (XPath and Camel Simple rows), Filter (`/Order/Item[@qty > 1]`), Router (CPI's `=` in a Non-XML condition), Splitter, then *Gather* on its result (SAP's multi-mapping format). |
| Flow Steps → Splitter, General, XPath `/src:Orders/src:Order xmlns:src="urn:demo:integration:orders:source:v1"` | `inputs/orders.xml` | One message per order; each keeps the envelope and `SourceSystem`. |

The files in `platform/` hold test data only, no real credentials.
