/**
 * 落地页文案与改动卡数据（由设计稿转换而来）
 *
 * 改动卡每行是 { t: 文本, k: 类名 }，k 取值来自设计稿的 .dl 修饰类：
 *   is-hunk（区块头）/ is-add（新增）/ is-del（删除）/ ''（上下文）
 * 组件据此渲染行级高亮，避免使用 v-html。
 */

/** 顶栏导航。requiresAuth 用于未登录时改为唤起登录弹窗，而不是撞到路由守卫 */
export const SITE_NAV = [
  { label: '开发教程', to: '/tutorials', requiresAuth: false },
  { label: '项目空间', to: '/projects', requiresAuth: true }
]

/** 首屏 */
export const HERO = {
  kicker: "浏览器里的编程 Agent",
  titleWords: [
    { text: 'Labex', delay: '.06s' },
    { text: 'Agent', delay: '.17s' }
  ],
  sub: "在浏览器里读代码、改文件、跑测试，做完交给你；过程和结果都留在对话里，随时能回看。"
}

/** 章节标签 */
export const SECTIONS = {
  changes: {
    id: 'changes',
    title: "改动看得见，也退得回",
    intro: "每个被改动的文件都留下改动前后的对照，逐行可查。"
  },
  runtime: {
    id: 'runtime',
    title: "从一句话，到改完代码",
    intro: "提出需求，它读代码、改文件、跑测试，做完把结果留在对话里。"
  }
}

/** 改动卡：3 个文件的 diff */
export const DIFF_PANES = [
  {
    file: "ApiResponse.java",
    path: "backend/src/main/java/com/blog/dto/ApiResponse.java",
    add: 32,
    del: 5,
    lines: [
      { t: "@@ -1,15 +1,42 @@", k: "is-hunk" },
      { t: "package com.blog.dto;" },
      { t: " " },
      { t: "import lombok.AllArgsConstructor;", k: "is-del" },
      { t: "import lombok.Data;", k: "is-del" },
      { t: " ", k: "is-del" },
      { t: "@Data", k: "is-del" },
      { t: "@AllArgsConstructor", k: "is-del" },
      { t: "public class ApiResponse<T> {" },
      { t: "    private boolean success;" },
      { t: "    private String message;" },
      { t: "    private T data;" },
      { t: " " },
      { t: "    public ApiResponse() {}", k: "is-add" },
      { t: " ", k: "is-add" },
      { t: "    public ApiResponse(boolean success, String message, T data) {", k: "is-add" },
      { t: "        this.success = success;", k: "is-add" },
      { t: "        this.message = message;", k: "is-add" },
      { t: "        this.data = data;", k: "is-add" },
      { t: "    }", k: "is-add" },
      { t: " ", k: "is-add" },
      { t: "    public boolean isSuccess() {", k: "is-add" },
      { t: "        return success;", k: "is-add" },
      { t: "    }", k: "is-add" },
      { t: " ", k: "is-add" },
      { t: "    public void setSuccess(boolean success) {", k: "is-add" },
    ]
  },
  {
    file: "AuthController.java",
    path: "backend/src/main/java/com/blog/controller/AuthController.java",
    add: 5,
    del: 5,
    lines: [
      { t: "@@ -32,18 +32,18 @@ public class AuthController {", k: "is-hunk" },
      { t: "    public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody LoginRequest request) {" },
      { t: "        try {" },
      { t: "            LoginResponse response = authService.login(request);" },
      { t: "            return ResponseEntity.ok(ApiResponse.success(\"登录成功\", response));", k: "is-del" },
      { t: "            return ResponseEntity.ok(ApiResponse.ok(\"登录成功\", response));", k: "is-add" },
      { t: "        } catch (RuntimeException e) {" },
      { t: "            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));", k: "is-del" },
      { t: "            return ResponseEntity.badRequest().body(ApiResponse.fail(e.getMessage()));", k: "is-add" },
      { t: "        }" },
      { t: "    }" },
      { t: " " },
      { t: "    @GetMapping(\"/me\")" },
      { t: "    public ResponseEntity<ApiResponse<UserDTO>> getCurrentUser(Authentication authentication) {" },
      { t: "        if (authentication == null) {" },
      { t: "            return ResponseEntity.status(401).body(ApiResponse.error(\"未登录\"));", k: "is-del" },
      { t: "            return ResponseEntity.status(401).body(ApiResponse.fail(\"未登录\"));", k: "is-add" },
      { t: "        }" },
      { t: "        UserDTO user = authService.getCurrentUser(authentication.getName());", k: "is-del" },
      { t: "        return ResponseEntity.ok(ApiResponse.success(\"获取成功\", user));", k: "is-del" },
      { t: "        UserDTO user = authService.getCurrentUser();", k: "is-add" },
      { t: "        return ResponseEntity.ok(ApiResponse.ok(\"获取成功\", user));", k: "is-add" },
      { t: "    }" },
      { t: "}" },
    ]
  },
  {
    file: "PostRepository.java",
    path: "backend/src/main/java/com/blog/repository/PostRepository.java",
    add: 1,
    del: 1,
    lines: [
      { t: "@@ -1,26 +1,26 @@", k: "is-hunk" },
      { t: "package com.blog.repository;" },
      { t: " " },
      { t: "import com.blog.entity.Post;" },
      { t: "import org.springframework.data.domain.Page;" },
      { t: "import org.springframework.data.domain.Pageable;" },
      { t: "import org.springframework.data.jpa.repository.JpaRepository;" },
      { t: "import org.springframework.data.jpa.repository.Query;" },
      { t: "import org.springframework.data.repository.query.Param;" },
      { t: "import org.springframework.stereotype.Repository;" },
      { t: " " },
      { t: "import java.util.List;" },
      { t: " " },
      { t: "@Repository" },
      { t: "public interface PostRepository extends JpaRepository<Post, Long> {" },
      { t: "    Page<Post> findByStatus(String status, Pageable pageable);" },
      { t: "    Page<Post> findByCategoryIdAndStatus(Long categoryId, String status, Pageable pageable);" },
      { t: "    " },
      { t: "    @Query(\"SELECT p FROM Post p JOIN p.postTags pt WHERE pt.tag.id = :tagId AND p.status = :status\")", k: "is-del" },
      { t: "    @Query(\"SELECT p FROM Post p JOIN p.tags t WHERE t.id = :tagId AND p.status = :status\")", k: "is-add" },
      { t: "    Page<Post> findByTagIdAndStatus(@Param(\"tagId\") Long tagId, @Param(\"status\") String status, Pageable pageable);" },
      { t: "    " },
      { t: "    @Query(\"SELECT p FROM Post p WHERE p.status = :status AND (p.title LIKE %:keyword% OR p.content LIKE %:keyword%)\")" },
      { t: "    Page<Post> searchByKeyword(@Param(\"keyword\") String keyword, @Param(\"status\") String status, Pageable pageable);" },
      { t: "    " },
      { t: "    long countByStatus(String status);" },
    ]
  }
]

/** 改动卡底部合计 */
export const DIFF_TOTAL = { add: 38, del: 11 }

/** 运行演示区块的媒体与说明 */
export const RUNTIME_MEDIA = {
  video: {
    poster: '/landing/labexagent-demo-poster.webp',
    src: '/landing/labexagent-demo.mp4',
    caption: "在构建模式下提出需求：给配置文件加一个版本号，顺带跑语法检查。它读了代码、写入改动、执行验证。"
  },
  shots: [
    {
      tag: '工作台',
      src: '/landing/terminal.webp',
      fallback: '/landing/terminal.png',
      alt: "LabexAgent 工作台：左侧文件树、中间代码编辑器、底部终端，可同时操作",
      caption: "文件树、编辑器、终端、对话都在同一页，不用来回切工具。"
    },
    {
      tag: '用量',
      src: '/landing/usage.webp',
      fallback: '/landing/usage.png',
      alt: "LabexAgent 用量面板：总 token 消耗、输入输出占比、缓存命中率与消耗热力图",
      caption: "每一轮的 token 消耗都有记录。"
    }
  ]
}

/**
 * 页脚。
 *
 * 所有链接都必须指向真实页面：`#` 占位会让用户以为站点有这些内容却点不动，
 * 比不提供链接更糟。资源栏指向教程系统里实际存在的文档 slug（与后端 /api/tutorials 一致）。
 */
export const FOOTER = {
  tagline: "浏览器里的编程 Agent。读代码、改文件、跑测试，做完把结果留在对话里。",
  columns: [
    { label: '产品', links: [
      { label: '项目空间', to: '/projects' },
      { label: '开发教程', to: '/tutorials' },
      { label: '改动审查', to: '#changes' },
      { label: '使用演示', to: '#runtime' }
    ] },
    { label: '资源', links: [
      { label: '快速上手', to: '/tutorials/getting-started' },
      { label: '模型配置', to: '/tutorials/api-key-and-provider' },
      { label: 'MCP 与扩展', to: '/tutorials/mcp-servers' },
      { label: '常见问题', to: '/tutorials/common-issues' }
    ] },
    { label: '法律', links: [
      { label: '使用说明', to: '/legal/terms' },
      { label: '隐私说明', to: '/legal/privacy' },
      { label: '开源许可', to: '/legal/licenses' }
    ] }
  ],
  copy: "© 2026 LabexAgent · 保留所有权利",
  bottomLinks: [
    { label: '使用说明', to: '/legal/terms' },
    { label: '隐私说明', to: '/legal/privacy' },
    { label: '回到顶部', to: '#top', top: true }
  ]
}
