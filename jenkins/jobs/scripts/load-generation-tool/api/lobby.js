import * as http from './httpwithretry.js';

export function getLobbyConfig (baseUrl, namespace, token) {
  const url = `${baseUrl}/lobby/v1/admin/config/namespaces/${namespace}`;
  const params = {
    headers: {
      'accept': 'application/json',
      'Content-Type':  'application/json',
      'Authorization': `Bearer ${token}`
    },
    tags: { name: getLobbyConfig.name },
  };
  return http.get(url, params);
}

export function updateLobbyConfig (baseUrl, namespace, body, token) {
  const url = `${baseUrl}/lobby/v1/admin/config/namespaces/${namespace}`;
  const params = {
    headers: {
      'accept': 'application/json',
      'Content-Type':  'application/json',
      'Authorization': `Bearer ${token}`
    },
    tags: { name: updateLobbyConfig.name },
  };
  return http.put(url, JSON.stringify(body), params);
}