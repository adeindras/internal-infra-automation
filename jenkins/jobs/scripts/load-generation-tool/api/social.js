import * as http from './httpwithretry.js';

export function adminUpdateStatCode(baseUrl, namespace, randomId, statCode, token) {
  const url = `${baseUrl}/social/v1/admin/namespaces/${namespace}/stats/${statCode}`;
  const body = JSON.stringify({
      description: `update_stat_code ${randomId}`,
    });

  const params = {
    headers: {  'accept': 'application/json',
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`
              },
    tags: { name: adminUpdateStatCode.name },
  };

  return http.patch(url, body, params);
}

export function createStats(baseUrl, namespace, body, token) {
  const url = `${baseUrl}/social/v1/admin/namespaces/${namespace}/stats`
  const params = {
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}` 
    },
    tags: { name: createStats.name }
  }
  return http.post(url, JSON.stringify(body), params);
}

export function deleteStats(baseUrl, namespace, statsCode, token) {
  const url = `${baseUrl}/social/v1/admin/namespaces/${namespace}/stats/${statsCode}`
  const params = {
    headers: {
      'accept': 'application/json',
      'Authorization': `Bearer ${token}` 
    },
    tags: { name: deleteStats.name }
  }
  return http.del(url, null, params);
}

export function updateStatsValue(baseUrl, namespace, user_id, statCode, value, token) {
  const url = `${baseUrl}/social/v1/public/namespaces/${namespace}/users/${user_id}/stats/${statCode}/statitems/value`
  const params = {
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}` 
    },
    tags: { name: updateStatsValue.name }
  }
  return http.put(url, JSON.stringify({"inc": value}), params);
}