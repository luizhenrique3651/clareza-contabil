import { handleFinancialHistoryGet } from "../../_shared/financial-collection";

export const runtime = "nodejs";

export function GET(request: Request) {
  return handleFinancialHistoryGet(request, "payable");
}
