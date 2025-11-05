import * as http from './httpwithretry.js';

export function getConfig (baseUrl, namespace, token) {
  const url = `${baseUrl}/config/v1/public/namespaces/${namespace}/configs`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: getConfig.name },
  };
  return http.get(url, params);
}