import { Dependencies } from "../models/dependencies.ts";
import * as platformAPI from "../api/platform.js"
import { getTokenViaGrantType, loginViaAuthFlow } from "../api/iam.js"
import { checkResponse } from "../lib/utils.js";

let adminAccessToken = '';
export function callTestPlatform (baseUrl, namespaceGame, userId, virtualCurrency, accessToken) {
  const now = new Date();
  const expiredDate = new Date(now.getTime() + 60 * 60 * 1000);
  // checkResponse(platformAPI.getCurrencies(baseUrl, namespaceGame, accessToken), {
  //   'get currencies': r => r && r.status === 200
  // });
  checkResponse(platformAPI.updateCurrencies(baseUrl, namespaceGame, virtualCurrency, adminAccessToken), {
    'update currency': r => r && r.status === 200
  });
  
  checkResponse(platformAPI.creditUserWallet(baseUrl, namespaceGame, virtualCurrency, userId, expiredDate.toISOString(), adminAccessToken), {
    'credit user wallet': r => r && r.status === 200
  });
}

export function testPlatform(dep:Dependencies) {
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
  }
  adminAccessToken = dep.adminAccessToken;

  callTestPlatform(dep.configData.baseURLDirect, dep.configData.namespaceGame, dep.userData.userId, dep.configData.virtualCurrency, dep.adminAccessToken);
}
