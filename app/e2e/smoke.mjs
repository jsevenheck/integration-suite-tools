// End-to-end smoke test of the UI against a running engine in web mode:
//   cpiwb-xml serve --http 7777 --ui app/dist   then   node app/e2e/smoke.mjs <projectDir> [screenshotDir]
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';

const project = path.resolve(process.argv[2] ?? 'testdata/regression-project');
const shots = process.argv[3];
// A Saxon-HE 12 engine on 7780 is optional; with it the runtime comparison is tested too.
const url = (process.env.CPIWB_URL ?? 'http://127.0.0.1:7777/') + '?engines=saxon-he-12@http://127.0.0.1:7780';
// Resolve playwright via require so a globally installed copy (NODE_PATH) works too.
const { chromium } = createRequire(import.meta.url)('playwright');
const fail = (m) => { console.error('FAIL: ' + m); process.exitCode = 1; };

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 860 } });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
await page.goto(url);
await page.evaluate(() => localStorage.clear());
await page.reload();

// 1) Scratch mode: the sample mapping runs on load.
await page.locator('.badge.ok').first().waitFor({ timeout: 15000 });
const out = await page.locator('section.output .view-lines').innerText();
if (!out.includes('SalesOrder')) fail('sample output missing SalesOrder: ' + out.slice(0, 200));
await page.getByRole('tab', { name: /^Headers/ }).nth(1).click();
if (!(await page.locator('section.output tr.added').first().innerText()).includes('OrderNumber')) fail('OrderNumber header not marked as added');
if (shots) await page.screenshot({ path: path.join(shots, '1-xslt-scratch.png') });

// 1b) Compare runtimes (only when the second XSLT engine is running).
const compare = page.getByRole('button', { name: 'Compare', exact: true });
if (await compare.isEnabled()) {
  await compare.click();
  await page.locator('section.output .compare .same').waitFor({ timeout: 15000 });
  if (shots) await page.screenshot({ path: path.join(shots, '1b-xslt-compare.png') });
  await page.getByRole('button', { name: 'Comparing' }).click();
} else {
  console.log('note: saxon-he-12 engine not running, comparison not tested');
}

// 2) A compile error shows up with its line.
await page.locator('section.mapping .view-lines').click({ position: { x: 40, y: 10 } }); // the element is as wide as the longest line
await page.keyboard.press('Control+End');
await page.keyboard.type('<broken');
await page.locator('.badge.bad').waitFor({ timeout: 10000 });
await page.getByRole('tab', { name: /^Body/ }).nth(1).click();
if (!(await page.locator('.diag.error').first().innerText()).match(/line \d+/)) fail('compile error without line');
if (shots) await page.screenshot({ path: path.join(shots, '2-xslt-error.png') });

// 3) Open the demo project and a mapping from the tree.
await page.getByLabel('Project folder').fill(project);
await page.getByRole('button', { name: 'Open' }).click();
await page.locator('.tree .file').first().waitFor({ timeout: 10000 });
// The scratch mapping still has the broken edit: opening a file asks first, and Cancel keeps the edit.
const mappingFile = page.locator('.tree .file', { hasText: 'order-to-salesorder.xsl' });
const discard = page.getByRole('dialog').filter({ hasText: 'Discard the unsaved changes' });
await mappingFile.click();
await discard.waitFor({ timeout: 5000 }).catch(() => fail('no question before an unsaved mapping is replaced'));
await discard.getByRole('button', { name: 'Cancel' }).click();
if (!(await page.locator('section.mapping .view-lines').innerText()).includes('<broken')) fail('Cancel did not keep the unsaved mapping');
await mappingFile.click();
await discard.getByRole('button', { name: 'OK' }).click();
// The title holds the bare path; the visible text may end with the unsaved-changes dot.
await page.locator('section.mapping h2[title="mappings/order-to-salesorder.xsl"]').waitFor();

// 2b) Save as… suggests a free name next to the open file and leaves the open file as it is.
const xslBefore = fs.readFileSync(path.join(project, 'mappings/order-to-salesorder.xsl'), 'utf8');
await page.getByRole('button', { name: 'Save as…' }).click();
const suggested = await page.getByRole('dialog').getByRole('textbox').inputValue();
if (suggested !== 'mappings/order-to-salesorder-copy.xsl') fail('Save as suggestion: ' + suggested);
await page.getByRole('dialog').getByRole('button', { name: 'OK' }).click();
await page.locator('section.mapping h2', { hasText: 'order-to-salesorder-copy.xsl' }).waitFor({ timeout: 10000 });
if (fs.readFileSync(path.join(project, 'mappings/order-to-salesorder-copy.xsl'), 'utf8') !== xslBefore) fail('Save as wrote different content');
if (fs.readFileSync(path.join(project, 'mappings/order-to-salesorder.xsl'), 'utf8') !== xslBefore) fail('Save as changed the open file');
// The tree reloads after the save and the copy takes the original's row; a click before that lands on the copy.
await page.locator('.tree .file', { hasText: 'order-to-salesorder-copy.xsl' }).waitFor();
await mappingFile.click();
await page.locator('section.mapping h2[title="mappings/order-to-salesorder.xsl"]').waitFor();

// 4) Open a test case: loads input, headers and runs.
await page.locator('.tree .file', { hasText: 'standard-order/case.json' }).click();
// The scratch result stays visible until the case's run ends, and it binds SenderSystem too (ERP_100),
// so wait for the case's value; step 6 validates this run's output.
await page.getByRole('tab', { name: /^Parameters/ }).click();
await page.locator('.params tr', { hasText: 'WEBSHOP' }).waitFor({ timeout: 10000 })
  .catch(() => fail('SenderSystem=WEBSHOP missing from the parameter table'));
const params = await page.locator('.params table').innerText();
if (!params.includes('header')) fail('parameter binding table: ' + params);
if (shots) await page.screenshot({ path: path.join(shots, '3-xslt-case.png') });

// 6) XSD validation of an invalid document.
await page.getByRole('tab', { name: 'XSD Validation' }).click();
await page.getByRole('tab', { name: 'XSLT Mapping' }).click();
await page.locator('.tree .file', { hasText: 'salesorder.xsd' }).click();
await page.getByRole('button', { name: 'Use XSLT output' }).click();
await page.getByRole('button', { name: 'Validate' }).click();
await page.locator('.verdict').waitFor({ timeout: 10000 });
const verdict = await page.locator('.verdict').innerText();
if (!verdict.includes('Valid')) fail('xsd verdict: ' + verdict);
if (shots) await page.screenshot({ path: path.join(shots, '5-xsd.png') });

// 6b) An XSD test case opens in the XSD view and validates.
await page.locator('.tree .file', { hasText: 'invalid-salesorder/case.json' }).click();
await page.locator('.verdict', { hasText: 'Invalid' }).waitFor({ timeout: 10000 });
if (!(await page.locator('.result').innerText()).includes('Item')) fail('xsd case diagnostics lack Item');

// 6b) File tree context menu: copy, rename and delete a file.
const menuOn = async (name) => {
  await page.locator('.tree .file', { hasText: name }).first().click({ button: 'right' });
  return page.getByRole('menu');
};
const answer = async (value) => {
  if (value !== undefined) await page.getByRole('dialog').getByRole('textbox').fill(value);
  await page.getByRole('dialog').getByRole('button', { name: 'OK' }).click();
};
await (await menuOn('salesorder.xsd')).getByRole('menuitem', { name: 'Copy…' }).click();
await answer('schemas/copy.xsd');
await page.locator('.tree .file', { hasText: 'copy.xsd' }).waitFor({ timeout: 10000 });
await (await menuOn('copy.xsd')).getByRole('menuitem', { name: 'Rename…' }).click();
await answer('schemas/renamed.xsd');
await page.locator('.tree .file', { hasText: 'renamed.xsd' }).waitFor({ timeout: 10000 });
if (fs.existsSync(path.join(project, 'schemas/copy.xsd'))) fail('rename left schemas/copy.xsd behind');
if (fs.readFileSync(path.join(project, 'schemas/renamed.xsd'), 'utf8') !== fs.readFileSync(path.join(project, 'schemas/salesorder.xsd'), 'utf8')) {
  fail('renamed copy differs from schemas/salesorder.xsd');
}
await (await menuOn('renamed.xsd')).getByRole('menuitem', { name: 'Delete…' }).click();
await answer();
await page.locator('.tree .file', { hasText: 'renamed.xsd' }).waitFor({ state: 'detached', timeout: 10000 });
if (fs.existsSync(path.join(project, 'schemas/renamed.xsd'))) fail('delete left schemas/renamed.xsd behind');

// 7) XPath.
await page.getByRole('tab', { name: 'XPath' }).click();
await page.getByLabel('XPath expression').fill('count(//Item)');
await page.getByRole('button', { name: 'Evaluate' }).click();
await page.locator('.res .item').first().waitFor({ timeout: 10000 });
if (shots) await page.screenshot({ path: path.join(shots, '6-xpath.png') });

// 8) Files that are not steps open in the File tab and leave the input alone; inputs/ holds saved inputs.
await page.getByRole('tab', { name: 'XSLT Mapping' }).click();
const inputEditor = page.locator('section.input .view-lines');
const inputBefore = await inputEditor.innerText();
await page.locator('.tree .file', { hasText: 'value-mappings.json' }).click();
await page.locator('section[aria-label="File"] h2', { hasText: 'platform/value-mappings.json' }).waitFor({ timeout: 10000 });
await page.getByRole('tab', { name: 'XSLT Mapping' }).click();
// The editor is created anew after the tab switch; wait until it has rendered.
let inputAfter = '';
for (let i = 0; i < 20 && inputAfter !== inputBefore; i++) {
  await page.waitForTimeout(250);
  inputAfter = await inputEditor.innerText();
}
if (inputAfter !== inputBefore) fail(`opening a platform file changed the input body: ${JSON.stringify(inputBefore.slice(0, 80))} -> ${JSON.stringify(inputAfter.slice(0, 80))}`);
await page.locator('section.input').getByRole('button', { name: 'Save…' }).click();
await answer('inputs/order.xml');
await page.locator('.tree .file', { hasText: 'order.xml' }).waitFor({ timeout: 10000 });
if (!fs.existsSync(path.join(project, 'inputs/order.xml'))) fail('input not saved to inputs/order.xml');
await (await menuOn('value-mappings.json')).getByRole('menuitem', { name: 'Use as input' }).click();
// Only XML engines run here: a JSON body has nowhere to go and must not replace the XSLT input.
await page.locator('.banner.error', { hasText: 'only a Groovy script takes a JSON body' }).waitFor({ timeout: 10000 })
  .catch(() => fail('"Use as input" with JSON and no Groovy engine gave no error'));
if ((await inputEditor.innerText()).includes('sourceAgency')) fail('JSON replaced the XSLT input');
await page.locator('.banner.error').getByRole('button', { name: 'Dismiss' }).click();
await (await menuOn('standard-order/input.xml')).getByRole('menuitem', { name: 'Use as input' }).click();
await page.locator('section.input h2', { hasText: '· input.xml' }).waitFor({ timeout: 10000 })
  .catch(() => fail('"Use as input" did not load standard-order/input.xml'));
await page.locator('.tree .file', { hasText: 'order.xml' }).click();
await page.locator('section.input .view-lines', { hasText: inputBefore.split('\n')[0].trim() }).waitFor({ timeout: 10000 })
  .catch(() => fail('clicking inputs/order.xml did not load it as input'));
// The XSD tab loads files from inputs/ into its own document and names the file.
await page.getByRole('tab', { name: 'XSD Validation' }).click();
await page.locator('.tree .file', { hasText: 'order.xml' }).click();
await page.locator('section h2', { hasText: '· order.xml' }).waitFor({ timeout: 10000 })
  .catch(() => fail('XSD tab does not show inputs/order.xml as its document'));
if (!(await page.locator('.tree .file', { hasText: 'order.xml' }).getAttribute('class')).includes('active')) fail('inputs/order.xml not marked in the tree');
// The XSLT output replaces the document, so the header no longer names the file.
await page.getByRole('button', { name: 'Use XSLT output' }).click();
await page.locator('section h2', { hasText: '· order.xml' }).waitFor({ state: 'detached', timeout: 5000 })
  .catch(() => fail('XSD header still names order.xml after Use XSLT output'));
await page.getByRole('tab', { name: 'XSLT Mapping' }).click();
await page.getByRole('tab', { name: 'value-mappings.json' }).click();
await page.locator('section[aria-label="File"]').getByRole('button', { name: 'Close' }).click();
await page.getByRole('tab', { name: 'value-mappings.json' }).waitFor({ state: 'detached', timeout: 5000 });

// 9) The gutter between input and mapping resizes the panes.
const inputPane = page.locator('section.input');
const w0 = (await inputPane.boundingBox()).width;
const g = await page.locator('.xslt > .gutter').first().boundingBox();
await page.mouse.move(g.x + g.width / 2, g.y + g.height / 2);
await page.mouse.down();
await page.mouse.move(g.x + 150, g.y + g.height / 2, { steps: 5 });
await page.mouse.up();
const w1 = (await inputPane.boundingBox()).width;
if (Math.abs(w1 - w0 - 150) > 10) fail(`input pane width ${w0} -> ${w1}, expected +150`);
if (shots) await page.screenshot({ path: path.join(shots, '7-resized.png') });

// 10) "+ Script" suggests a free name and asks before it overwrites an existing file.
fs.writeFileSync(path.join(project, 'scripts/new-script.groovy'), '// keep me\n');
await page.getByTitle('Reload file list').click();
await page.getByRole('button', { name: '+ Script' }).click();
const newName = await page.getByRole('dialog').getByRole('textbox').inputValue();
if (newName !== 'scripts/new-script-2.groovy') fail('+ Script suggestion: ' + newName);
// Another spelling of the same path is the same file.
await page.getByRole('dialog').getByRole('textbox').fill('scripts/./new-script.groovy');
await page.getByRole('dialog').getByRole('button', { name: 'OK' }).click();
await page.getByRole('dialog').filter({ hasText: 'exists. Overwrite it?' }).getByRole('button', { name: 'Cancel' }).click()
  .catch(() => fail('no overwrite question for an existing script'));
if (fs.readFileSync(path.join(project, 'scripts/new-script.groovy'), 'utf8') !== '// keep me\n') fail('+ Script overwrote an existing file');

// 11) Closing the project asks before it drops unsaved changes in the File tab; Cancel keeps it open.
await page.locator('.tree .file', { hasText: 'value-mappings.json' }).click();
await page.locator('section[aria-label="File"] .view-lines').click({ position: { x: 40, y: 10 } });
await page.keyboard.press('Control+End');
await page.keyboard.type(' ');
await page.getByTitle('Close project').click();
await page.getByRole('dialog').filter({ hasText: 'Discard the unsaved changes' }).getByRole('button', { name: 'Cancel' }).click()
  .catch(() => fail('no question before Close project drops File tab changes'));
if (!(await page.locator('.tree .file', { hasText: 'value-mappings.json' }).isVisible())) fail('Cancel closed the project anyway');

if (errors.length) fail('browser errors: ' + errors.join(' | '));
await browser.close();
console.log(process.exitCode ? 'smoke test FAILED' : 'smoke test passed');
