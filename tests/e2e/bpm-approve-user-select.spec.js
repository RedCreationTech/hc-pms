const { test, expect } = require('@playwright/test');
const path = require('node:path');

// 审批人自选(APPROVE_USER_SELECT)全链路: 用 API 建两节点模型(二级审批配置 APPROVE_USER_SELECT),
// 发起后 admin 在一级审批通过时弹出"下一节点审批人"选择器, 选中的人收到二级审批待办.
const output = path.resolve(__dirname, '../../reports/bpm-approve-user-select');
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const PASSWORD = 'Pass-1234';

async function login(page, user = 'admin', password = 'admin123') {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill(user);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
  await page.getByText('红创PMS').nth(1).waitFor();
}

async function api(page, method, url, data, expected = 200) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, data });
  const body = await response.json().catch(() => ({}));
  expect(body.code ?? response.status(), `${method} ${url}: ${body.msg}`).toBe(expected);
  return body.data;
}

const shot = (page, file) => page.screenshot({ path: path.join(output, file), animations: 'disabled' });
const dialog = (page, title) => page.getByRole('dialog').filter({ hasText: title }).last();
const rows = page => page.locator('.ant-table-tbody tr.ant-table-row');

const nodeConfigProp = cfg => `<flowable:properties><flowable:property name="nodeConfig" value="${cfg.replace(/"/g, '&quot;')}"/></flowable:properties>`;
const taskEl = (id, name, cfg) =>
  `<userTask id="${id}" name="${name}"><extensionElements>` +
  `<flowable:taskListener event="create" delegateExpression="${'${bpmTaskListener}'}"/>${nodeConfigProp(cfg)}` +
  `</extensionElements></userTask>`;
const simpleBpmn = els => {
  const ids = els.map(e => /id="([^"]+)"/.exec(e)[1]);
  const chain = ['start', ...ids, 'end'];
  const flows = chain.slice(1).map((b, i) => `<sequenceFlow id="f_${chain[i]}_${b}" sourceRef="${chain[i]}" targetRef="${b}"/>`).join('');
  return `<?xml version="1.0"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" ` +
    `xmlns:flowable="http://flowable.org/bpmn" id="d" targetNamespace="http://bpmn.io/schema/bpmn">` +
    `<process id="PHKEY" name="审批人自选验证" isExecutable="true"><startEvent id="start"/>${els.join('')}` +
    `<endEvent id="end"/>${flows}</process></definitions>`;
};

async function createUser(page, username) {
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '自选审批人', password: PASSWORD, roles: [2] });
  const list = await api(page, 'GET', `/api/system/user?user_name=${username}&page=1&size=5`);
  return list.rows[0];
}

async function deployModel(page, xml, key) {
  const bpmn = xml.replace(/PHKEY/g, key);
  await api(page, 'POST', '/api/business/bpm/model', { model_key: key, model_name: '审批人自选验证', category_id: 0, form_type: '1', form_json: '{"fields":[]}' });
  const list = await api(page, 'GET', '/api/business/bpm/model?page=1&size=100');
  const mid = list.rows.find(r => r.model_key === key).model_id;
  await api(page, 'PUT', `/api/business/bpm/model/${mid}`, { model_id: mid, model_name: '审批人自选验证', category_id: 0, form_type: '1', bpmn_xml: bpmn, status: '1', remark: '' });
  await api(page, 'POST', `/api/business/bpm/model/deploy/${mid}`);
  return mid;
}

test.describe.configure({ mode: 'serial' });

test('审批人自选: 通过时选择下一节点审批人, 被选中者收到待办', async ({ page }) => {
  await login(page);
  const sfx = serial();
  const picked = `self${sfx}`;
  await createUser(page, picked);

  const t1 = { 'approve-type': 'USER', 'candidate-strategy': 'USER', 'candidate-param': { 'user-ids': [1] }, 'approve-method': 'SEQUENTIAL', 'assign-empty-handler': { type: 'TO_ADMIN' } };
  const t2 = { 'approve-type': 'USER', 'candidate-strategy': 'APPROVE_USER_SELECT', 'approve-method': 'SEQUENTIAL', 'assign-empty-handler': { type: 'TO_ADMIN' } };
  const key = `selfsel${sfx}`;
  const mid = await deployModel(page, simpleBpmn([taskEl('t1', '一级审批', JSON.stringify(t1)), taskEl('t2', '二级审批', JSON.stringify(t2))]), key);
  const inst = await api(page, 'POST', '/api/business/bpm/instance', { model_id: mid, form_data: { reason: '审批人自选验证' } });

  // admin 进入待办, 点一级审批"通过" -> 弹窗出现"下一节点审批人"选择器
  await page.goto('/office/bpm/todo');
  await rows(page).first().waitFor({ timeout: 20000 });
  const row = rows(page).filter({ hasText: '一级审批' }).filter({ hasText: '审批人自选验证' }).first();
  await expect(row).toBeVisible({ timeout: 20000 });
  await row.getByRole('button', { name: /通\s*过/ }).click();
  const modal = dialog(page, '一级审批');
  await expect(modal).toBeVisible({ timeout: 10000 });
  await expect(modal.getByText('下一节点审批人')).toBeVisible();
  await shot(page, '01-approve-modal-select-user.png');

  // 选择被选中用户
  await modal.locator('.ant-select').first().click();
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option:visible')
    .filter({ hasText: picked }).last().click();
  await shot(page, '02-user-picked.png');

  await modal.getByRole('button', { name: /确\s*定|OK/i }).click();
  await expect(modal).toBeHidden({ timeout: 15000 });

  // 被选中用户登录, 待办里出现二级审批
  const ctx2 = await page.context().browser().newContext();
  const p2 = await ctx2.newPage();
  await login(p2, picked, PASSWORD);
  await p2.goto('/office/bpm/todo');
  await rows(p2).first().waitFor({ timeout: 20000 });
  const row2 = rows(p2).filter({ hasText: '二级审批' }).filter({ hasText: '审批人自选验证' }).first();
  await expect(row2, '被选中用户应收到二级审批待办').toBeVisible({ timeout: 20000 });
  await shot(p2, '03-picked-user-todo.png');
  await ctx2.close();

  // admin 不应收到该实例的二级审批待办
  await page.goto('/office/bpm/todo');
  await rows(page).first().waitFor({ timeout: 20000 });
  const adminRow = rows(page).filter({ hasText: '二级审批' }).filter({ hasText: '审批人自选验证' });
  await expect(adminRow).toHaveCount(0, { timeout: 8000 });
});