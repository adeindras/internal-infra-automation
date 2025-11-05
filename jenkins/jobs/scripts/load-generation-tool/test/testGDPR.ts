import * as gdprAPI from "../api/gdpr.js";
import { getTokenViaGrantType } from "../api/iam.js";
import { checkResponse } from "../lib/utils.js";
import { Dependencies } from "../models/dependencies.js";

let needRelogin = false;
let adminAccessToken = '';
export function callTestGDPR(baseUrl, namespaceGame, namespacePublisher, accessToken, adminToken) {
  checkResponse(gdprAPI.getDeletionStatus(baseUrl, accessToken), {
    'get deletion status': r => r && r.status === 200
  });
  checkResponse(gdprAPI.getRegisteredServicesConfiguration(baseUrl, namespacePublisher, adminToken), {
    'get registered service config': r => r && r.status === 200
  });
}

export function testGDPR(dep:Dependencies) {
  if (needRelogin || dep.adminAccessToken) {
    dep.adminAccessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.configData.adminEmail, 
      dep.configData.adminPassword, 
      dep.configData.clientId, 
      dep.configData.clientSecret
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

  callTestGDPR(dep.configData.baseURLDirect, dep.configData.namespaceGame, dep.configData.namespacePublisher, dep.accessToken, adminAccessToken);
}