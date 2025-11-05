import * as http from './httpwithretry.js';

export function getContentV2 (baseUrl, namespace, token) {
  const url = `${baseUrl}/ugc/v2/public/namespaces/${namespace}/contents`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: getContentV2.name },
  };
  return http.get(url, params);
}

export function getUGCType (baseUrl, namespace, token) {
  const url = `${baseUrl}/ugc/v1/public/namespaces/${namespace}/types`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: getUGCType.name },
  };
  return http.get(url, params);
}