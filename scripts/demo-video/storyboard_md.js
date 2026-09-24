// 从 storyboard.js 生成 docs/pms/15-demo-video-storyboard.md (分镜与字幕文档), 保证文档与录屏/成片使用同一份文案.
// 用法: node scripts/demo-video/storyboard_md.js
const fs = require('node:fs');
const path = require('node:path');
// DEMO_STORYBOARD 选择分镜数据源; 数据源可用 doc 字段给出文档路径, 标题与说明 (默认完整业务流程视频).
const S = require(process.env.DEMO_STORYBOARD ? path.resolve(process.env.DEMO_STORYBOARD) : './storyboard.js');

const out = path.resolve(__dirname, '../..', (S.doc && S.doc.path) || 'docs/pms/15-demo-video-storyboard.md');
const esc = s => String(s).replace(/\|/g, '\\|');
const who = w => ((S.accounts && S.accounts[w]) || (w === 'reviewer' ? '独立审核人' : '项目经理')).replace(/账号$/, '');
const lines = [];

if (S.doc) {
  lines.push(`# ${S.doc.title}`);
  lines.push('');
  lines.push(S.doc.intro);
} else {
lines.push('# 红创PMS 完整流程演示视频: 分镜与字幕');
lines.push('');
lines.push('本文件由 `scripts/demo-video/storyboard_md.js` 从 `scripts/demo-video/storyboard.js` 生成, 请修改数据源后重新生成, 不要直接编辑. 视频用同一个设备订单项目把 PMS 从平台模板走到正式关闭, 与 [演示剧本](14-demo-script.md) 的 37 步一致; 录屏由 `tests/e2e/pms-demo-video.spec.js` 在真实系统上自动完成, 字幕按本分镜逐条出现.');
}
lines.push('');
lines.push('## 画面与节奏');
lines.push('');
lines.push('- 成片 1920x1080, 25 fps, H.264 + AAC. 系统画面按 1600x900 原尺寸录制 (不缩放, 文字清晰), 放在画面右上; 左侧 220 像素为章节导航栏 (当前章节高亮); 底部 140 像素为字幕带, 字幕不遮挡系统界面; 最底部细进度条表示整体进度.');
lines.push('- 片头卡 4 秒, 每个功能节点前有 3.2 秒章节卡 (大标题 + 副标题 + 要点), 片尾卡 7 秒, 卡片淡入淡出.');
lines.push('- 录屏中可见鼠标光标 (平滑移动, 点击有涟漪), 表单文字逐字输入; 需要审核的动作切换到独立审核人账号的画面.');
lines.push('- 每条字幕停留 max(2.8 秒, 字数 / 6.5 字每秒), 由录屏用例在每条字幕后自动等待保证可读; 为控制时长, 重复性的提交/批准经真实 HTTP 完成且不入镜, 画面只保留操作与结果.');
lines.push('- 长表单填写: 同一条字幕下超过 max(8 秒, 阅读时长 + 2 秒) 的部分按 3 倍速播放 (整段约不超过 2.3 倍), 加速期间左侧导航栏下方显示 "快进 N.Nx" 标记, 不隐瞒加速.');
lines.push('- 字幕与画面对齐: 录屏时每帧底边画有 8 像素时间码 (合成时裁掉), 合成脚本逐帧读出时间码, 把每个镜头起止与每条字幕映射到真正显示该时刻的那一帧, 消除录屏链路不均匀且随时长累积的时间偏差.');
lines.push('- 配乐: 原创合成 (程序逐音符生成, 无采样与第三方素材), 轻快科技感, 112 BPM, D 大调, 柔和铺底 + 琶音 + 贝斯 + 轻鼓组; 章节卡处有上扬音效与重音; 整体响度约 -20 LUFS, 不压字幕阅读.');
lines.push('');
lines.push('## 片头');
lines.push('');
lines.push(`- 大标题: ${S.title.heading}`);
lines.push(`- 副标题: ${S.title.subheading}`);
lines.push(`- 说明行: ${S.title.meta}`);
lines.push('');
lines.push('## 章节卡');
lines.push('');
lines.push('| 编号 | 大标题 | 副标题 | 要点 | 导航栏简称 |');
lines.push('|---|---|---|---|---|');
for (const c of S.chapters) lines.push(`| ${c.no} | ${esc(c.title)} | ${esc(c.subtitle)} | ${esc(c.points.join(' / '))} | ${esc(c.short)} |`);
lines.push('');
lines.push('## 分镜');
lines.push('');
let n = 0;
for (const c of S.chapters) {
  lines.push(`### ${c.no} ${c.title}`);
  lines.push('');
  lines.push('| 镜头 | 账号 | 画面 | 动作 | 字幕 (按顺序) | 最短时长 |');
  lines.push('|---|---|---|---|---|---|');
  for (const s of S.shots.filter(x => x.chapter === c.no)) {
    const secs = s.captions.reduce((a, t) => a + S.minDuration(t), 0);
    n += s.captions.length;
    lines.push(`| ${s.id} | ${who(s.who)} | ${esc(s.screen)} | ${esc(s.action)} | ${s.captions.map((t, i) => `${i + 1}. ${esc(t)}`).join('<br>')} | ${secs.toFixed(1)} 秒 |`);
  }
  lines.push('');
}
lines.push('## 片尾');
lines.push('');
lines.push(`- 大标题: ${S.ending.heading}`);
for (const p of S.ending.points) lines.push(`- ${p}`);
lines.push('');
lines.push('## 制作流程');
lines.push('');
if (S.doc && S.doc.make) {
  lines.push(`共 ${S.chapters.length} 个章节, ${S.shots.length} 个镜头, ${n} 条字幕.`);
  lines.push('');
  for (const l of S.doc.make) lines.push(l);
} else {
lines.push(`共 ${S.chapters.length} 个章节, ${S.shots.length} 个镜头, ${n} 条字幕. 在隔离后端 (空库或已有演示数据均可, 用例每次新建演示项目与审核账号) 上执行:`);
lines.push('');
lines.push('```bash');
lines.push('node scripts/demo-video/storyboard_md.js                     # 重新生成本文件');
lines.push('PMS_DEMO_VIDEO=1 PMS_DEMO_VIDEO_FRESH=1 BASE_URL=http://127.0.0.1:3100 \\');
lines.push('  npx playwright test tests/e2e/pms-demo-video.spec.js --project=chromium   # 按章节分段录屏, 约 20 分钟');
lines.push('node scripts/demo-video/render_cards.js                      # 片头 / 章节卡 / 片尾 / 带导航栏的背景');
lines.push('python3 scripts/demo-video/compose.py                        # 剪辑表, 字幕, 原创配乐 (music.py), 合成 MP4 与 SRT');
lines.push('```');
lines.push('');
lines.push('录屏中断后去掉 `PMS_DEMO_VIDEO_FRESH` 重跑, 用例读取 `state.json` 从未完成的章节继续; 设置 `PMS_DEMO_DB` 时每章开始前备份一次 SQLite 库 (`raw/ckpt-XX.db`), 可先恢复到该章开始时的数据再续录.');
lines.push('');
lines.push('产物在 `reports/demo-video/` (不入库): `hc-pms-demo.mp4` 成片, `hc-pms-demo.srt` 字幕, `timeline.json` 录屏时间轴, `edl.json` 成片剪辑表, `music.wav` 配乐. 录屏依赖 Playwright 的页面录像 (`recordVideo`), 需要本机有 ffmpeg (libx264 / libass) 与中文字体 (Noto Sans CJK SC).');
lines.push('');
}
fs.writeFileSync(out, lines.join('\n'));
console.log(`written ${out}`);
