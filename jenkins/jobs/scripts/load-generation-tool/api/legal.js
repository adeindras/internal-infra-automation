import * as http from './httpwithretry.js';

export function getAgreement(baseUrl, token) {
  const url = `${baseUrl}/agreement/public/agreements/policies`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`},
    tags: { name: getAgreement.name },
  };
  return http.get(url, params);
}

export function getEligibilities(baseUrl, namespace, token) {
  const url = `${baseUrl}/agreement/public/eligibilities/namespaces/${namespace}`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`},
    tags: { name: getEligibilities.name },
  };
  return http.get(url, params);
}