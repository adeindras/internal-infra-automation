import * as http from './httpwithretry.js';

export function createGameSession(baseUrl, namespace, body, token) {
  const url = `${baseUrl}/session/v1/public/namespaces/${namespace}/gamesession`;
  const params = {
    headers: {  'accept': 'application/json',
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`
              },
    tags: { name: createGameSession.name },
  };

  return http.post(url, JSON.stringify(body), params);
}

export function updateGameSession(baseUrl, namespace, sessionId, body, token) {
  const url = `${baseUrl}/session/v1/public/namespaces/${namespace}/gamesessions/${sessionId}`;

  const params = {
    headers: { 'accept': 'application/json',
              'Content-Type': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: updateGameSession.name },
  };
  return http.patch(url, JSON.stringify(body), params);
}

export function getMyGameSession(baseUrl, namespace, token) {
  const url = `${baseUrl}/session/v1/public/namespaces/${namespace}/users/me/gamesessions`;

  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: getMyGameSession.name },
  };
  return http.get(url, params);
}

export function deleteGameSession(baseUrl, namespace, session_id, token) {
  const url = `${baseUrl}/session/v1/public/namespaces/${namespace}/gamesessions/${session_id}`;

  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: deleteGameSession.name },
  };
  return http.del(url, null, params);
}