import * as http from './httpwithretry.js';

export function createProfile(baseUrl, namespace, token) {
  const url = `${baseUrl}/basic/v1/public/namespaces/${namespace}/users/me/profiles`;
  const body = JSON.stringify({language: "en"});
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`,
              'Content-Type': 'application/json'},
    tags: { name: createProfile.name },
  };
  let res = http.post(url, body, params);
  return res
}

export function updateProfile(baseUrl, namespace, token) {
  const url = `${baseUrl}/basic/v1/public/namespaces/${namespace}/users/me/profiles`;
  const body = JSON.stringify({language: "id"});
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`,
              'Content-Type': 'application/json'},
    tags: { name: updateProfile.name },
  };
  return http.put(url, body, params);
}

export function getProfile(baseUrl, namespace, token) {
  const url = `${baseUrl}/basic/v1/public/namespaces/${namespace}/users/me/profiles`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`,
              'Content-Type': 'application/json'},
    tags: { name: getProfile.name },
  };
  return http.get(url, params);
}

export function deleteProfile(baseUrl, namespace, user_id, token) {
  const url = `${baseUrl}/basic/v1/admin/namespaces/${namespace}/users/${user_id}/profiles`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`,
              'Content-Type': 'application/json'},
    tags: { name: deleteProfile.name }
  };
  return http.del(url, null, params);
}