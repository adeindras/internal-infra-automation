import * as http from './httpwithretry.js';

export interface GameHistorySearchParams {
  userID?: string;
  matchPool?: string;
  statusV2?: string;
  joinability?: string;
  configurationName?: string;
  dsPodName?: string;
  isPersistent?: string;
  completedOnly?: string;
  startDate?: string;
  endDate?: string;
  orderBy?: string;
  order?: string;
  offset?: string;
  limit?: string;
}

export function getAllGameHistory(baseurl: string, namespace: string, searchParams: GameHistorySearchParams, token: string) {
  let searchStrings = new URLSearchParams(Object.entries(searchParams)).toString();
  searchStrings = searchStrings ? '?' + searchStrings : '';
  const url = `${baseurl}/sessionhistory/v1/admin/namespaces/${namespace}/gamesessions${searchStrings}`;
  const params = {
    headers: {
      'accept': 'application/json',
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    tags: { name: getAllGameHistory.name },
  }
  return http.get(url, params);
}

export function createTicketObservabilityRequest(baseurl: string, namespace: string, body: object, token: string) {
  const url = `${baseurl}/sessionhistory/v2/admin/namespaces/${namespace}/xray/tickets`;
  const params = {
    headers: {
      'accept': 'application/json',
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    tags: { name: createTicketObservabilityRequest.name },
  }
  return http.post(url, JSON.stringify(body), params);
}