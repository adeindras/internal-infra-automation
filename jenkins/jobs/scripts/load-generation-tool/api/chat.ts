import { uuid } from '../lib/utils.js';
import * as http from './httpwithretry.js';
import WSClient from './wsclient.js';

export function expectChatMethod(method: string) {
  return message => {
    message && message.data && console.log(message.timestamp, __VU, "message", message.data);
    if (!(message && message.data)) return false;
    const res = JSON.parse(message.data);
    return res.method === method;
  }
}

export async function wsSendChat(ws: WSClient, topicId:string, message: string) {
  const req = {
    jsonrpc: "2.0",
    id: uuid(),
    method: "sendChat",
    params: {
      topicId: topicId,
      message: message
    }
  };
  ws.sendMessage(JSON.stringify(req));
  const res = await ws.waitMessage(expectChatMethod('sendChat'), 10);
  if (res.data) return JSON.parse(res.data);
  return res;
}

export async function wsCreateTopic(
  ws: WSClient,
  namespace: string,
  topicType: string,
  name: string,
  members: string[],
  admins=new Array<string>, 
  isJoinable = true,
  isChannel = false
) {
  const req = {
    jsonrpc: "2.0",
    id: uuid(),
    method: "actionCreateTopic",
    params: {
      namespace: namespace,
      type: topicType,
      name: name,
      members: members,
      isChannel: isChannel,
      isJoinable: isJoinable,
      admins: topicType !== 'PERSONAL' ? admins : undefined
    }
  }
  ws.sendMessage(JSON.stringify(req));
  const res = await ws.waitMessage(expectChatMethod('eventAddedToTopic'), 10);
  if (res.data) return JSON.parse(res.data);
  return res;
}

export async function wsDeleteTopic(ws: WSClient, topicId: string) {
  const req = {
    jsonrpc: "2.0",
    id: uuid(),
    method: "actionDeleteTopic",
    params: {
      topicId: topicId
    }
  }
  ws.sendMessage(JSON.stringify(req));
  const res = await ws.waitMessage(expectChatMethod('actionDeleteTopic'), 10);
  if (res.data) return JSON.parse(res.data);
  return res;
}

export function getChatTopic(baseUrl: string, namespace: string, token: string) {
  const url = `${baseUrl}/chat/public/namespaces/${namespace}/topic`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: getChatTopic.name },
  };
  return http.get(url, params);
}

export function createGroupTopic(baseUrl: string, namespace: string, body: object, token: string) {
  const url = `${baseUrl}/chat/admin/namespaces/${namespace}/topic`;
  const params = {
    headers: { 
      'accept': 'application/json',
      'content-type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    tags: { name: createGroupTopic.name },
  };
  return http.post(url, JSON.stringify(body), params);
}

export function updateGroupTopic(baseUrl: string, namespace: string, topicId: string, body: object, token: string) {
  const url = `${baseUrl}/chat/admin/namespaces/${namespace}/topic/${topicId}`;
  const params = {
    headers: { 
      'accept': 'application/json',
      'content-type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    tags: { name: updateGroupTopic.name },
  };
  return http.put(url, JSON.stringify(body), params);
}

export function deleteGroupTopic(baseUrl: string, namespace: string, topicId: string, token: string) {
  const url = `${baseUrl}/chat/admin/namespaces/${namespace}/topic/${topicId}`;
  const params = {
    headers: { 
      'accept': 'application/json',
      'content-type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    tags: { name: deleteGroupTopic.name },
  };
  return http.del(url, null, params);
}

export function removeUserFromTopic(baseUrl: string, namespace: string, topicId: string, userId: string, token: string) {
  const url = `${baseUrl}/chat/admin/namespaces/${namespace}/topic/${topicId}/user/${userId}`;
  const params = {
    headers: { 
      'accept': 'application/json',
      'content-type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    tags: { name: removeUserFromTopic.name },
  };
  return http.del(url, null, params);
}
