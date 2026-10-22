# 原型与界面设计

所有原型页面、配套脚本和样式、设计说明、截图、验证脚本及本地预览资源集中放在本目录。

## 预览入口

- [V6 完整交互原型](V6.html)：默认进入策划书工作台。
- [V6 原始入口](PAMS-UI-bright-v6.html)：支持全站模块切换。
- [V5 原型](PAMS-UI-bright-v5.html)。
- [登录设计](PAMS-login-redesign-v1.html)。
- [灵思 V2 原型](lingsi-ui-v2-prototype.html)。
- [设计语言预览](design-preview.html)。
- [Agent 团队原型](agent-team-prototype.html)。

直接打开 HTML 即可预览。需要本地 HTTP 服务时，在仓库根目录执行：

```bash
node docs/design/ui-v6-activity/serve.cjs
```

随后访问 [V6 本地预览](http://127.0.0.1:4176/V6.html)。该服务以本设计目录为根目录。

## 文件组织

- `PAMS-UI-v6-*.css/js`：V6 各模块原型代码。
- `PAMS-UI-*.md`：界面规范、模块设计与落地记录。
- `ui-*`：原型及界面验收截图、验证脚本和样例文件。
- `assets/`：原型使用的品牌图标和灵思头像。
- `vendor/word-preview/`：Word 预览依赖及许可证。
- `品牌设计/`：品牌方案与使用规范文档。

策划书的审核人勾选、版本可见范围和文档内编辑方案见 [策划书工作台设计](PAMS-UI-v6-策划书工作台设计.md)。

正式项目的接口、权限规则、验证与预览见 [策划书工作台落地记录](PAMS-UI-v6-策划书工作台落地记录.md)。

内嵌文档编辑器的方案、授权与接入设计见 [策划书内嵌 Word 编辑器选型调研](PAMS-策划书内嵌Word编辑器选型调研.md)。

当前正式项目已切换到 [SuperDoc 接入与验收](PAMS-SuperDoc-接入与验收.md)，含旧方案清理范围与实际页面截图。
