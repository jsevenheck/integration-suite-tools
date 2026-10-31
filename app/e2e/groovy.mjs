// Browser test of the Groovy view in web mode with three engines:
//   cpiwb-xml-he99 serve --http 7777 --ui app/dist
//   java -jar cpiwb-groovy-v1.jar serve --http 7778
//   java -jar cpiwb-groovy-v2.jar serve --http 7779
//   node app/e2e/groovy.mjs <projectDir> [screenshotDir]
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';

const { chromium } = createRequire(import.meta.url)('playwright');
const project = path.resolve(process.argv[2] ?? 'testdata/regression-project');
const shots = process.argv[3];
const url = (process.env.CPIWB_URL ?? 'http://127.0.0.1:7777/')
  + '?engines=groovy-1.x@http://127.0.0.1:7778,groovy-2.x@http://127.0.0.1:7779';
const fail = (m) => { console.error('FAIL: ' + m); process.exitCode = 1; };

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 860 } });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
await page.goto(url);
await page.evaluate(() => localStorage.clear());
await page.reload();
await page.locator('.status', { hasText: 'engine(s) available' }).waitFor({ timeout: 20000 });

// 1) Sample script on Groovy 1.x.
await page.getByRole('tab', { name: 'Groovy Script' }).click();
await page.locator('section.output .badge.ok').waitFor({ timeout: 20000 });
// Monaco renders the output a moment after the badge, so wait for the text itself.
await page.locator('section.output .view-lines', { hasText: 'ProcessedOrder' }).waitFor({ timeout: 10000 })
  .catch(async () => fail('groovy output: ' + (await page.locator('section.output .view-lines').innerText()).slice(0, 200)));
await page.getByRole('tab', { name: /^MPL/ }).click();
if (!(await page.locator('section.output .pad').innerText()).includes('Order')) fail('MPL property missing');
await page.getByRole('tab', { name: /^Console/ }).click();
if (!(await page.locator('section.output .console').innerText()).includes('Processing order 4711')) fail('console output missing');
if (shots) await page.screenshot({ path: path.join(shots, 'g1-groovy-v1.png') });

// 2) Switch to 2.x: the untouched sample switches to the v2 API and still works.
await page.getByLabel('Runtime profile').selectOption('groovy-2.x');
await page.waitForTimeout(500);
await page.locator('section.output .badge.ok').waitFor({ timeout: 20000 });
const script = await page.locator('section.script .view-lines').innerText();
if (!script.includes('com.sap.it.script.v2.api.Message')) fail('v2 sample not loaded');
await page.locator('section.output footer', { hasText: 'Groovy 4.0.29' }).waitFor({ timeout: 10000 });

// 3) A runtime error is reported with its script line.
await page.locator('section.script .view-lines').click({ position: { x: 40, y: 10 } }); // the element is as wide as the longest line
await page.keyboard.press('Control+a');
await page.keyboard.insertText("import com.sap.it.script.v2.api.Message\ndef Message processData(Message message) {\n    def items = null\n    message.setProperty('n', items.size())\n    return message\n}\n");
await page.locator('section.output .badge.bad').waitFor({ timeout: 20000 });
await page.locator('section.output').getByRole('tab', { name: /^Body/ }).click();
const diag = await page.locator('section.output .diag.error').first().innerText();
if (!diag.includes('line 4') || !diag.includes('NullPointerException')) fail('runtime error: ' + diag);
if (shots) await page.screenshot({ path: path.join(shots, 'g2-groovy-error.png') });

// 4) Open the demo project and run the Groovy test case.
await page.getByLabel('Project folder').fill(project);
await page.getByRole('button', { name: 'Open' }).click();
await page.locator('.tree .file', { hasText: 'groovy-enrich-order/case.json' }).click();
// The script typed in step 3 is unsaved, so the case asks before it replaces the script.
await page.getByRole('dialog').filter({ hasText: 'Discard the unsaved changes' }).getByRole('button', { name: 'OK' }).click();
await page.locator('section.output .badge.ok').waitFor({ timeout: 20000 });
await page.getByRole('tab', { name: /^API calls/ }).click();
const calls = await page.locator('section.output .pad').innerText();
if (!calls.includes('getMappedValue') || !calls.includes('getUserCredential')) fail('api calls: ' + calls);
if (shots) await page.screenshot({ path: path.join(shots, 'g3-groovy-case.png') });

// 4a) Compare runtimes: the 1.x demo script uses XmlParser without import, which Groovy 4 rejects.
await page.getByRole('button', { name: 'Compare', exact: true }).click();
await page.locator('section.output .compare .side .badge.bad').waitFor({ timeout: 20000 });
const cmp = await page.locator('section.output .compare').innerText();
if (!cmp.includes('Groovy Script 2.x') || !cmp.includes('XmlParser')) fail('compare: ' + cmp.slice(0, 400));
if (shots) await page.screenshot({ path: path.join(shots, 'g3b-compare.png') });
await page.getByRole('button', { name: 'Comparing' }).click();

// 4a2) A case with an attachment file: the app keeps it while the case is open.
await page.locator('.tree .file', { hasText: 'groovy-attachment/case.json' }).click();
// The previous case's result stays visible until this run ends, so wait for content, not the badge.
await page.locator('.extras', { hasText: '1 attachment' }).waitFor({ timeout: 20000 })
  .catch(() => fail('case extras missing'));
await page.locator('section.output').getByRole('tab', { name: /^Headers/ }).click();
await page.locator('section.output tr', { hasText: 'DN-4711' }).waitFor({ timeout: 20000 })
  .catch(async () => fail('attachment header: ' + (await page.locator('section.output').innerText()).slice(0, 400)));

// 4b) Import an exported iFlow (zip) and open its Groovy step.
if (process.env.CPIWB_IFLOW_ZIP) {
  await page.getByRole('button', { name: 'Import iFlow' }).click();
  await page.locator('.dlg input.mono').fill(process.env.CPIWB_IFLOW_ZIP);
  await page.locator('.dlg').getByRole('button', { name: 'OK' }).click();
  await page.locator('.steps .step', { hasText: 'Enrich order' }).click();
  await page.locator('section.script h2', { hasText: 'enrich.groovy' }).waitFor({ timeout: 10000 });
  const fn = await page.getByLabel('Function name').inputValue();
  if (fn !== 'enrich') fail('function from iFlow step: ' + fn);
  const prof = await page.getByLabel('Runtime profile').inputValue();
  if (prof !== 'groovy-2.x') fail('profile from iFlow step: ' + prof);
  if (shots) await page.screenshot({ path: path.join(shots, 'g5-iflow.png') });
  // A Content Modifier of the iFlow opens in Flow Steps with its rows.
  await page.locator('.steps .step', { hasText: 'Set headers' }).click();
  await page.locator('.src', { hasText: 'Set headers' }).waitFor({ timeout: 10000 })
    .catch(() => fail('Content Modifier from the iFlow did not open in Flow Steps'));
  await page.locator('.steps .step', { hasText: 'Keep items' }).click();
  if ((await page.getByLabel('Filter XPath').inputValue()) !== '/Order/Item') fail('Filter XPath from the iFlow');
  await page.locator('.steps .step', { hasText: 'Gather items' }).click();
  if ((await page.getByLabel('Aggregation Algorithm').inputValue()) !== 'combineAtXPath') fail('Gather algorithm from the iFlow');
  await page.getByRole('tab', { name: 'Groovy Script' }).click();
}

// 4c) Another script starts with processData again, not with the function set for the previous one.
await page.getByLabel('Function name').fill('customFunction');
await page.locator('.tree .file', { hasText: 'route-by-partner.groovy' }).click();
await page.locator('section.script h2', { hasText: 'route-by-partner.groovy' }).waitFor({ timeout: 10000 });
if ((await page.getByLabel('Function name').inputValue()) !== 'processData') fail('function name kept from the previous script');

// 5) A file from inputs/ opens as the Groovy input body.
fs.mkdirSync(path.join(project, 'inputs'), { recursive: true });
fs.writeFileSync(path.join(project, 'inputs/g-input.xml'), '<Order id="g-input-1"/>\n');
await page.getByTitle('Reload file list').click();
await page.locator('.tree .file', { hasText: 'g-input.xml' }).click();
await page.locator('section.input .view-lines', { hasText: 'g-input-1' }).waitFor({ timeout: 10000 })
  .catch(() => fail('inputs/g-input.xml not loaded as Groovy input'));
await page.locator('section.input h2', { hasText: '· g-input.xml' }).waitFor({ timeout: 5000 })
  .catch(() => fail('Groovy input header does not name g-input.xml'));

// 6) A JSON input opens in the Groovy tab, even from the XSLT tab, and is formatted as JSON.
fs.writeFileSync(path.join(project, 'inputs/g-input.json'), '{"order":{"id":"j-1","items":[1,2]}}\n');
await page.getByTitle('Reload file list').click();
await page.getByRole('tab', { name: 'XSLT Mapping' }).click();
await page.locator('.tree .file', { hasText: 'g-input.json' }).click();
await page.locator('section.script').waitFor({ timeout: 10000 })
  .catch(() => fail('JSON input did not open in the Groovy tab'));
await page.locator('section.input').getByRole('button', { name: 'Format' }).click();
await page.locator('section.input .view-lines', { hasText: '"items": [' }).waitFor({ timeout: 5000 })
  .catch(() => fail('Format did not pretty-print the JSON input'));

// 6b) Flow Steps with their sample settings: Content Modifier (XPath on the XML engine, Simple on Camel),
// Router (CPI's Non-XML spelling) and General Splitter.
await page.getByRole('tab', { name: 'Flow Steps' }).click();
await page.getByRole('tab', { name: 'Content Modifier' }).click();
await page.getByRole('button', { name: 'Run step' }).click();
// Both the sample and the imported iFlow's Content Modifier set the header OrderId from /Order/@id.
await page.locator('.res .row', { hasText: 'OrderId' }).filter({ hasText: '4711' }).waitFor({ timeout: 20000 })
  .catch(() => fail('Content Modifier: header OrderId is not 4711'));
await page.getByRole('tab', { name: 'Router' }).click();
await page.getByRole('button', { name: 'Run step' }).click();
await page.locator('.taken-line', { hasText: 'ERP' }).waitFor({ timeout: 10000 })
  .catch(() => fail('Router did not take the ERP route'));
await page.getByRole('tab', { name: 'Splitter' }).click();
await page.getByRole('button', { name: 'Run step' }).click();
await page.locator('.res h3', { hasText: '2 message(s)' }).waitFor({ timeout: 10000 })
  .catch(() => fail('General Splitter did not create 2 messages'));
// The split messages go to Gather; Combine writes SAP's multi-mapping format.
await page.locator('.res h3').getByRole('button', { name: 'Gather' }).click();
// Set explicitly: opening the iFlow's Gather (4b) loaded Combine at XPath.
await page.getByLabel('Incoming Format').selectOption('sameXml');
await page.getByLabel('Aggregation Algorithm').selectOption('combine');
await page.getByRole('button', { name: 'Run step' }).click();
await page.locator('.res pre', { hasText: 'multimap:Message1' }).waitFor({ timeout: 10000 })
  .catch(() => fail('Gather did not combine the split messages'));
await page.getByRole('tab', { name: 'Filter' }).click();
await page.getByLabel('Filter XPath').fill('/Order/Item[@qty > 1]');
await page.getByLabel('Value Type').selectOption('nodelist');
await page.getByRole('button', { name: 'Run step' }).click();
await page.locator('.res h3', { hasText: '1 item(s) selected' }).waitFor({ timeout: 10000 })
  .catch(() => fail('Filter did not select the one item with qty > 1'));
if (shots) await page.screenshot({ path: path.join(shots, 'g4-flow-steps.png') });

// 7) Closing the project closes it in the engines too: runs no longer see its platform data.
await page.getByTitle('Close project').click();
await page.getByLabel('Project folder').waitFor({ timeout: 5000 });
const afterClose = await page.evaluate(async () => {
  const r = await fetch('http://127.0.0.1:7778/rpc', { method: 'POST', headers: { 'content-type': 'application/json', 'x-cpiwb': '1' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'project.list', params: {} }) });
  return (await r.json()).error?.message ?? 'still open';
});
if (!/No project folder is open/.test(afterClose)) fail('Groovy 1.x engine after Close project: ' + afterClose);

if (errors.length) fail('browser errors: ' + errors.join(' | '));
await browser.close();
console.log(process.exitCode ? 'groovy e2e FAILED' : 'groovy e2e passed');
