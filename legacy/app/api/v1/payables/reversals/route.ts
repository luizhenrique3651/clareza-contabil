import { handleFinancialReversalPost } from "../../_shared/financial-collection";

export const runtime = "nodejs";

export function POST(request: Request) {
  return handleFinancialReversalPost(request, "payable");
}
