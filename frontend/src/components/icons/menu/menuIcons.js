import { h } from 'vue'

/** 右键菜单内联图标集：单个模块集中定义，避免为每个小图标建独立组件文件。 */

function makeIcon(children) {
  return function Icon() {
    return h('svg', {
      width: '13',
      height: '13',
      viewBox: '0 0 24 24',
      fill: 'none',
      stroke: 'currentColor',
      'stroke-width': '2',
      'stroke-linecap': 'round',
      'stroke-linejoin': 'round'
    }, children.map(([tag, attrs]) => h(tag, attrs)))
  }
}

export const IconTerminal = makeIcon([
  ['polyline', { points: '4 17 10 11 4 5' }],
  ['line', { x1: '12', y1: '19', x2: '20', y2: '19' }]
])

export const IconSearchFolder = makeIcon([
  ['circle', { cx: '11', cy: '11', r: '7' }],
  ['line', { x1: '21', y1: '21', x2: '16.2', y2: '16.2' }]
])

export const IconDownload = makeIcon([
  ['path', { d: 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4' }],
  ['polyline', { points: '7 10 12 15 17 10' }],
  ['line', { x1: '12', y1: '3', x2: '12', y2: '15' }]
])

export const IconUpload = makeIcon([
  ['path', { d: 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4' }],
  ['polyline', { points: '17 8 12 3 7 8' }],
  ['line', { x1: '12', y1: '3', x2: '12', y2: '15' }]
])

export const IconEye = makeIcon([
  ['path', { d: 'M1 12s4-7 11-7 11 7 11 7-4 7-11 7S1 12 1 12z' }],
  ['circle', { cx: '12', cy: '12', r: '3' }]
])

export const IconChatPlus = makeIcon([
  ['path', { d: 'M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z' }],
  ['line', { x1: '12', y1: '7', x2: '12', y2: '13' }],
  ['line', { x1: '9', y1: '10', x2: '15', y2: '10' }]
])

export const IconHistory = makeIcon([
  ['path', { d: 'M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8' }],
  ['path', { d: 'M3 3v5h5' }],
  ['polyline', { points: '12 7 12 12 15 14' }]
])

export const IconScissors = makeIcon([
  ['circle', { cx: '6', cy: '6', r: '3' }],
  ['circle', { cx: '6', cy: '18', r: '3' }],
  ['line', { x1: '20', y1: '4', x2: '8.12', y2: '15.88' }],
  ['line', { x1: '14.47', y1: '14.48', x2: '20', y2: '20' }],
  ['line', { x1: '8.12', y1: '8.12', x2: '12', y2: '12' }]
])

export const IconCopyDoc = makeIcon([
  ['rect', { x: '9', y: '9', width: '13', height: '13', rx: '2', ry: '2' }],
  ['path', { d: 'M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1' }]
])

export const IconClipboardPaste = makeIcon([
  ['rect', { x: '8', y: '2', width: '8', height: '4', rx: '1' }],
  ['path', { d: 'M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2' }]
])

export const IconCopyPath = makeIcon([
  ['path', { d: 'M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71' }],
  ['path', { d: 'M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71' }]
])

export const IconLinkPath = makeIcon([
  ['path', { d: 'M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z' }],
  ['line', { x1: '9', y1: '14', x2: '15', y2: '14' }]
])

export const IconRename = makeIcon([
  ['path', { d: 'M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7' }],
  ['path', { d: 'M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z' }]
])

export const IconTrash = makeIcon([
  ['polyline', { points: '3 6 5 6 21 6' }],
  ['path', { d: 'M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2' }]
])

export const IconNewFile = makeIcon([
  ['path', { d: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z' }],
  ['polyline', { points: '14 2 14 8 20 8' }],
  ['line', { x1: '12', y1: '18', x2: '12', y2: '12' }],
  ['line', { x1: '9', y1: '15', x2: '15', y2: '15' }]
])

export const IconNewFolder = makeIcon([
  ['path', { d: 'M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z' }],
  ['line', { x1: '12', y1: '11', x2: '12', y2: '17' }],
  ['line', { x1: '9', y1: '14', x2: '15', y2: '14' }]
])
