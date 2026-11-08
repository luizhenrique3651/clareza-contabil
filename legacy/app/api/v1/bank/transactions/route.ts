import { handleBankTransactionsGet } from "../../_shared/bank-collection";

export const dynamic = "force-dynamic";

export function GET(request: Request) {
  return handleBankTransactionsGet(request);
}
