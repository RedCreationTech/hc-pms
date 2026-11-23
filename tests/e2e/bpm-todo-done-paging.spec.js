const { test, expect } = require('playwright/test');
const path = require('node:path');

// 我的待办 / 我的已办 服务端分页: 列表页脚显示的总条数来自接口 total (不是当页条数),
// 页码切换会带 page/size 重新请求, 表格行数等于每页条数.
const output = path.resolve(__dirname, '../../reports/bpm-todo-done-paging');
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
const rows = page => page.locator('.ant-table-tbody tr.ant-table-row');

// 分页组件底部的 "共 N 条" 文案
async function totalText(page) {
  const item = page.locator('.ant-pagination-total-text').first();
  await expect(item).toBeVisible();
  return (await item.innerText()).trim();
}

async function gotoPage(page, url) {
  await page.goto(url);
  await expect(rows(page).first()).toBeVisible();
  await page.waitForFunction(() => document.querySelectorAll('.ant-table-tbody tr.ant-table-row').length > 0);
}

test.describe.configure({ mode: 'serial' });

test.use({ channel: undefined });

test('我的待办按服务端 total 分页', async ({ page }) => {
  await login(page);

  // 造 3 条待办: 两节点模型, 每次发起都会在"分页审批1"留下一条 admin 待办.
  const key = `pg${Date.now().toString(36)}`;
  const bpmn = '<?xml version="1.0"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" '
    + 'xmlns:flowable="http://flowable.org/bpmn" id="d" targetNamespace="http://bpmn.io/schema/bpmn">'
    + `<process id="${key}" name="分页演示" isExecutable="true">`
    + '<startEvent id="start"/>'
    + '<userTask id="a1" name="分页审批1" flowable:candidateUsers="admin"/>'
    + '<userTask id="a2" name="分页审批2" flowable:candidateUsers="admin"/>'
    + '<endEvent id="end"/>'
    + '<sequenceFlow id="f1" sourceRef="start" targetRef="a1"/>'
    + '<sequenceFlow id="f2" sourceRef="a1" targetRef="a2"/>'
    + '<sequenceFlow id="f3" sourceRef="a2" targetRef="end"/>'
    + '</process></definitions>';
  await api(page, 'post', '/api/business/bpm/model', {
    model_key: key, model_name: '分页演示', category_id: 0, form_type: '0', bpmn_xml: '',
  });
  const list = await api(page, 'get', '/api/business/bpm/model?page=1&size=200');
  const model = list.rows.find(row => row.model_key === key);
  expect(model, '新建模型必须出现在模型列表').toBeTruthy();
  await api(page, 'put', `/api/business/bpm/model/${model.model_id}`, {
    model_id: model.model_id, model_name: '分页演示', category_id: 0, form_type: '0',
    bpmn_xml: bpmn, status: '1', remark: '',
  });
  await api(page, 'post', `/api/business/bpm/model/deploy/${model.model_id}`, {});
  for (let i = 0; i < 3; i += 1) {
    await api(page, 'post', '/api/business/bpm/instance', { model_id: model.model_id, form_data: {} });
  }

  const apiTotal = (await api(page, 'get', '/api/business/bpm/todo?page=1&size=10')).total;
  expect(apiTotal, '待办总数应 >= 3').toBeGreaterThanOrEqual(3);

  await gotoPage(page, '/office/bpm/todo');
  expect(await totalText(page)).toBe(`共 ${apiTotal} 条`);
  const firstPage = await rows(page).count();
  expect(firstPage).toBe(Math.min(10, apiTotal));
  await shot(page, '01-todo-page1.png');

  if (apiTotal > 10) {
    await page.locator('.ant-pagination-item-2').first().click();
    await page.waitForTimeout(800);
    const secondPage = await rows(page).count();
    expect(secondPage).toBe(Math.min(10, apiTotal - 10));
    await shot(page, '02-todo-page2.png');
  }

  // 切换到每页 20 条
  await page.locator('.ant-pagination-options .ant-select').first().click();
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')
    .filter({ hasText: /^20\s*条\/页$/ }).last().click();
  await page.waitForTimeout(800);
  expect(await rows(page).count()).toBe(Math.min(20, apiTotal));
  expect(await totalText(page)).toBe(`共 ${apiTotal} 条`);
  await shot(page, '03-todo-size20.png');
});

test('我的已办按服务端 total 分页', async ({ page }) => {
  await login(page);
  const apiTotal = (await api(page, 'get', '/api/business/bpm/done?page=1&size=10')).total;
  expect(apiTotal, '已办总数应 >= 1').toBeGreaterThanOrEqual(1);

  await gotoPage(page, '/office/bpm/done');
  expect(await totalText(page)).toBe(`共 ${apiTotal} 条`);
  expect(await rows(page).count()).toBe(Math.min(10, apiTotal));
  await shot(page, '04-done-page1.png');

  if (apiTotal > 10) {
    await page.locator('.ant-pagination-item-2').first().click();
    await page.waitForTimeout(800);
    expect(await rows(page).count()).toBe(Math.min(10, apiTotal - 10));
    await shot(page, '05-done-page2.png');
  }
});