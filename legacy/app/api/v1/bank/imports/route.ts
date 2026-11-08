import {
  handleBankImportsGet,
  handleBankImportsPost,
} from "../../_shared/bank-collection";

export const dynamic = "force-dynamic";

export function GET(request: Request) {
  return handleBankImportsGet(request);
}

export function POST(request: Request) {
  return handleBankImportsPost(request);
}
