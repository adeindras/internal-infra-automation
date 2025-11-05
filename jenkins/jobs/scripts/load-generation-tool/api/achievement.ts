import * as http from './httpwithretry.js';

export function createNewAchievement(baseUrl: string, namespace: string, body: object, token: string) {
  const url = `${baseUrl}/achievement/v1/admin/namespaces/${namespace}/achievements`;
  const params = {
    headers: {
      'accept': 'application/json',
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    tags: { name: createNewAchievement.name },
  };
  return http.post(url, JSON.stringify(body), params);
}

export function claimGlobalAchievement(baseUrl: string, namespace: string, userId: string, achievementCode: string, token: string) {
  const url = `${baseUrl}/achievement/v1/public/namespaces/${namespace}/users/${userId}/global/achievements/${achievementCode}/claim`;
  const params = {
    headers: {
      'accept': 'application/json',
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    tags: { name: claimGlobalAchievement.name },
  };
  return http.retryRequest('POST', url, null, params, (response) => response && (response.status === 404));
}

export function unlockUserAchievement(baseUrl: string, namespace: string, userId: string, achievementCode: string, token: string) {
  const url = `${baseUrl}/achievement/v1/public/namespaces/${namespace}/users/${userId}/achievements/${achievementCode}/unlock`;
  const params = {
    headers: {
      'accept': 'application/json',
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    tags: { name: unlockUserAchievement.name },
  };
  return http.put(url, null, params);
}

export function listGlobalAchievements(baseUrl: string, namespace: string, token: string) {
  const url = `${baseUrl}/achievement/v1/public/namespaces/${namespace}/global/achievements`;
  const params = {
    headers: {
      'accept': 'application/json',
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    tags: { name: listGlobalAchievements.name },
  };
  return http.get(url, params);
}

export function deleteAchievements(baseUrl: string, namespace: string, achievementCode:string, token: string) {
  const url = `${baseUrl}/achievement/v1/admin/namespaces/${namespace}/achievements/${achievementCode}`;
  const params = {
    headers: {
      'accept': 'application/json',
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    tags: { name: deleteAchievements.name },
  };
  return http.del(url, null, params);
}