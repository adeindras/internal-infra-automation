import * as http from './httpwithretry.js';

export function getMatchTicket(baseUrl, namespace, token) {
  const url = `${baseUrl}/match2/v1/namespaces/${namespace}/match-tickets/me`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`,
              'Content-Type': 'application/json'},
    tags: { name: getMatchTicket.name },
  };
  return http.get(url, params);
}

export function createMatchTicket(baseUrl, namespace, body, token) {
  const url = `${baseUrl}/match2/v1/namespaces/${namespace}/match-tickets`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`,
              'Content-Type': 'application/json'},
    tags: { name: createMatchTicket.name },
  };
  return http.post(url, JSON.stringify(body), params);
}

export function deleteMatchTicket(baseUrl, namespace, ticketId, token) {
  const url = `${baseUrl}/match2/v1/namespaces/${namespace}/match-tickets/${ticketId}`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`,
              'Content-Type': 'application/json'},
    tags: { name: deleteMatchTicket.name },
  };
  return http.del(url, null, params);
}