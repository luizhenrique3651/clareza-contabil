type HistoryResponseGuard = Readonly<{
  requestRevision: number;
  currentRevision: number;
  requestedRecordId: string;
  responseRecordId: string;
}>;

export function isCurrentHistoryResponse({
  requestRevision,
  currentRevision,
  requestedRecordId,
  responseRecordId,
}: HistoryResponseGuard) {
  return (
    requestRevision === currentRevision &&
    requestedRecordId === responseRecordId
  );
}
