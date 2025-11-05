import * as http from './httpwithretry.js';

export function getGroup (baseUrl, namespace, token) {
  const url = `${baseUrl}/group/v1/public/namespaces/${namespace}/groups`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: getGroup.name },
  };
  return http.get(url, params);
}