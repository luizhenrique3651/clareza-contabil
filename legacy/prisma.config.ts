import { defineConfig } from "prisma/config";

const localDevelopmentUrl =
  "postgresql://clarity_owner:replace_with_local_password@127.0.0.1:5432/clarity_mvp?schema=public";

export default defineConfig({
  schema: "prisma/schema.prisma",
  migrations: {
    path: "prisma/migrations",
  },
  datasource: {
    // Migrations prefer the owner-only direct connection. The fallback exists
    // solely so validation and client generation work in a fresh local clone.
    url:
      process.env.DIRECT_DATABASE_URL ??
      process.env.DATABASE_URL ??
      localDevelopmentUrl,
  },
});
