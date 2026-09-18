/**
 * 工作台演示数据（由设计稿转换而来）
 *
 * 代码内容以 **结构化 token** 表示：[{ t: 文本, k: 语法类 }]
 * k 取值：kw（关键字）/ str（字符串）/ cm（注释），空表示普通文本。
 * 之所以不用 HTML 字符串，是为了避免 v-html —— 渲染统一走组件。
 *
 * 修改代码示例时请保持「每行一个 token 数组」的形状。
 */

/** 工作台里可打开的文件：文件名 → 行数组（每行是 token 数组） */
export const WORKBENCH_FILES = {
  "config.py": [
    [
      { t: "import", k: "kw" },
      { t: " os" }
    ],
        [],
    [
      { t: "BASE_DIR = os.path.dirname(__file__)" }
    ],
        [],
    [
      { t: "class", k: "kw" },
      { t: " Config:" }
    ],
    [
      { t: "    SECRET_KEY = os.getenv(" },
      { t: "'SECRET_KEY'", k: "str" },
      { t: ")" }
    ],
    [
      { t: "    TRACK_MODIFICATIONS = " },
      { t: "False", k: "kw" }
    ],
    [
      { t: "    MODEL_DIR = " },
      { t: "'models'", k: "str" }
    ],
    [
      { t: "    UPLOAD_DIR = " },
      { t: "'data'", k: "str" }
    ],
    [
      { t: "    MAX_CONTENT_LENGTH = 16 * 2**20" }
    ],
    [
      { t: "    APP_VERSION = " },
      { t: "'1.0.0'", k: "str" }
    ]
  ],
  "app.py": [
    [
      { t: "from", k: "kw" },
      { t: " flask " },
      { t: "import", k: "kw" },
      { t: " Flask" }
    ],
    [
      { t: "from", k: "kw" },
      { t: " config " },
      { t: "import", k: "kw" },
      { t: " Config" }
    ],
        [],
    [
      { t: "app = Flask(__name__)" }
    ],
    [
      { t: "app.config.from_object(Config)" }
    ],
        [],
    [
      { t: "@app.route(" },
      { t: "'/'", k: "str" },
      { t: ")" }
    ],
    [
      { t: "def", k: "kw" },
      { t: " index():" }
    ],
    [
      { t: "    " },
      { t: "return", k: "kw" },
      { t: " render_template(" },
      { t: "'i.html'", k: "str" },
      { t: ")" }
    ],
        [],
    [
      { t: "@app.route(" },
      { t: "'/health'", k: "str" },
      { t: ")" }
    ],
    [
      { t: "def", k: "kw" },
      { t: " health():" }
    ],
    [
      { t: "    " },
      { t: "return", k: "kw" },
      { t: " jsonify(OK)" }
    ]
  ],
  "models.py": [
    [
      { t: "from", k: "kw" },
      { t: " sqlalchemy " },
      { t: "import", k: "kw" },
      { t: " Column" }
    ],
        [],
    [
      { t: "db = SQLAlchemy()" }
    ],
        [],
    [
      { t: "class", k: "kw" },
      { t: " User(db.Model):" }
    ],
    [
      { t: "    id = Column(Integer, primary_key=1)" }
    ],
    [
      { t: "    name = Column(String(64), unique=1)" }
    ],
    [
      { t: "    email = Column(String(120))" }
    ],
        [],
    [
      { t: "    " },
      { t: "def", k: "kw" },
      { t: " __repr__(self):" }
    ],
    [
      { t: "        " },
      { t: "return", k: "kw" },
      { t: " " },
      { t: "f'<User {self.name}>'", k: "str" }
    ]
  ],
  "test_app.py": [
    [
      { t: "import", k: "kw" },
      { t: " pytest" }
    ],
    [
      { t: "from", k: "kw" },
      { t: " app " },
      { t: "import", k: "kw" },
      { t: " app" }
    ],
        [],
    [
      { t: "@pytest.fixture" }
    ],
    [
      { t: "def", k: "kw" },
      { t: " client():" }
    ],
    [
      { t: "    " },
      { t: "return", k: "kw" },
      { t: " app.test_client()" }
    ],
        [],
    [
      { t: "def", k: "kw" },
      { t: " test_index(client):" }
    ],
    [
      { t: "    r = client.get(" },
      { t: "'/'", k: "str" },
      { t: ")" }
    ],
    [
      { t: "    " },
      { t: "assert", k: "kw" },
      { t: " r.status == 200" }
    ]
  ],
  "README.md": [
    [
      { t: "# 薪资预测系统", k: "cm" }
    ],
        [],
    [
      { t: "基于 Flask 的薪资预测应用。" }
    ],
        [],
    [
      { t: "## 快速开始", k: "cm" }
    ],
        [],
    [
      { t: "    pip install -r req.txt" }
    ],
    [
      { t: "    python app.py" }
    ]
  ],
}

/** 文件树（与真实工作台的根目录结构一致） */
export const WORKBENCH_TREE = [
  { name: '.labex', type: 'dir', children: [{ name: 'cache', type: 'dir' }] },
  { name: '.labex-agent', type: 'dir', children: [{ name: 'agent.log', type: 'file' }] },
  { name: 'Microsoft', type: 'dir', children: [] },
  { name: 'work-4425', type: 'dir', open: true, children: [
    { name: 'app.py', type: 'file' },
    { name: 'config.py', type: 'file' },
    { name: 'models.py', type: 'file' },
    { name: 'test_app.py', type: 'file' },
    { name: 'README.md', type: 'file' }
  ] },
  { name: '.labex-agentignore', type: 'file' },
  { name: 'LabexAgent.md', type: 'file' }
]

/** AI 面板的 5 个标签页 */
export const WORKBENCH_PANELS = [
  { key: "chat", label: "对话" },
  { key: "usage", label: "用量" },
  { key: "review", label: "审查" },
  { key: "extensions", label: "扩展" },
  { key: "terminal", label: "终端" },
]

/** 三种模式胶囊 */
export const WORKBENCH_MODES = [
  { key: "build", label: "构建" },
  { key: "plan", label: "规划" },
  { key: "explore", label: "探索" },
]

/** 演示场景：提问 → 工具调用 → 回答 */
export const WORKBENCH_SCENES = {
  build: {
    file: "config.py",
    ask: "给 config.py 加上 APP_VERSION，然后跑一下语法检查",
    steps: [
      { name: "read_file:", sub: "(config.py)", state: "0.08s · 成功", at: 380, tokens: 1240 },
      { name: "apply_patch:", sub: "(config.py · +2)", state: "已应用", at: 780, tokens: 3120 },
      { name: "shell:", sub: "(py_compile)", state: "0.56s · 退出码 0", at: 1320, tokens: 4580 },
    ],
    reply: "加好了，语法检查也通过了。"
  },
  plan: {
    file: "models.py",
    ask: "这个项目的目录结构是怎样的",
    steps: [
      { name: "list_files:", sub: "(/)", state: "0.11s · 成功", at: 380, tokens: 980 },
      { name: "glob:", sub: "(**/*.py)", state: "0.43s · 找到 52 处", at: 900, tokens: 2140 },
    ],
    reply: "根目录下是 app.py、config.py、models.py、routes/、templates/ 与 static/。"
  },
  explore: {
    file: "app.py",
    ask: "加一个 /health 接口，返回服务状态",
    steps: [
      { name: "grep:", sub: "(app.route)", state: "0.21s · 成功", at: 380, tokens: 1520 },
      { name: "apply_patch:", sub: "(app.py · +3)", state: "已应用", at: 820, tokens: 3860 },
      { name: "shell:", sub: "(py_compile)", state: "0.55s · 退出码 0", at: 1400, tokens: 5240 },
    ],
    reply: "接口加好了，返回 <b>{'status': 'healthy'}</b>。"
  },
}

/** 场景播放顺序（与模式胶囊的构建/规划/探索对应） */
export const WORKBENCH_SCENE_ORDER = ["build","plan","explore"]
