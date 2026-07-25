export const THEME_SETTING_OPTIONS = Object.freeze({
  mode: [
    { value: 'system', label: '系统', preview: 'system' },
    { value: 'light', label: '浅色', preview: 'light' },
    { value: 'dark', label: '深色', preview: 'dark' },
  ],
  colorPreset: [
    { value: 'default', label: '默认', preview: 'linear-gradient(135deg, #ff9b45 0%, #f6d743 25%, #10b981 52%, #0ea5e9 74%, #d875ff 100%)' },
    { value: 'anthropic', label: 'Anthropic', preview: 'linear-gradient(135deg, #fee4d6, #ea8e76)' },
    { value: 'contrast', label: '高对比', preview: 'linear-gradient(135deg, #161616, #f2f2f2)' },
    { value: 'night', label: '暗夜', preview: 'linear-gradient(135deg, #47614d, #9a6c9e)' },
    { value: 'rose', label: '玫瑰花园', preview: 'linear-gradient(135deg, #ef1b59, #ff96b2)' },
    { value: 'lake', label: '湖光', preview: 'linear-gradient(135deg, #00c98b, #128a87)' },
    { value: 'sunset', label: '日落霞光', preview: 'linear-gradient(135deg, #d33f3f, #fb9b78)' },
    { value: 'forest', label: '森林低语', preview: 'linear-gradient(135deg, #007e72, #5c7b98)' },
    { value: 'ocean', label: '海风', preview: 'linear-gradient(135deg, #2f64ea, #5965ea)' },
    { value: 'lavender', label: '薰衣草梦', preview: 'linear-gradient(135deg, #9369d9, #9fc8dc)' },
  ],
  fontFamily: [
    { value: 'auto', label: 'Auto', preview: 'Auto' },
    { value: 'sans', label: 'Sans', preview: 'Aa' },
    { value: 'serif', label: 'Serif', preview: 'Aa' },
  ],
  radius: [
    { value: 'auto', label: 'Auto' }, { value: '0', label: '0' }, { value: '0.3', label: '0.3' },
    { value: '0.5', label: '0.5' }, { value: '0.75', label: '0.75' }, { value: '1.0', label: '1.0' },
  ],
  density: [
    { value: 'compact', label: '紧凑' }, { value: 'default', label: '默认' },
    { value: 'relaxed', label: '宽松' }, { value: 'spacious', label: '超大' },
  ],
  sidebar: [
    { value: 'embedded', label: '内嵌' }, { value: 'floating', label: '浮动' }, { value: 'sidebar', label: '侧边栏' },
  ],
  layout: [
    { value: 'default', label: '默认' }, { value: 'compact', label: '紧凑' }, { value: 'fullscreen', label: '全屏布局' },
  ],
  contentWidth: [{ value: 'full', label: '全宽' }, { value: 'centered', label: '居中' }],
  direction: [{ value: 'ltr', label: '从左到右' }, { value: 'rtl', label: '从右到左' }],
})

export const THEME_SETTINGS_SECTIONS = Object.freeze([
  { key: 'mode', title: '主题', columns: 3 },
  { key: 'colorPreset', title: '颜色预设', columns: 4, compact: true },
  { key: 'fontFamily', title: '字体', columns: 3 },
  { key: 'radius', title: '圆角', columns: 6, compact: true },
  { key: 'density', title: '密度', columns: 4 },
  { key: 'sidebar', title: '侧边栏', columns: 3 },
  { key: 'layout', title: '布局', columns: 3 },
  { key: 'contentWidth', title: '内容宽度', columns: 2 },
  { key: 'direction', title: '方向', columns: 2 },
])
