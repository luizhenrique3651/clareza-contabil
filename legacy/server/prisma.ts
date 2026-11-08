import { PrismaPg } from "@prisma/adapter-pg";
import { PrismaClient } from "../generated/prisma/client";

export class DatabaseConfigurationError extends Error {
  constructor() {
    super("DATABASE_URL não está configurada no servidor.");
    this.name = "DatabaseConfigurationError";
  }
}

type PrismaGlobal = typeof globalThis & {
  clarezaPrisma?: PrismaClient;
};

export function getPrisma(): PrismaClient {
  const globalScope = globalThis as PrismaGlobal;
  if (globalScope.clarezaPrisma) return globalScope.clarezaPrisma;

  const connectionString = process.env.DATABASE_URL;
  if (!connectionString) throw new DatabaseConfigurationError();

  const adapter = new PrismaPg({ connectionString });
  const client = new PrismaClient({ adapter });

  if (process.env.NODE_ENV !== "production") {
    globalScope.clarezaPrisma = client;
  }

  return client;
}
