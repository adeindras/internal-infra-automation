import * as http from './httpwithretry.js';

export function getTurnServers(baseUrl: string, token: string) {
  const url = `${baseUrl}/turnmanager/turn`;
  const params = {
    headers: {
      'accept': 'application/json',
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    tags: { name: getTurnServers.name },
  };
  return http.get(url, params);
}

export function storeTurnSecret(baseUrl: string, body: object, token: string) {
  const url = `${baseUrl}/turnmanager/servers/secret`;
  const params = {
    headers: {
      'accept': 'application/json',
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    tags: { name: storeTurnSecret.name },
  };
  return http.post(url, JSON.stringify(body), params);
}