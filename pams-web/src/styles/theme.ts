/**
 * 设计 token 到 antd 主题 / 语义标签色调的映射。
 * 值全部来自 styles/tokens.ts（唯一来源），此文件只做「映射」，不定义颜色。
 */
import { theme as antdTheme, type ThemeConfig } from 'antd'
import { resolveTokens, FONT_FAMILY, type ThemeMode, type BaseTokens } from './tokens'

export type { ThemeMode }

/**
 * 生成 antd ConfigProvider theme。CSS 变量由 installTokenCss() 注入，两边同源。
 * @param primary 组织自定义主色（stores/org.ts 的 primaryColor）；不传则用院校主蓝。
 */
export function buildAntdTheme(mode: ThemeMode, primary?: string): ThemeConfig {
  const t: BaseTokens = resolveTokens(mode, primary)
  const isDark = mode === 'dark'

  return {
    algorithm: isDark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    cssVar: true,
    hashed: false,
    token: {
      colorPrimary: t.brand,
      colorInfo: t.info,
      colorSuccess: t.success,
      colorWarning: t.warning,
      colorError: t.danger,
      colorLink: t.brandText,

      colorBgLayout: t.page,
      colorBgContainer: t.canvas,
      // 浮层与内容面同色，靠阴影区分（方案 §4.4：不再用 surface-strong 变灰）
      colorBgElevated: t.canvas,
      colorBorder: t.border,
      colorBorderSecondary: t.border,

      colorText: t.textPrimary,
      colorTextSecondary: t.textSecondary,
      colorTextTertiary: t.textTertiary,
      colorTextQuaternary: t.textDisabled,

      borderRadius: 6,
      borderRadiusLG: 8,
      borderRadiusSM: 4,
      borderRadiusXS: 4,

      controlHeight: 32,
      controlHeightSM: 24,
      controlHeightLG: 36,

      fontSize: 14,
      fontFamily: FONT_FAMILY,

      boxShadow: t.shadow1,
      boxShadowSecondary: t.shadow2,
      wireframe: false,
    },
    components: {
      Layout: {
        headerBg: 'transparent',
        bodyBg: 'transparent',
        siderBg: 'transparent',
        headerHeight: 52,
        headerPadding: '0 24px',
      },
      Menu: {
        // 侧边栏专用色：浅色主题=浅底深字，深色主题=深底白字。
        // 不要写死白色 —— 那会让侧边栏在切到浅色主题时不跟随（曾踩过）。
        itemSelectedBg: t.siderSelected,
        itemSelectedColor: t.siderText,
        itemColor: t.siderText,
        itemHoverBg: t.siderHover,
        itemHoverColor: t.siderText,
        itemHeight: 36,
        itemMarginInline: 8,
        itemMarginBlock: 3,
        itemBorderRadius: 6,
        subMenuItemBg: 'transparent',
        groupTitleColor: t.siderTextDim,
        iconSize: 16,
      },
      Table: {
        headerBg: t.soft,
        headerColor: t.textSecondary,
        headerSplitColor: 'transparent',
        headerBorderRadius: 0,
        rowHoverBg: t.soft,
        borderColor: t.border,
        cellPaddingBlock: 10,
        cellPaddingInline: 12,
        cellFontSize: 13,
        footerBg: t.soft,
      },
      Card: {
        paddingLG: 16,
        colorBorderSecondary: t.border,
      },
      Button: {
        fontWeight: 500,
        primaryShadow: 'none',
        defaultShadow: 'none',
        dangerShadow: 'none',
        paddingInline: 14,
      },
      Modal: {
        borderRadiusLG: 10,
        titleFontSize: 16,
        headerBg: t.canvas,
        contentBg: t.canvas,
        footerBg: 'transparent',
      },
      Drawer: {
        colorBgElevated: t.canvas,
      },
      Input: {
        activeShadow: `0 0 0 3px ${t.brandSoft}`,
        activeBorderColor: t.brand,
        hoverBorderColor: t.brandHover,
        // 凹陷面：输入框比卡片更深，视觉上「可填入」（参考站搜索框即比导航栏深）
        colorBgContainer: t.sunken,
      },
      InputNumber: {
        colorBgContainer: t.sunken,
      },
      Select: {
        optionSelectedBg: t.brandSoft,
        optionSelectedColor: t.brandText,
        optionSelectedFontWeight: 600,
        optionActiveBg: t.softStrong,
        selectorBg: t.sunken,
      },
      DatePicker: {
        colorBgContainer: t.sunken,
      },
      Cascader: {
        optionSelectedBg: t.brandSoft,
      },
      TreeSelect: {
        nodeSelectedBg: t.brandSoft,
      },
      Form: {
        itemMarginBottom: 16,
        labelColor: t.textSecondary,
        verticalLabelPadding: '0 0 4px',
      },
      Tag: {
        defaultBg: t.softStrong,
        defaultColor: t.textSecondary,
      },
      Tabs: {
        itemColor: t.textSecondary,
        itemSelectedColor: t.textPrimary,
        itemHoverColor: t.textPrimary,
        inkBarColor: t.brand,
        titleFontSize: 13,
      },
      Pagination: {
        itemActiveBg: t.brandSoft,
      },
      Segmented: {
        itemSelectedBg: t.canvas,
        itemSelectedColor: t.textPrimary,
        trackBg: t.soft,
      },
      Tooltip: {
        colorBgSpotlight: t.textPrimary,
      },
      Descriptions: {
        labelBg: t.soft,
      },
      Alert: {
        withDescriptionPadding: '10px 12px',
      },
    },
  }
}
