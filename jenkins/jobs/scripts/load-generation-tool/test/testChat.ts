import * as chatAPI from "../api/chat.ts"
import { getTokenViaGrantType } from "../api/iam.js"
import WSClient from "../api/wsclient.js";
import { checkResponse } from "../lib/utils.js";
import { Dependencies } from "../models/dependencies.js";

let adminAccessToken = '';
let connected;
export async function callTestChat(baseUrl: string, namespaceGame: string, userId: string, ws:WSClient, accessToken: string) {
  if (!connected) {
    console.log('ensure player is connected');
    connected = await ws.waitMessage(chatAPI.expectChatMethod('eventConnected'), 10);
  }
  console.log(__VU, 'create group topic');
  const createRes = await chatAPI.wsCreateTopic(ws, namespaceGame, 'GROUP', 'topicum', [userId], [userId]);
  if (!checkResponse(createRes, {
    'created chat topic using ws': r => r && !r.error
  })) return;
  console.log(createRes);
  
  const topicId = createRes && createRes.params && createRes.params.topicId;
  console.log(topicId);
  console.log(__VU, 'send chat message');
  checkResponse(await chatAPI.wsSendChat(ws, topicId!, userId), {
    'send chat using websocket': r => r && !r.error
  }, {name: chatAPI.wsSendChat.name});

  console.log(__VU, 'get chat topic');
  checkResponse(chatAPI.getChatTopic(baseUrl, namespaceGame, accessToken), {
    'get chat topic': r => r && r.status === 200
  });

  console.log(__VU, 'update chat topic');
  checkResponse(chatAPI.updateGroupTopic(baseUrl, namespaceGame, topicId!, {
    description: 'updated'
  }, adminAccessToken), {
    'updated the topic': r => r && r.status === 200
  });
  
  console.log(__VU, 'remove user from topic');
  checkResponse(chatAPI.removeUserFromTopic(baseUrl, namespaceGame, topicId!, userId, adminAccessToken), {
    'removed user from the topic': r => r && r.status === 200
  });

  console.log(__VU, 'delete group topic');
  checkResponse(chatAPI.deleteGroupTopic(baseUrl, namespaceGame, topicId!, adminAccessToken), {
    'removed user from the topic': r => r && r.status === 200
  });
}

export async function testChat(dep:Dependencies) {
  if (!dep.adminAccessToken) {
    dep.adminAccessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.configData.adminEmail, 
      dep.configData.adminPassword, 
      dep.seedData.gameClientId, 
      dep.seedData.gameClientSecret
    )?.toString()!;
  }
  adminAccessToken = dep.adminAccessToken;
  if (!dep.accessToken) {
    dep.accessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.userData.emailAddress, 
      dep.userData.password, 
      dep.seedData.gameClientId, 
      dep.seedData.gameClientSecret
    )?.toString()!;
  }

  await callTestChat(dep.configData.baseURLDirect, dep.configData.namespaceGame, dep.userData.userId, dep.chatWS!, dep.accessToken);
}