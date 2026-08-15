import IconDefaultFile from '~icons/vscode-icons/default-file'
import IconDefaultFolder from '~icons/vscode-icons/default-folder'
import IconVue from '~icons/vscode-icons/file-type-vue'
import IconPython from '~icons/vscode-icons/file-type-python'
import IconJava from '~icons/vscode-icons/file-type-java'
import IconTs from '~icons/vscode-icons/file-type-typescript'
import IconJs from '~icons/vscode-icons/file-type-js'
import IconReact from '~icons/vscode-icons/file-type-reactts'
import IconHtml from '~icons/vscode-icons/file-type-html'
import IconCss from '~icons/vscode-icons/file-type-css'
import IconScss from '~icons/vscode-icons/file-type-scss'
import IconSass from '~icons/vscode-icons/file-type-sass'
import IconLess from '~icons/vscode-icons/file-type-less'
import IconJson from '~icons/vscode-icons/file-type-json'
import IconMarkdown from '~icons/vscode-icons/file-type-markdown'
import IconYaml from '~icons/vscode-icons/file-type-yaml'
import IconToml from '~icons/vscode-icons/file-type-toml'
import IconXml from '~icons/vscode-icons/file-type-xml'
import IconConfig from '~icons/vscode-icons/file-type-config'
import IconImage from '~icons/vscode-icons/file-type-image'
import IconPdf from '~icons/vscode-icons/file-type-pdf2'
import IconZip from '~icons/vscode-icons/file-type-zip'
import IconBinary from '~icons/vscode-icons/file-type-binary'
import IconWord from '~icons/vscode-icons/file-type-word'
import IconExcel from '~icons/vscode-icons/file-type-excel'
import IconPowerpoint from '~icons/vscode-icons/file-type-powerpoint'
import IconC from '~icons/vscode-icons/file-type-c'
import IconCpp from '~icons/vscode-icons/file-type-cpp'
import IconCsharp from '~icons/vscode-icons/file-type-csharp'
import IconGo from '~icons/vscode-icons/file-type-go'
import IconRust from '~icons/vscode-icons/file-type-rust'
import IconRuby from '~icons/vscode-icons/file-type-ruby'
import IconPhp from '~icons/vscode-icons/file-type-php'
import IconKotlin from '~icons/vscode-icons/file-type-kotlin'
import IconSwift from '~icons/vscode-icons/file-type-swift'
import IconShell from '~icons/vscode-icons/file-type-shell'
import IconSql from '~icons/vscode-icons/file-type-sql'
import IconDocker from '~icons/vscode-icons/file-type-docker'
import IconMake from '~icons/vscode-icons/file-type-makefile'
import IconGit from '~icons/vscode-icons/file-type-git'
import IconNpm from '~icons/vscode-icons/file-type-npm'
import IconYarn from '~icons/vscode-icons/file-type-yarn'
import IconPnpm from '~icons/vscode-icons/file-type-pnpm'
import IconVite from '~icons/vscode-icons/file-type-vite'
import IconWebpack from '~icons/vscode-icons/file-type-webpack'
import IconEslint from '~icons/vscode-icons/file-type-eslint'
import IconPrettier from '~icons/vscode-icons/file-type-prettier'
import IconNode from '~icons/vscode-icons/file-type-node'
import IconLicense from '~icons/vscode-icons/file-type-license'
import IconAstro from '~icons/vscode-icons/file-type-astro'
import IconGradle from '~icons/vscode-icons/file-type-gradle'
import IconLog from '~icons/vscode-icons/file-type-log'
import IconLua from '~icons/vscode-icons/file-type-lua'
import IconR from '~icons/vscode-icons/file-type-r'

export const FOLDER_ICON = IconDefaultFolder
export const DEFAULT_FILE_ICON = IconDefaultFile

export const FILE_ICON_MAP = {
  ts: IconTs,
  tsx: IconReact,
  mts: IconTs,
  cts: IconTs,
  js: IconJs,
  jsx: IconReact,
  mjs: IconJs,
  cjs: IconJs,
  vue: IconVue,
  py: IconPython,
  pyi: IconPython,
  ipynb: IconPython,
  java: IconJava,
  kt: IconKotlin,
  go: IconGo,
  rs: IconRust,
  c: IconC,
  h: IconC,
  cpp: IconCpp,
  hpp: IconCpp,
  cc: IconCpp,
  cs: IconCsharp,
  php: IconPhp,
  rb: IconRuby,
  swift: IconSwift,
  lua: IconLua,
  r: IconR,
  astro: IconAstro,
  sh: IconShell,
  bash: IconShell,
  zsh: IconShell,
  ps1: IconShell,
  bat: IconShell,
  cmd: IconShell,
  sql: IconSql,
  html: IconHtml,
  htm: IconHtml,
  css: IconCss,
  scss: IconScss,
  sass: IconSass,
  less: IconLess,
  json: IconJson,
  jsonc: IconJson,
  json5: IconJson,
  md: IconMarkdown,
  markdown: IconMarkdown,
  mdx: IconMarkdown,
  txt: IconLog,
  log: IconLog,
  yml: IconYaml,
  yaml: IconYaml,
  toml: IconToml,
  ini: IconConfig,
  conf: IconConfig,
  properties: IconConfig,
  xml: IconXml,
  svg: IconImage,
  png: IconImage,
  jpg: IconImage,
  jpeg: IconImage,
  gif: IconImage,
  webp: IconImage,
  ico: IconImage,
  bmp: IconImage,
  pdf: IconPdf,
  doc: IconWord,
  docx: IconWord,
  xls: IconExcel,
  xlsx: IconExcel,
  csv: IconExcel,
  ppt: IconPowerpoint,
  pptx: IconPowerpoint,
  zip: IconZip,
  rar: IconZip,
  '7z': IconZip,
  tar: IconZip,
  gz: IconZip,
  gradle: IconGradle,
  lock: IconBinary,
  env: IconConfig
}

const NAMED_FILES = {
  'dockerfile': IconDocker,
  'makefile': IconMake,
  'license': IconLicense,
  'readme': IconMarkdown,
  '.npmrc': IconNpm,
  '.yarnrc': IconYarn,
  'pnpm-lock.yaml': IconPnpm,
  'package-lock.json': IconNpm,
  'yarn.lock': IconYarn,
  'tsconfig.json': IconTs,
  'jsconfig.json': IconJs,
  'vite.config.js': IconVite,
  'vite.config.ts': IconVite,
  'vite.config.mjs': IconVite,
  'webpack.config.js': IconWebpack,
  'webpack.config.ts': IconWebpack,
  '.eslintrc.js': IconEslint,
  '.eslintrc.json': IconEslint,
  '.eslintrc.cjs': IconEslint,
  '.prettierrc': IconPrettier,
  '.prettierrc.json': IconPrettier,
  '.prettierrc.js': IconPrettier,
  '.editorconfig': IconConfig,
  '.gitignore': IconGit,
  '.gitattributes': IconGit,
  '.gitmodules': IconGit,
  'package.json': IconNode
}

export function fileIconFor(name) {
  if (!name) return DEFAULT_FILE_ICON
  const lower = name.toLowerCase()
  if (NAMED_FILES[lower]) return NAMED_FILES[lower]
  const dotIdx = lower.lastIndexOf('.')
  if (dotIdx <= 0) return DEFAULT_FILE_ICON
  const ext = lower.slice(dotIdx + 1)
  return FILE_ICON_MAP[ext] || DEFAULT_FILE_ICON
}
