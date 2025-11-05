import { clearInterval, setInterval, setTimeout } from "k6/timers";
import * as friendsAPI from "../api/friends.js"
import { getTokenViaGrantType } from "../api/iam.js"
import WSClient from "../api/wsclient.js";
import { checkResponse, wsMessageToJSON } from "../lib/utils.js";
import { Dependencies } from "../models/dependencies.js";

let testDone = true;
let testFine:boolean|undefined;
let adminAccessToken = '';
export async function callTestFriends(baseUrl:string, namespaceGame:string, userId:string, friendId:string, accessToken:string, lobbyWS:WSClient) {
  testDone = false;
  try {
    if ((__VU % 2) === 1) {
      testFine = checkResponse(friendsAPI.addFriend(baseUrl, namespaceGame, {friendId: friendId}, accessToken), {
      'added friend': r => r && r.status === 201
      });
    }

    if ((__VU % 2) === 0) {
      const wsMessage = await lobbyWS.waitMessage((message) => {
        if (!message || !message.data) return false;
        const lobbyMessage = wsMessageToJSON(message.data);
        return lobbyMessage.type === 'requestFriendsNotif';
      }, 10);
      if (wsMessage.error) console.error(wsMessage.error);
      const incomingFriendId = wsMessageToJSON(wsMessage.data).friendId;
      let isPooling = !incomingFriendId;
      let retryDuration = 2000;
      while(isPooling) {
        const res = friendsAPI.getIncomingFriends(baseUrl, namespaceGame, accessToken);
        const ok = checkResponse(res, {
          'getting incoming friends after got no notification': r => r && r.status === 200
        });
        const found = ok && res.json(`friendIDs.#(${friendId})`)?.toString();
        isPooling = !found;
        await new Promise<void>(resolve => setTimeout(() => resolve(), retryDuration));
        retryDuration *=2;
        if (retryDuration >= parseInt(__ENV.maxBackoffDuration || '30')*1000) break;
      }
      testFine = checkResponse(friendsAPI.acceptFriend(baseUrl, namespaceGame, incomingFriendId, accessToken), {
        'accepted friend request': r => r && r.status === 204
      });
      checkResponse(friendsAPI.getFriends(baseUrl, namespaceGame, accessToken), {
        'got friend list': r => r && r.status === 200
      });
    }

    if ((__VU % 2) === 1) {
      const wsMessage = await lobbyWS.waitMessage((message) => message && message.data && wsMessageToJSON(message.data).type === 'acceptFriendsNotif', 20);
      let isPooling = wsMessage.error;
      let retryDuration = 2000;
      while(isPooling) {
        const res = friendsAPI.getFriends(baseUrl, namespaceGame, accessToken);
        const ok = checkResponse(res, {
          'getting friends after got no notification': r => r && r.status === 200
        });
        const found = ok && res.json(`friendIDs.#(${friendId})`)?.toString();
        isPooling = !found;
        await new Promise<void>(resolve => setTimeout(() => resolve(), retryDuration));
        retryDuration *=2;
        if (retryDuration >= parseInt(__ENV.maxBackoffDuration || '30')*1000) break;
      }
      if (isPooling) console.error(wsMessage.error);
      testFine = checkResponse(friendsAPI.unfriend(baseUrl, namespaceGame, friendId, accessToken), {
        'unfriend': r => r && r.status === 204
      });
    }
  } catch(err) {
    console.error(err);
    checkResponse(friendsAPI.bulkDeleteFriendship(baseUrl, namespaceGame, userId, [friendId], accessToken),
    {
      'clean up failed friendship': r => r && r.status === 200
    });
  } finally {
    testDone = true;
    if (!testFine) {
      checkResponse(friendsAPI.bulkDeleteFriendship(baseUrl, namespaceGame, userId, [friendId], accessToken),
      {
        'clean up failed friendship': r => r && r.status === 200
      });
    }
  }
}

export async function testFriends(dep:Dependencies) {
  if (parseInt(__ENV.NUMBER_OF_USER) % 2 !== 0) throw new Error('odd number test users for friends scenario. this scenario need an even number of users');
  if (!dep.adminAccessToken) dep.adminAccessToken = getTokenViaGrantType(
    dep.configData.baseURLDirect, 
    dep.configData.adminEmail, 
    dep.configData.adminPassword, 
    dep.configData.clientId, 
    dep.configData.clientSecret
  )?.toString()!;
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
  await new Promise<void>(resolve => {
    const waitInterval = setInterval(() => testDone && resolve(clearInterval(waitInterval)), 50);
  });
  await callTestFriends(dep.configData.baseURLDirect, dep.configData.namespaceGame, dep.userData.userId, dep.userData.friendId, dep.accessToken, dep.lobbyWS!);
}

export function deleteFriendships(dep:Dependencies) {
  const userIds = dep.userData.map(user => user.userId);
  
  userIds.forEach((userId, i) => {
    dep.accessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.userData[i].emailAddress, 
      dep.userData[i].password, 
      dep.seedData.gameClientId, 
      dep.seedData.gameClientSecret
    )?.toString()!;

    friendsAPI.bulkDeleteFriendship(
      dep.configData.baseURLDirect,
      dep.configData.namespaceGame,
      userId,
      userIds,
      dep.accessToken,
    )
  });
}