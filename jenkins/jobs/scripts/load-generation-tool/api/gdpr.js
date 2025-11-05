import * as http from './httpwithretry.js';

export function getDeletionStatus(baseUrl, token) {
  const url = `${baseUrl}/gdpr/public/users/me/deletions/status`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`},
    tags: { name: getDeletionStatus.name },
  };
  return http.get(url, params);
}

export function getRegisteredServicesConfiguration(baseUrl, namespace, token) {
  const url = `${baseUrl}/gdpr/admin/namespaces/${namespace}/services/configurations`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`},
    tags: { name: getRegisteredServicesConfiguration.name },
  };
  return http.get(url, params);
}