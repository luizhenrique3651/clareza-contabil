import {
  handleFinancialGet,
  handleFinancialPost,
} from "../_shared/financial-collection";

export const dynamic = "force-dynamic";

export function GET(request: Request) {
  return handleFinancialGet(request, "payable");
}

export function POST(request: Request) {
  return handleFinancialPost(request, "payable");
}
