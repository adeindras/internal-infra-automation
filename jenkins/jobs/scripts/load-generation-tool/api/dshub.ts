import * as http from './httpwithretry.js';

export function sendNotificationToDS(baseUrl:string, namespace: string, dsId: string, body: object, token: string) {
  const url = `${baseUrl}/dshub/namespace/${namespace}/ds/${dsId}/notify`;
  const params = {
    headers: {
      'accept': 'application/json',
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    tags: { name: sendNotificationToDS.name },
  };
  return http.post(url, JSON.stringify(body), params);
}