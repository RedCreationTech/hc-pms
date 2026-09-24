const { test, expect } = require('@playwright/test');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');
const crypto = require('node:crypto');
const zlib = require('node:zlib');

// 增量5 (C04/C05/C06 二进制附件): 界面上传真实 PDF/PNG 文件 -> 表格显示文件名/大小/类别 -> 预览弹窗内嵌 PDF 与图片并显示服务端复核一致
// -> 可执行文件被拒 -> 真实 HTTP 下载字节 SHA256 与本地文件一致 -> 无密级权限审核人读机密文件 403 (界面错误面板) -> 批量 ZIP 含二进制原件
// -> 提交发布并由独立审核人签发, 记录固化 release_sha256. 全部走真实 multipart 与真实字节, 不用 mock.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/d-documents');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;

async function login(page, user = 'admin', password = 'admin123') {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill(user);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
}

async function token(page) { return page.evaluate(() => localStorage.getItem('ruoyi_token')); }

async function api(page, method, url, data, expected = 200) {
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${await token(page)}` }, data });
  const body = await response.json();
  expect(body.code, `${method} ${url}: ${body.msg}`).toBe(expected);
  return body.data;
}

async function mutate(page, id, suffix, data = {}, expected = 200) {
  const project = await api(page, 'GET', base(id));
  return api(page, 'POST', base(id) + suffix, { ...data, version: project.version }, expected);
}

async function tab(page, name) {
  await drawer(page).getByRole('tab', { name, exact: true }).click();
  await page.waitForLoadState('networkidle');
}

async function open(page, id, section, subsection) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  if (section) await tab(page, section);
  if (subsection) await tab(page, subsection);
}

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await input.press('Escape');
}

async function fill(form, values) {
  for (const [key, value] of Object.entries(values)) await form.locator(`#${key}`).fill(String(value));
}

async function shot(page, file, { idle = true } = {}) {
  // 内嵌 PDF 查看器会持续占用网络连接, 预览弹窗截图不等待 networkidle.
  if (idle) await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 生成一个最小但真实可渲染的单页 PDF (含文字), 以及一个 1x1 PNG.
function makePdf(text) {
  const objects = [];
  objects.push('<< /Type /Catalog /Pages 2 0 R >>');
  objects.push('<< /Type /Pages /Kids [3 0 R] /Count 1 >>');
  objects.push('<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>');
  const stream = `BT /F1 18 Tf 24 120 Td (${text}) Tj ET`;
  objects.push(`<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`);
  objects.push('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');
  let out = '%PDF-1.4\n';
  const offsets = [];
  objects.forEach((obj, i) => { offsets.push(out.length); out += `${i + 1} 0 obj\n${obj}\nendobj\n`; });
  const xref = out.length;
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const o of offsets) out += `${String(o).padStart(10, '0')} 00000 n \n`;
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(out, 'latin1');
}

function makePng() {
  const crc = buf => { let c, crcTable = []; for (let n = 0; n < 256; n++) { c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crcTable[n] = c >>> 0; }
    let crcv = 0xffffffff; for (const b of buf) crcv = crcTable[(crcv ^ b) & 0xff] ^ (crcv >>> 8); return (crcv ^ 0xffffffff) >>> 0; };
  const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([len, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(8, 0); ihdr.writeUInt32BE(8, 4); ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  const raw = Buffer.alloc(8 * (1 + 8 * 3)); for (let y = 0; y < 8; y++) { raw[y * 25] = 0; for (let x = 0; x < 8; x++) { raw[y * 25 + 1 + x * 3] = 30; raw[y * 25 + 2 + x * 3] = 90 + x * 15; raw[y * 25 + 3 + x * 3] = 160; } }
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}

// 读取 ZIP 的中央目录, 返回 {name: bytes} (仅支持 stored/deflate 两种方法, 足够校验本次打包).
function unzip(buffer) {
  const entries = {};
  let eocd = buffer.length - 22;
  while (eocd >= 0 && buffer.readUInt32LE(eocd) !== 0x06054b50) eocd--;
  const count = buffer.readUInt16LE(eocd + 10);
  let p = buffer.readUInt32LE(eocd + 16);
  for (let i = 0; i < count; i++) {
    const method = buffer.readUInt16LE(p + 10), csize = buffer.readUInt32LE(p + 20), nlen = buffer.readUInt16LE(p + 28), elen = buffer.readUInt16LE(p + 30), clen = buffer.readUInt16LE(p + 32);
    const local = buffer.readUInt32LE(p + 42);
    const name = buffer.slice(p + 46, p + 46 + nlen).toString('utf8');
    const lnlen = buffer.readUInt16LE(local + 26), lelen = buffer.readUInt16LE(local + 28);
    const start = local + 30 + lnlen + lelen;
    const data = buffer.slice(start, start + csize);
    entries[name] = method === 8 ? zlib.inflateRawSync(data) : data;
    p += 46 + nlen + elen + clen;
  }
  return entries;
}

const sha256 = buf => crypto.createHash('sha256').update(buf).digest('hex');

async function fixture(page, browser) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  // 审核人: 有质量审批权限但没有 pms:document:confidential, 用于验证机密文件 403.
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `文档审核${suffix}`, role_key: `pms_doc_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(item => item.role_key === `pms_doc_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`, password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立文档审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `DOC-${suffix}`, name: `二进制证据 ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  return { id, userId, adminId: options.currentUserId, reviewer, context, suffix };
}

async function upload(page, title, values, filePath, classification, category) {
  await drawer(page).getByRole('button', { name: '上传证据文件' }).click();
  const form = modal(page, '上传证据文件');
  await fill(form, values);
  if (classification) await choose(page, form, 'classification', classification);
  if (category) await choose(page, form, 'category', category);
  await form.locator('#document_file').setInputFiles(filePath);
  await expect(form.getByText(path.basename(filePath))).toBeVisible();
  const response = page.waitForResponse(r => r.url().includes('/documents/upload') && r.request().method() === 'POST');
  await form.getByRole('button', { name: /^上\s*传$/ }).click();
  const body = await (await response).json();
  return { body, form };
}

test.describe('增量5 证据文档二进制附件: 上传/预览/下载/密级/批量ZIP/签发', () => {
  test.setTimeout(240000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('界面上传 PDF/PNG 真实文件, 预览与服务端复核, 可执行文件被拒, 下载摘要一致, 机密 403, ZIP 含原件, 签发固化摘要', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f = await fixture(page, browser);
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'pms-docs-'));
    const pdfPath = path.join(tmp, `design-review-${f.suffix}.pdf`);
    const pngPath = path.join(tmp, `photo-${f.suffix}.png`);
    const exePath = path.join(tmp, `payload-${f.suffix}.exe`);
    const pdf = makePdf(`Design review ${f.suffix}`);
    const png = makePng();
    fs.writeFileSync(pdfPath, pdf); fs.writeFileSync(pngPath, png); fs.writeFileSync(exePath, Buffer.from('MZ fake'));

    // 1. 上传机密 PDF (类别 设计, 阶段 设计) -> 表格显示文件名/大小/类别/密级.
    await open(page, f.id, '需求与治理', '证据版本');
    const { body: created } = await upload(page, '上传证据文件', { code: `DR-${f.suffix}`, title: '设计评审报告', stage: '设计' }, pdfPath, '机密', '设计');
    expect(created.code, created.msg).toBe(200);
    const pdfDoc = created.data.result;
    expect(pdfDoc.content_kind).toBe('file');
    expect(pdfDoc.sha256).toBe(sha256(pdf));
    expect(pdfDoc.byte_size).toBe(pdf.length);
    await expect(modal(page, '上传证据文件')).toBeHidden();
    await expect(row(page, `DR-${f.suffix}`)).toContainText(path.basename(pdfPath));
    await expect(row(page, `DR-${f.suffix}`)).toContainText('机密');
    await expect(row(page, `DR-${f.suffix}`)).toContainText('设计');
    await shot(page, 'd-1-file-uploaded.png');

    // 2. 预览: PDF 内嵌框 + 服务端复核一致.
    await row(page, `DR-${f.suffix}`).getByRole('button', { name: '预览/下载' }).click();
    const preview = page.getByRole('dialog').filter({ hasText: '设计评审报告 / V1' });
    await expect(preview.locator('iframe')).toBeVisible();
    await expect(preview.getByText(/服务端复核: 一致/)).toBeVisible();
    await shot(page, 'd-2-pdf-preview.png', { idle: false });
    await preview.getByRole('button', { name: 'Close' }).click();

    // 3. 上传公开 PNG -> 图片预览.
    const { body: pngCreated } = await upload(page, '上传证据文件', { code: `PH-${f.suffix}`, title: '现场照片', structure_node: '主机' }, pngPath, '公开', '现场');
    expect(pngCreated.code, pngCreated.msg).toBe(200);
    const pngDoc = pngCreated.data.result;
    await expect(modal(page, '上传证据文件')).toBeHidden();
    await row(page, `PH-${f.suffix}`).getByRole('button', { name: '预览/下载' }).click();
    const imgPreview = page.getByRole('dialog').filter({ hasText: '现场照片 / V1' });
    await expect(imgPreview.locator('img[alt]')).toBeVisible();
    await expect(imgPreview.getByText(/服务端复核: 一致/)).toBeVisible();
    await shot(page, 'd-3-image-preview.png', { idle: false });
    await imgPreview.getByRole('button', { name: 'Close' }).click();

    // 4. 可执行文件被服务端拒绝, 弹窗错误面板显示原因.
    const { body: rejected, form: rejectedForm } = await upload(page, '上传证据文件', { code: `BAD-${f.suffix}`, title: '可执行文件' }, exePath);
    expect(rejected.code).toBe(400);
    await expect(rejectedForm.getByText(/不允许的文件类型/)).toBeVisible();
    await shot(page, 'd-4-exe-rejected.png');
    await rejectedForm.getByRole('button', { name: /^返\s*回$/ }).click();

    // 5. 真实 HTTP 下载: 字节与本地文件一致, 响应头携带 SHA256; 预览接口 inline.
    const adminToken = await token(page);
    const download = await page.request.get(base(f.id) + `/governance/documents/${pdfDoc.id}/download`, { headers: { Authorization: `Bearer ${adminToken}` } });
    expect(download.status()).toBe(200);
    const downloaded = await download.body();
    expect(sha256(downloaded)).toBe(sha256(pdf));
    expect(download.headers()['x-content-sha256']).toBe(sha256(pdf));
    expect(download.headers()['content-disposition']).toContain('attachment');
    const inline = await page.request.get(base(f.id) + `/governance/documents/${pdfDoc.id}/preview`, { headers: { Authorization: `Bearer ${adminToken}` } });
    expect(inline.headers()['content-disposition']).toContain('inline');
    expect(inline.headers()['content-type']).toContain('application/pdf');

    // 6. 无密级权限的审核人: 机密 PDF 读取 403 (真实 HTTP + 界面错误面板), 公开 PNG 可读.
    const reviewerToken = await token(f.reviewer);
    const forbidden = await f.reviewer.request.get(base(f.id) + `/governance/documents/${pdfDoc.id}/download`, { headers: { Authorization: `Bearer ${reviewerToken}` } });
    expect(forbidden.status()).toBe(403);
    const allowed = await f.reviewer.request.get(base(f.id) + `/governance/documents/${pngDoc.id}/download`, { headers: { Authorization: `Bearer ${reviewerToken}` } });
    expect(allowed.status()).toBe(200);
    expect(sha256(await allowed.body())).toBe(sha256(png));
    await open(f.reviewer, f.id, '需求与治理', '证据版本');
    await row(f.reviewer, `DR-${f.suffix}`).getByRole('button', { name: '预览/下载' }).click();
    const denied = f.reviewer.getByRole('dialog').filter({ hasText: '设计评审报告 / V1' });
    await expect(denied.getByText(/无机密文档访问权限/)).toBeVisible();
    await f.reviewer.screenshot({ path: path.join(output, 'd-5-confidential-denied.png'), animations: 'disabled' });
    await denied.getByRole('button', { name: 'Close' }).click();

    // 7. 批量 ZIP 含二进制原件与 MANIFEST (content_kind=file).
    const zipResponse = await page.request.post(base(f.id) + '/governance/documents/batch-download', { headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' }, data: { record_ids: [pdfDoc.id, pngDoc.id] } });
    expect(zipResponse.status()).toBe(200);
    const entries = unzip(await zipResponse.body());
    const pdfEntry = Object.keys(entries).find(n => n.endsWith(path.basename(pdfPath)));
    expect(pdfEntry).toBeTruthy();
    expect(sha256(entries[pdfEntry])).toBe(sha256(pdf));
    expect(entries['MANIFEST.tsv'].toString('utf8')).toContain('\tfile\t');

    // 8. 上传同编号新版本 (文件修订) -> V2 行出现, V1 摘要不变.
    await row(page, `DR-${f.suffix}`).getByRole('button', { name: '新版本' }).click();
    const revForm = modal(page, '新增证据文档版本 (上传文件)');
    const pdf2Path = path.join(tmp, `design-review-v2-${f.suffix}.pdf`);
    fs.writeFileSync(pdf2Path, makePdf(`Design review v2 ${f.suffix}`));
    await revForm.locator('#document_file').setInputFiles(pdf2Path);
    const revResponse = page.waitForResponse(r => r.url().includes('/upload-revision') && r.request().method() === 'POST');
    await revForm.getByRole('button', { name: /^上\s*传$/ }).click();
    const revBody = await (await revResponse).json();
    expect(revBody.code, revBody.msg).toBe(200);
    expect(revBody.data.result.revision).toBe(2);
    await expect(modal(page, '新增证据文档版本 (上传文件)')).toBeHidden();
    const docs = (await api(page, 'GET', base(f.id) + '/governance')).documents.filter(d => d.code === `DR-${f.suffix}`);
    expect(docs.map(d => d.revision).sort()).toEqual([1, 2]);
    expect(docs.find(d => d.revision === 1).sha256).toBe(sha256(pdf));

    // 9. 提交发布 -> 独立审核人签发 -> 记录固化 release_sha256 与签发时间.
    const v2 = docs.find(d => d.revision === 2);
    await mutate(page, f.id, `/governance/documents/${v2.id}/submit`, { reviewer_id: f.userId });
    const approved = (await mutate(f.reviewer, f.id, `/governance/documents/${v2.id}/decision`, { decision: 'approved', reason: '正式签发' })).result;
    expect(approved.status).toBe('approved');
    expect(approved.release_sha256).toBe(v2.sha256);
    expect(approved.released_at).toBeTruthy();
    await open(page, f.id, '需求与治理', '证据版本');
    await expect(drawer(page).locator('tbody tr:visible').filter({ hasText: `DR-${f.suffix}` }).filter({ hasText: '已发布' })).toHaveCount(1);
    await shot(page, 'd-6-released.png');

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
