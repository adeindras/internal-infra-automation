import { Dependencies } from "../models/dependencies.ts";
import * as ugcAPI from "../api/ugc.js"
import { getTokenViaGrantType, getProfile } from "../api/iam.js"

export function callTestUGC(baseUrl, namespaceGame, accessToken) {
  ugcAPI.getContentV2(baseUrl, namespaceGame, accessToken);
  ugcAPI.getUGCType(baseUrl, namespaceGame, accessToken);
}

export function testUGC (dep:Dependencies) {
  if (!dep.accessToken) {
    dep.accessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.userData.emailAddress, 
      dep.userData.password, 
      dep.seedData.gameClientId, 
      dep.seedData.gameClientSecret
    )?.toString()!;
  }

  callTestUGC(dep.configData.baseURLDirect, dep.configData.namespaceGame, dep.accessToken);
}