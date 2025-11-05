import { uuid } from "./lib/utils.js";
import * as loginAPI from "./api/iam.js"
import { SharedArray } from 'k6/data';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';

const data = new SharedArray('base data', function () {
  return JSON.parse(open('./config.json')).data;
});

const usersData = new SharedArray('open csv', function () {
  return papaparse.parse(open('./hosting-5k-user.csv'), { header: true }).data;
});

export const options = {
  scenarios:{
    default: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '5m',
    }
  }
}

export function login(user) {
  let state = uuid(2)
  let codeChallenge = uuid(2)
  const resAuth = loginAPI.iamAuthorize(data[0].baseUrl,data[0].iamClientIdGame, state, codeChallenge)
  const requestId = (resAuth.url).match(/request_id=([^&]*)/)[1]

  const resAtuhenticate = loginAPI.iamAuthenticate(data[0].baseUrl,usersData[user].email, data[0].userLoginPassword, requestId)
  const resCode = (resAtuhenticate.url).match(/code=([^&]*)/)[1]
  const resState = (resAtuhenticate.url).match(/state=([^&]*)/)[1]
  
  const resGetTokenV3 = loginAPI.iamGetTokenV3(data[0].baseUrl,resCode, codeChallenge, data[0].iamClientIdGame)
  let accessToken = JSON.parse(resGetTokenV3.body)['access_token']
  return accessToken
}

export default function() {
	// login(__VU)
  loginAPI.getTokenViaGrantType(data[0].baseUrl, usersData[__VU].email, data[0].userLoginPassword, data[0].clientId, data[0].clientSecret)
}