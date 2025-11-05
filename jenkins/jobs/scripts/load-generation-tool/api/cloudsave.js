import * as http from './httpwithretry.js';

export function createRecord(baseUrl, namespace, gameRecordKey, token) {
  const url = `${baseUrl}/cloudsave/v1/namespaces/${namespace}/records/${gameRecordKey}`;
  const body = JSON.stringify({method: 'create_record'});
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`,
              'Content-Type': 'application/json'},
    tags: { name: createRecord.name },
  };
  return http.post(url, body, params);
}

export function updateRecord(baseUrl, namespace, gameRecordKey, token) {
  const url = `${baseUrl}/cloudsave/v1/namespaces/${namespace}/records/${gameRecordKey}`;
  const body = JSON.stringify({method: 'update_record'});
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`,
              'Content-Type': 'application/json'},
    tags: { name: updateRecord.name },
  };
  return http.put(url, body, params);
}

export function deleteRecord(baseUrl, namespace, gameRecordKey, token) {
  const url = `${baseUrl}/cloudsave/v1/namespaces/${namespace}/records/${gameRecordKey}`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`,
              'Content-Type': 'application/json'},
    tags: { name: deleteRecord.name },
  };
  return http.del(url, {}, params);
}

export function getRecord(baseUrl, namespace, gameRecordKey, token) {
  const url = `${baseUrl}/cloudsave/v1/namespaces/${namespace}/records/${gameRecordKey}`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`,
              'Content-Type': 'application/json'},
    tags: { name: getRecord.name },
  };
  return http.get(url, params);
}

export function createPlayerRecord(baseUrl, namespace, user_id, gameRecordKey, token) {
  const url = `${baseUrl}/cloudsave/v1/namespaces/${namespace}/users/${user_id}/records/${gameRecordKey}`;
  const body = JSON.stringify({ method: 'create_record'});
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`,
              'Content-Type': 'application/json'},
    tags: { name: createPlayerRecord.name },
  };

  return http.post(url, body, params);
}

export function updatePlayerRecord(baseUrl, namespace, user_id, gameRecordKey, token) {
  const url = `${baseUrl}/cloudsave/v1/namespaces/${namespace}/users/${user_id}/records/${gameRecordKey}`;
  const body = JSON.stringify({ method: 'update_record'});
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`,
              'Content-Type': 'application/json'},
    tags: { name: updatePlayerRecord.name },
  };

  return http.put(url, body, params);
}

export function deletePlayerRecord(baseUrl, namespace, user_id, gameRecordKey, token) {
  const url = `${baseUrl}/cloudsave/v1/namespaces/${namespace}/users/${user_id}/records/${gameRecordKey}`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`,
              'Content-Type': 'application/json'},
    tags: { name: deletePlayerRecord.name },
  };

  return http.del(url, null, params);
}