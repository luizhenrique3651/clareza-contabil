import {
  handleFinancialGet,
  handleFinancialPost,
} from "../_shared/financial-collection";

export const dynamic = "force-dynamic";

export function GET(request: Request) {
  return handleFinancialGet(request, "receivable");
}

export function POST(request: Request) {
  return handleFinancialPost(request, "receivable");
}
