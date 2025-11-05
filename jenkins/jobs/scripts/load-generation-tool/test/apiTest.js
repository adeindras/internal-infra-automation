import * as iamAPI from "../api/iam.js"
import { callTestUGC } from "./testUGC.js"
import { callTestCloudsave } from "./testCloudsave.js"
import { callTestConfig } from "./testConfig.js"
import { callTestFriends } from "./testFriends.js"
import { callTestGroup } from "./testGroup.js"
import { callTestPlatform } from "./testPlatform.js"
import { callTestSession } from "./testSession.js"
import { callTestSocial } from "./testSocial.js"
import { SharedArray } from 'k6/data';

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
      maxDuration: '1m',
    }
  }
}

export default function() {
  let accessToken = iamAPI.getTokenViaGrantType(data[0].baseUrl, usersData[__VU].email, data[0].userLoginPassword, data[0].clientId, data[0].clientSecret);
  let baseUrl = data[0].baseUrl
  let namespaceGame = data[0].namespaceGame
  //// =========== uncomment to include more services =========== ////

  // callTestUGC(baseUrl, namespaceGame, accessToken);
  // callTestCloudsave(baseUrl, namespaceGame, gProfile.json("userId"), data[0].gameRecordKey, accessToken)
  // callTestConfig(baseUrl, namespaceGame, accessToken);
  // callTestFriends(baseUrl, namespaceGame, accessToken);
  // callTestGroup(baseUrl, namespaceGame, accessToken);
  // callTestPlatform(baseUrl, namespaceGame, accessToken);
  // callTestSession(baseUrl, namespaceGame, accessToken);
  // callTestSocial(baseUrl, namespaceGame, gProfile.json("userId"), accessToken);
  // callTestUGC(baseUrl, namespaceGame, accessToken);

  // ============================================================ ////
}