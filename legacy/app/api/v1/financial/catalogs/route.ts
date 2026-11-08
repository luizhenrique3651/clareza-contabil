import {
  handleFinancialCatalogsGet,
  handleFinancialCatalogsPost,
} from "../../_shared/financial-collection";

export const runtime = "nodejs";

export function GET(request: Request) {
  return handleFinancialCatalogsGet(request);
}

export function POST(request: Request) {
  return handleFinancialCatalogsPost(request);
}
