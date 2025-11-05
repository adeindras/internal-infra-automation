import * as http from './httpwithretry.js';

export function getFriends(baseUrl, namespace, token) {
  const url = `${baseUrl}/friends/namespaces/${namespace}/me?limit=25&offset=0`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: getFriends.name },
  };
  return http.get(url, params);
}

export function getIncomingFriends(baseUrl, namespace, token) {
  const url = `${baseUrl}/friends/namespaces/${namespace}/me/incoming?limit=25&offset=0`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: getFriends.name },
  };
  return http.get(url, params);
}

export function addFriend(baseUrl, namespace, body, token) {
  const url = `${baseUrl}/friends/namespaces/${namespace}/me/request`;
  const params = {
    headers: {
      'accept': 'application/json',
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    tags: { name: addFriend.name },
  };
  return http.post(url, JSON.stringify(body), params);
}

export function acceptFriend(baseUrl, namespace, friendId, token) {
  const url = `${baseUrl}/friends/namespaces/${namespace}/me/request/accept`;
  const params = {
    headers: {
      'accept': 'application/json',
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    tags: { name: acceptFriend.name },
  };
  return http.post(url, JSON.stringify({friendId: friendId}), params);
}

export function unfriend(baseUrl, namespace, friendId, token) {
  const url = `${baseUrl}/friends/namespaces/${namespace}/me/unfriend`;
  const params = {
    headers: {
      'accept': 'application/json',
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    tags: { name: unfriend.name },
  };
  return http.post(url, JSON.stringify({friendId: friendId}), params);
}

export function bulkDeleteFriendship(baseUrl, namespace, userId, friendIds, token) {
  const url = `${baseUrl}/friends/namespaces/${namespace}/users/${userId}/delete/bulk`;
  const params = {
    headers: {
      'accept': 'application/json',
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    tags: { name: bulkDeleteFriendship.name },
  };
  return http.post(url, JSON.stringify({friendIds: friendIds}), params);
}