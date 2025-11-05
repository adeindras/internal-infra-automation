import { Dependencies } from "../models/dependencies.ts";
import * as socialAPI from "../api/social.js"
import { checkResponse, uuid } from "../lib/utils.js";
import { getTokenViaGrantType, getProfile } from "../api/iam.js"

let adminAccessToken = '';
export function callTestSocial(baseUrl, namespaceGame, userId, statCode, accessToken, adminAccessToken) {
  const serverStatsCode = `tes-stats-code-${userId}`
  let res = socialAPI.createStats(baseUrl, namespaceGame, {
    statCode: serverStatsCode,
    name: 'test stats code',
    defaultValue: 0,
    setBy: 'SERVER'
  }, adminAccessToken);
  checkResponse(res, {'create test stats success': r => r && r.status === 201});
  res = socialAPI.adminUpdateStatCode(baseUrl, namespaceGame, uuid(2), statCode, adminAccessToken);
  checkResponse(res, {'update stats success': r => r && r.status === 200});
  res = socialAPI.updateStatsValue(baseUrl, namespaceGame, userId, statCode, 1, accessToken);
  checkResponse(res, {'update stats value success': r => r && r.status === 200});
  res = socialAPI.deleteStats(baseUrl, namespaceGame, serverStatsCode, adminAccessToken);
  checkResponse(res, {'delete stats value success': r => r && r.status === 204});
}

export function testSocial (dep:Dependencies) {
  if (!dep.accessToken) {
    dep.accessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.userData.emailAddress, 
      dep.userData.password, 
      dep.seedData.gameClientId, 
      dep.seedData.gameClientSecret
    )?.toString()!;
  }
  if (!dep.adminAccessToken) {
    dep.adminAccessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.configData.adminEmail, 
      dep.configData.adminPassword, 
      dep.seedData.gameClientId, 
      dep.seedData.gameClientSecret
    )?.toString()!;
    adminAccessToken = dep.adminAccessToken;
  }
  callTestSocial(dep.configData.baseURLDirect, dep.configData.namespaceGame, dep.userData.userId, dep.configData.statCode, dep.accessToken, dep.adminAccessToken);
}