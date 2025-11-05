import http from 'k6/http';
import { sleep } from 'k6';
import short from "./lib/short-uuid.js";
import { SharedArray } from 'k6/data';
import { WebSocket } from 'k6/experimental/websockets';
import { randomString, randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.1.0/index.js';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';
import { setTimeout, clearTimeout, setInterval, clearInterval } from 'k6/experimental/timers';
import urlencode from 'https://jslib.k6.io/form-urlencoded/3.0.0/index.js';
import ws from 'k6/ws';
import encoding from 'k6/encoding';

const data = new SharedArray('base data', function () {
  return JSON.parse(open('./config.json')).data;
});

const usersData = new SharedArray('open csv', function () {
  return papaparse.parse(open('./hosting-5k-email-username.csv'), { header: true }).data;
});

export const options = {
  scenarios:{
    default: {
      // executor: 'constant-arrival-rate',
      // preAllocatedVUs: 500,
      // rate: 500,
      // timeUnit: '15s',
      // duration: '20m',

      executor: 'constant-vus',
      vus: 500,
      duration: '120m',

      // executor: 'externally-controlled',
      // vus: 1,
      // maxVUs: 800,
      // duration: '10m',

      // executor: 'per-vu-iterations',
      // vus: 1,
      // iterations: 1,
      // maxDuration: '60m',

      // executor: 'ramping-vus',
      // startVUs: 0,
      // stages: [
      //   { duration: '3m', target: 100 },
      //   { duration: '3m', target: 0 },
      // ],
      // gracefulRampDown: '0s',
    }
  }
}

export function wsMessageToJSON(input) {
  const lines = input.split("\\n").filter(Boolean);
  const outputJSON = {};
  lines.forEach(line => {
    const [keyWithQuotes, ...valuesWithQuotes] = line.split(": ");
    const key = keyWithQuotes.replace(/^"|"$/g, "").trim();
    const value = valuesWithQuotes.join(": ").replace(/\\n(?!$)|"$/g, "").trim();
    if (key && value) {
      outputJSON[key] = value;
    }
  });
  return outputJSON;
}

export function uuid(scale=1) {
    let result = '';
    for (let i = 0; i < scale; i++) {
        result += short.uuid();
    }
    return result.replace(/-/g, "");
}

export function urlQueryParams(queryParams) {
    return Object.entries(queryParams).reduce((a, b) => `${a}${b[0]}=${b[1]}&`, '?');
}

export function iamAuthorize(clientId, state, codeChallenge) {
  const queryParams = urlQueryParams({
    response_type: 'code',
    client_id: clientId,
    state: state,
    code_challenge: codeChallenge,
  });

  const url = `${data[0].baseUrl}/iam/v3/oauth/authorize${queryParams}`;
  const params = {
    tags: { name: 'authorize_user' },
  };

  return http.get(url, params);
}

export function iamAuthenticate(email, password, requestId) {
  const url = `${data[0].baseUrl}/iam/v3/authenticate`;
  const body = {
    user_name: email,
    password: password,
    request_id: requestId,
  };

  const params = {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    tags: { name: 'authenticate_user' },
  };

  return http.post(url, body, params);
}

export function iamGetTokenV3(code, codeChallenge, clientId) {
  const url = `${data[0].baseUrl}/iam/v3/oauth/token`;
  const body = {
    grant_type: 'authorization_code',
    code: code,
    code_verifier: codeChallenge,
    client_id: clientId,
  };

  const params = {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    tags: { name: 'get_user_token_v3' },
  };

  return http.post(url, body, params);
}

export function getProfile(namespace, token) {
  const url = `${data[0].baseUrl}/iam/v3/public/users/me?includeAllPlatforms=false`;
  const params = {
    headers: { 'accept': 'application/json',
                'Authorization':  `Bearer ${token}`},
    tags: { name: 'get_profile' },
  };

  return http.get(url, params);
}

export function updateProfile(namespace, token) {
  const url = `${data[0].baseUrl}/iam/v3/public/namespaces/${namespace}/users/me`;
  const params = {
    headers: { 'accept': 'application/json',
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json'},
    tags: { name: 'update_profile' },
  };
  const body = JSON.stringify({
    "country": "ID"
  })

  return http.put(url, body, params);
}

export function getTokenViaGrantType1(username) {
  const auth = encoding.b64encode(`${data[0].clientId}:${data[0].clientSecret}`);
  const url = `${data[0].baseUrl}/iam/v3/oauth/token`;
  const body = urlencode({ 
    'grant_type': 'password', 
    'username': `${usersData[username].username}`, 
    'password': `${data[0].userLoginPassword}`})
  const params = {
    headers: { 
      'accept': 'application/json',
      'Authorization': `Basic ${auth}`,
      'Content-Type': 'application/x-www-form-urlencoded'
    },
    tags: { name: 'get_token_via_grant' },
  };
  return http.post(url, body, params);
}

export function login_grantType(user) {
  const resGetTokenViaGrant = getTokenViaGrantType1(user)
  let accessToken = JSON.parse(resGetTokenViaGrant.body)['access_token']
  return accessToken
}

export function login(user) {
  let state = uuid(2)
  let codeChallenge = uuid(2)
  const resAuth = iamAuthorize(data[0].iamClientIdGame, state, codeChallenge)
  const requestId = (resAuth.url).match(/request_id=([^&]*)/)[1]

  const resAtuhenticate = iamAuthenticate(usersData[user].email, data[0].userLoginPassword, requestId)
  const resCode = (resAtuhenticate.url).match(/code=([^&]*)/)[1]
  const resState = (resAtuhenticate.url).match(/state=([^&]*)/)[1]
  
  const resGetTokenV3 = iamGetTokenV3(resCode, codeChallenge, data[0].iamClientIdGame)
  let accessToken = JSON.parse(resGetTokenV3.body)['access_token']
  return accessToken
}

export function getCurrencies(namespace, token) {
  const url = `${data[0].baseUrl}/platform/public/namespaces/${namespace}/currencies`;
  const params = {
    headers: { 'accept': 'application/json',
                'Authorization':  `Bearer ${token}`},
    tags: { name: 'get_currencies' },
  };

  return http.get(url, params);
}

export function getStores(namespace, token) {
  const url = `${data[0].baseUrl}/platform/public/namespaces/${namespace}/stores`;
  const params = {
    headers: { 'accept': 'application/json',
                'Authorization':  `Bearer ${token}`},
    tags: { name: 'get_stores' },
  };

  return http.get(url, params);
}

export function getFriends(namespace, token) {
  const url = `${data[0].baseUrl}/friends/namespaces/${namespace}/me?limit=25&offset=0`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: 'get_friends' },
  };
  return http.get(url, params);
}

export function getIncomingFriends(namespace, token) {
  const url = `${data[0].baseUrl}/friends/namespaces/${namespace}/me/incoming?limit=25&offset=0`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: 'get_incoming_friends' },
  };
  return http.get(url, params);
}

export function createGameSession(namespace, token) {
  const url = `${data[0].baseUrl}/session/v1/public/namespaces/${namespace}/gamesession`;
  const body = JSON.stringify({
      configurationName: 'autobot'
    });

  const params = {
    headers: {  'accept': 'application/json',
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`
              },
    tags: { name: 'create_game_session' },
  };

  return http.post(url, body, params);
}

export function joinGameSession(namespace, session_id, token) {
  const url = `${data[0].baseUrl}/session/v1/public/namespaces/${namespace}/gamesessions/${session_id}/join`;

  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: 'join_game_session' },
  };

  return http.post(url, null, params);
}

export function leaveGameSession(namespace, session_id, token) {
  let resJoinGameSession = joinGameSession(data[0].namespaceGame, session_id, token);
  
  const url = `${data[0].baseUrl}/session/v1/public/namespaces/${namespace}/gamesessions/${session_id}/leave`;

  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: 'leave_game_session' },
  };
  return http.del(url, null, params);
}

export function deleteGameSession(namespace, token) {
  let resCreateGameSession = createGameSession(data[0].namespaceGame, token);
  console.log(`create game session response for user ${__VU} --> ${resCreateGameSession.status_text}`);

  sleep(Math.random() * 15);

  let sessionId = JSON.parse(resCreateGameSession.body)['id'];
  const url = `${data[0].baseUrl}/session/v1/public/namespaces/${namespace}/gamesessions/${sessionId}`;

  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: 'delete_game_session' },
  };
  return http.del(url, null, params);
}

export function createParty(namespace, token) {
  const url =  `${data[0].baseUrl}/session/v1/public/namespaces/${namespace}/party`
  const params = {
    headers: {  'accept': 'application/json',
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}` },
    tags: { name: 'create_party' },
  };

  const body = JSON.stringify({
    name: 'default',
    type: 'NONE',
    joinability: 'OPEN',
    minPlayers: 1,
    maxPlayers: 8,
    inviteTimeout: 60,
    inactiveTimeout: 60,
    textChat: true
  });

  return http.post(url, body, params);
}

export function getChatTopic(namespace, token) {
  const url = `${data[0].baseUrl}/chat/public/namespaces/${namespace}/topic`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: 'get_chat_topic' },
  };
  return http.get(url, params);
}

export function updateRecord({namespace, user_id}, token) {
  const url = `${data[0].baseUrl}/cloudsave/v1/namespaces/${namespace}/users/${user_id}/records/damiano_test`;
  const body = JSON.stringify({ method: 'update_record'});
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}`,
              'Content-Type': 'application/json'},
    tags: { name: 'update_cs_record' },
  };

  return http.post(url, body, params);
}

export function getGameProfle({namespace, user_id}, token) {
  const url = `${data[0].baseUrl}/social/public/namespaces/${namespace}/profiles?userIds=${user_id}`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: 'get_game_profile' },
  };
  return http.get(url, params);
}

export function getStatCode({namespace, user_id}, token) {
  const url = `${data[0].baseUrl}/social/v2/public/namespaces/${namespace}/users/${user_id}/statitems/value/bulk?statCodes=cppsdkteststatcode709187076`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: 'get_stat_code' },
  };
  return http.get(url, params);
}

export function getUserProfile ({namespace, user_id}, token) {
  const url = `${data[0].baseUrl}/v1/public/namespaces/${namespace}/users/${user_id}/profiles`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: 'get_user_profile' },
  };
  return http.get(url, params);
}

export function getGroup ({namespace}, token) {
  const url = `${data[0].baseUrl}/group/v1/public/namespaces/${namespace}/groups`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: 'get_all_open_group' },
  };
  return http.get(url, params);
}

export function getConfig ({namespace}, token) {
  const url = `${data[0].baseUrl}/config/v1/public/namespaces/${namespace}/configs`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: 'get_all_config' },
  };
  return http.get(url, params);
}

export function getContentV2 ({namespace}, token) {
  const url = `${data[0].baseUrl}/ugc/v2/public/namespaces/${namespace}/contents`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: 'get_ugc_content' },
  };
  return http.get(url, params);
}

export function getType ({namespace}, token) {
  const url = `${data[0].baseUrl}/ugc/v1/public/namespaces/${namespace}/types`;
  const params = {
    headers: { 'accept': 'application/json',
              'Authorization': `Bearer ${token}` },
    tags: { name: 'get_ugc_type' },
  };
  return http.get(url, params);
}


// export function setup() {

//   let resCreateGameSession = createGameSession(data[0].namespaceGame, login(1));
//   let sessionId = JSON.parse(resCreateGameSession.body)['id'];

//   sleep(Math.random() * 15);
//   return { sessionId: sessionId }
// }

export default function(setup) {
  let token = login_grantType(__VU)

  // getStores(data[0].namespaceGame, token);

  // getProfile(data[0].namespaceGame, token);
  
  // updateProfile(data[0].namespaceGame, token);

  // getFriends(data[0].namespaceGame, token);

  // getIncomingFriends(data[0].namespaceGame, token);

  startWSWorker(token);

  // let resCreateGameSession = createGameSession(data[0].namespaceGame, login(__VU));
  // console.log(`create game session response for user ${__VU} --> ${resCreateGameSession.status_text}`);

  // let sessionId = JSON.parse(resCreateGameSession.body)['id'];

  // let res = joinGameSession(data[0].namespaceGame, sessionId, login(2));
  // console.log(`join game session response for user 2 --> ${res.status_text}`);

  // let res2 = leaveGameSession(data[0].namespaceGame, sessionId, login(2));
  // console.log(`leave game session response for user 2 --> ${res2.status_text}`);

  // sleep(Math.random() * 9);

  // let res3 = deleteGameSession(data[0].namespaceGame ,login(__VU));
  // console.log(`delete game session response for user ${__VU} --> ${res3.status_text}`);

  // chatWS(login(__VU))

  // const auth = `${usersData[0].username}:${data[0].clientSecret}`
  // console.log(auth);

  // let gProfile = getProfile(data[0].namespaceGame, token);
  // const paramVariable = {
  //   namespace: data[0].namespaceGame,
  //   user_id: gProfile.json("userId")
  // }
  // getUserProfile(paramVariable, token)
  // getGroup(paramVariable, token)
}

function startWSWorker(token) {
  let param = {
  headers: {
      Authorization: `Bearer ${token}`
    }
  };
  // create a new websocket connection
  const ws = new WebSocket(`${data[0].baseUrlWs}/lobby`, null, param);

  ws.addEventListener('open', () => {
    // // change the user name
    // ws.send(JSON.stringify({ event: 'SET_NAME', new_name: `Croc ${__VU}:${id}` }));

    // // listen for messages/errors and log them into console
    ws.addEventListener('message', (e) => {
      const parsedMsg = wsMessageToJSON(JSON.stringify(e.data));
      const msgType = parsedMsg.type;
      if (msgType === 'disconnectNotif') {
        console.log('disconected')
      } else if (msgType === 'connectNotif') {
        // send a message every 2-8 seconds
        const intervalId = setInterval(() => {
          ws.send(
            'type: listOfFriendsRequest\n' +
            `id: message123\n`
            );

          let gProfile = getProfile(data[0].namespaceGame, token);
          const paramVariable = {
            namespace: data[0].namespaceGame,
            user_id: gProfile.json("userId")
          }

          updateRecord(paramVariable, token)
          // getStatCode(paramVariable, token)
          getGameProfle(paramVariable, token)
          getUserProfile(paramVariable, token)
          getGroup(paramVariable, token)
          getConfig(paramVariable, token)
          getType(paramVariable, token)
          // getCurrencies(data[0].namespaceGame, token)
          // getStores(data[0].namespaceGame, token)
          // ws.send(
          //   'type: partyInfoRequest\n' +
          //   `id: message123\n`
          //   );
          // ws.send(
          //   'type: friendsStatusRequest\n' +
          //   `id: message123\n`
          //   );
          // ws.send(
          //   'type: partyLeaveRequest\n' +
          //   `id: message123\n`
          //   );
          // ws.send(
          //   'type: listOutgoingFriendsRequest\n' +
          //   `id: message123\n`
          //   );
        }, randomIntBetween(2000, 8000));
      }
    });

    // // after a sessionDuration stop sending messages and leave the room
    // const timeout1id = setTimeout(function () {
    //   clearInterval(intervalId);
    //   console.log(`VU ${__VU}:${id}: ${sessionDuration}ms passed, leaving the chat`);
    //   ws.send(JSON.stringify({ event: 'LEAVE' }));
    // }, sessionDuration);

    // // after a sessionDuration + 3s close the connection
    // const timeout2id = setTimeout(function () {
    //   console.log(`Closing the socket forcefully 3s after graceful LEAVE`);
      // ws.close();
    // }, sessionDuration + 3000);

    // // when connection is closing, clean up the previously created timers
    // ws.addEventListener('close', () => {
    //   clearTimeout(timeout1id);
    //   clearTimeout(timeout2id);
    // });
  });
}

function chatWS(token) {
  let param = {
  headers: {
      Authorization: `Bearer ${token}`
    }
  };
  const ws = new WebSocket(`${data[0].baseUrlWs}/chat`, null, param);
  ws.addEventListener('open', () => {
    ws.addEventListener('message', (e) => {
      const msgType = JSON.parse(e.data)['method']
      if (msgType == 'eventConnected') {
        sleep(Math.random() * 15);
        ws.send(
          JSON.stringify({
            "jsonrpc": "2.0",
            "method": "actionQueryTopic",
            "params": {
              "namespace": `${data[0].namespaceGame}`,
              "keyword": "",
              "offset": 0,
              "limit": 100
            },
            "id": "actionQueryTopic-1"
          })
        )

        sleep(Math.random() * 15);

        let res = createParty(data[0].namespaceGame, token);
        console.log(`create party status -> ${res.status_text}`)


        // let res2 = getChatTopic(data[0].namespaceGame, token);
        // console.log(`get chat topic status -> ${res2.status_text}`)
      } else if (msgType == 'eventAddedToTopic') {
        console.log((JSON.parse(e.data)['params'])['topicId'])
      }
    })
  })
}

