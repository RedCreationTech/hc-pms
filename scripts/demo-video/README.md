# 演示视频制作工具

把 [分镜与字幕](../../docs/pms/15-demo-video-storyboard.md) 变成一支带章节卡, 底部字幕和原创配乐的完整流程演示视频. 全部步骤在本机执行, 不依赖外部服务或第三方素材.

| 文件 | 作用 |
|---|---|
| `storyboard.js` | 分镜与字幕的唯一数据源: 片头, 11 个章节 (大标题/副标题/要点), 48 个镜头 (账号, 画面, 动作, 字幕) |
| `storyboard_md.js` | 从数据源生成 `docs/pms/15-demo-video-storyboard.md` |
| `../../tests/e2e/pms-demo-video.spec.js` | 自动录屏: 每个页面注入可见光标与底边时间码条 (每 100 毫秒一次, 供合成时对齐), 按章节分段录制, 每章项目经理与独立审核人各一个录像上下文 (1600x900, `raw/ch-XX-admin.webm` / `raw/ch-XX-reviewer.webm`), 表单逐字输入, 按分镜逐镜头记录起止时间与字幕时刻到 `timeline.json`; 重复性的后台提交/批准不入镜. 跨章节状态写入 `state.json`, 中断后重跑从未完成的章节继续 |
| `render_cards.js` | 用 Chromium 渲染 1920x1080 的片头卡, 章节卡, 片尾卡与带左侧章节导航栏的录屏背景, 并导出 `storyboard.json` |
| `music.py` | 原创配乐: 逐音符合成 (D 大调, 112 BPM, 铺底 + 琶音 + 贝斯 + 轻鼓组 + 钟琴动机), 章节卡处上扬音效与重音, 按成片时间轴编排 |
| `compose.py` | 剪辑合成: 逐帧读出录屏时间码把镜头与字幕对齐到画面, 生成剪辑表 (长表单填写段落加速并标注 "快进"), 逐段渲染 (章节卡淡入淡出, 录屏裁掉时间码条后叠加到背景并在切点淡变), ASS 底部字幕 + 账号标签 + 进度条, 配乐响度归一到 -20 LUFS, 输出 MP4 与 SRT |

```bash
node scripts/demo-video/storyboard_md.js
PMS_DEMO_VIDEO=1 PMS_DEMO_VIDEO_FRESH=1 PMS_DEMO_DB=/path/to/e2e.db BASE_URL=http://127.0.0.1:3100 \
  npx playwright test tests/e2e/pms-demo-video.spec.js --project=chromium
node scripts/demo-video/render_cards.js
python3 scripts/demo-video/compose.py            # 内部调用 music.py; --keep 保留中间片段
```

录屏中断 (机器重启, 超时) 时: 若设置了 `PMS_DEMO_DB`, 先停后端, 用 `raw/ckpt-XX.db` (XX 为 `state.json` 中 `inProgress.chapter`) 覆盖数据库再启动后端, 然后去掉 `PMS_DEMO_VIDEO_FRESH` 重跑同一命令, 已完成的章节不会重录.

依赖: Node + Playwright (Chromium), Python 3 + numpy + scipy, ffmpeg (libx264, libass), 中文字体 Noto Sans CJK SC. 录屏约 20 分钟 (字幕按可读时长自动停留), 合成约 10 分钟 (2 核); 录屏期间不要在同一台机器上跑重负载任务, 否则动画变慢会影响下拉选择的时机. 产物在 `reports/demo-video/` (不入库).
