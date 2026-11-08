import { handleFinancialSettlementPost } from "../../_shared/financial-collection";

export const runtime = "nodejs";

export function POST(request: Request) {
  return handleFinancialSettlementPost(request, "receivable");
}
