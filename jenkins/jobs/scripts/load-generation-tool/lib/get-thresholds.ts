import * as iamAPI from '../api/iam.js';
import * as basicAPI from '../api/basic.js';
import * as chatAPI from '../api/chat.ts';
import * as cloudSaveAPI from '../api/cloudsave.js';
import * as configAPI from '../api/config.js';
import * as friendsAPI from '../api/friends.js';
import * as gdprAPI from '../api/gdpr.js';
import * as groupAPI from '../api/group.js';
import * as legalAPI from '../api/legal.js';
import * as lobbyAPI from '../api/lobby.js';
import * as match2API from '../api/match2.js';
import * as platformAPI from '../api/platform.js';
import * as sessionAPI from '../api/session.js';
import * as socialAPI from '../api/social.js';
import * as ugcAPI from '../api/ugc.js';
import * as sessionHistoryAPI from "../api/sessionhistory.ts";
import * as turnmanagerAPI from '../api/turnmanager.ts';
import * as dsHubAPI from '../api/dshub.ts';
import * as achievementAPI from "../api/achievement.ts";

const serviceAPIs = {
  iam: iamAPI,
  basic: basicAPI,
  chat: chatAPI,
  cloudsave: cloudSaveAPI,
  config: configAPI,
  friends: friendsAPI,
  gdpr: gdprAPI,
  group: groupAPI,
  legal: legalAPI,
  lobby: lobbyAPI,
  match2: match2API,
  platform: platformAPI,
  session: sessionAPI,
  social: socialAPI,
  ugc: ugcAPI,
  sessionhistory: sessionHistoryAPI,
  turnmanager: turnmanagerAPI,
  dshub: dsHubAPI,
  achievement: achievementAPI
}

export function getThresholds(services: string[]): object {
  const result = {
    'retry{prot:http}': ['count>=0'],
    'retry{name:lobby_reconnect}': ['count>=0']
  };
  for (let i = 0; i < services.length; i++) {
    let serviceAPI = serviceAPIs[services[i]];
    Object.keys(serviceAPI).forEach(apiName => {
      result[`retry{name:${apiName}}`] = ['count>=0'];
      result[`failed_retry{name:${apiName}}`] = ['count>=0'];
      result[`http_reqs{name:${apiName}}`] = ['count>=0'];
      result[`http_req_duration{name:${apiName}}`] = ['p(99)<=2000'];
      result[`http_req_failed{name:${apiName}}`] = ['rate<0.01'];
      result[`timeout{name:${apiName}}`] = ['count>=0'];
    });
  }
  
  return result;
}