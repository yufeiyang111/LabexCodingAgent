# LabexAgent

LabexAgent is a standalone extraction of the Labex cloud coding workspace. It keeps the Agent workspace, project file operations, terminal, model configuration, skills, MCP server settings, streaming chat, and apply/reject/undo diff flow, with a small username/password auth layer for user isolation.

## Structure

- `backend/`: Spring Boot 3, Java 17, MyBatis-Plus, JWT auth, Agent runtime, project/workspace APIs.
- `frontend/`: Vue 3, Vite, Pinia, Element Plus, Monaco editor, workspace UI.

## Runtime Requirements

- Java 17
- Maven 3.9+
- Node.js 18+
- MySQL 8+

## Environment

Copy `.env.example` values into your shell or service manager. Do not commit real secrets.

Required:

- `LABEX_AGENT_DB_URL`
- `LABEX_AGENT_DB_USERNAME`
- `LABEX_AGENT_DB_PASSWORD`
- `LABEX_AGENT_JWT_SECRET`

Optional:

- `MINIMAX_API_KEY` for the MiniMax fallback provider.
- `TAVILY_API_KEY` for web search.
- `LABEX_AGENT_PROJECT_BASE_PATH` and `LABEX_AGENT_UPLOAD_PATH` for local runtime storage.

## Database

Create an empty MySQL database before first start:

```sql
CREATE DATABASE labex_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

The backend initializes the standalone schema from `backend/src/main/resources/sql/schema.sql` on startup.

## Start Backend

```powershell
cd D:\LabexAgent\backend
mvn spring-boot:run
```

Backend API base path: `http://localhost:8080/api`

## Start Frontend

```powershell
cd D:\LabexAgent\frontend
npm install
npm run dev
```

Frontend dev server: `http://localhost:3000`

The Vite dev server proxies `/api` to `http://localhost:8080`.

## Auth

The standalone app exposes:

- `POST /api/auth/register`
- `POST /api/auth/login`
- `GET /api/auth/userinfo`

JWT subject is the numeric user ID, so the extracted Agent/project code can continue to use its existing `studentId` ownership checks internally.

## Notes

- Original Labex project files are not required by this extracted project at runtime.
- Runtime workspace data is stored under `LABEX_AGENT_PROJECT_BASE_PATH`.
- Generated folders such as `node_modules/`, `dist/`, `target/`, `workspaces/`, and `uploads/` are ignored by git.
