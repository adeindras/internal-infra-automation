import * as basicAPI from "../api/basic.js"
import { getTokenViaGrantType } from "../api/iam.js"
import { checkResponse } from "../lib/utils.js";
import { Dependencies } from "../models/dependencies.js";

let userId = '';
let adminAccessToken = '';
export function callTestBasic(baseUrl, namespaceGame, accessToken) {
  const res = basicAPI.createProfile(baseUrl, namespaceGame, accessToken);
  checkResponse(res, {
    'create profile': r => r && r.status === 201,
  });
  userId = res.json('userId')?.toString()!;
  checkResponse(basicAPI.updateProfile(baseUrl, namespaceGame, accessToken), {
    'update profile': r => r && r.status === 200
  });
  checkResponse(basicAPI.getProfile(baseUrl, namespaceGame, accessToken), {
    'get profile': r => r && r.status === 200
  });
  checkResponse(basicAPI.deleteProfile(baseUrl, namespaceGame, userId, adminAccessToken), {
    'delete profile': r => r && r.status === 200
  });
}

export function testBasic(dep:Dependencies) {
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
      dep.configData.clientId, 
      dep.configData.clientSecret
    )?.toString()!;
  }
  adminAccessToken = dep.adminAccessToken;

  callTestBasic(dep.configData.baseURLDirect, dep.configData.namespaceGame, dep.accessToken);
}